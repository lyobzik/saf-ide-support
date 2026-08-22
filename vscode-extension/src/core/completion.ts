import { allKeywords, jinja, keywordsByCategory, typeContext } from "./contract";
import { propertyName, propertyValue, propertyOf, stringNodeAt, type Node } from "./ast";
import { isJinja } from "./jinja";
import { isFileReference, isOfferablePath, searchDirs } from "./fileRefRules";
import { decode, rawText } from "./jsonDecode";
import { TokenType, tokenize } from "./jinjaLexer";
import { targetFormOf } from "./fieldRef";
import { targetKinds } from "./refRules";
import { categoryFor } from "./typeContext";
import type { SmartAppIndex } from "./index";
import type { DocumentContext } from "./semantics";
import { isValueNode } from "./ast";

/**
 * Автодополнение: значения type, имена сущностей и поля формы в Jinja.
 * Порт `SmartAppCompletionContributor`.
 *
 * Ядро отдаёт метки и диапазон замены в смещениях; собрать из этого
 * `CompletionItem` — дело адаптера.
 */

export type CompletionKind = "keyword" | "name" | "field" | "file" | "variable";

export interface CompletionItem {
  readonly label: string;
  /** Подпись справа в списке: `type`, вид сущности, `field`, `file` или имя формы. */
  readonly detail: string;
}

export interface CompletionResult {
  readonly kind: CompletionKind;
  readonly items: readonly CompletionItem[];
  /** Диапазон документа, который заменяет выбранный вариант. */
  readonly replaceStart: number;
  readonly replaceEnd: number;
}

const EMPTY: CompletionResult = { kind: "name", items: [], replaceStart: 0, replaceEnd: 0 };

/**
 * Хвост выражения перед кареткой, открывающий completion имени поля: переменная
 * формы, точка и, возможно, начатый идентификатор — и конец. Проверяется именно
 * хвост: слева в выражении стоит ещё и `if `, `set x = `, `not ` и т.п.
 *
 * Слева от переменной допустимо: начало выражения; любой символ, не входящий в
 * идентификатор и не точка (`(`, `,`, `=`); пробел, перед которым нет точки.
 * Исключение — `|`: после него Jinja ждёт имя фильтра, а не значение.
 * Отвергаются `variables.main_form.` и `variables. main_form.` (обращение к
 * чужому полю) и `xmain_form.` (другое имя). Пробел без этого разбора отвергать
 * нельзя: `{% if main_form.<caret> %}` — обычнейшая позиция в бою.
 *
 * Цепочка (`main_form.x.`) и не-идентификатор (`main_form.2`) completion не
 * открывают — такие позиции всё равно не резолвятся.
 */
const formContextPattern = new RegExp(
  String.raw`(?:^|[^A-Za-z0-9_$À-￿.\s|]|(?<![.\s|])\s)\s*${escapeRegExp(jinja.formVariable)}\s*\.\s*` +
    String.raw`(?:[A-Za-z_$À-￿][A-Za-z0-9_$À-￿]*)?$`,
);

/**
 * Хвост выражения перед кареткой, открывающий completion самой переменной
 * формы: начатый идентификатор (возможно пустой), перед которым нет точки.
 * Левая граница — та же, что у поля: начало выражения, не-идентификатор или
 * пробел без точки перед ним. `variables.mai` и `variables. mai` — обращение к
 * чужому объекту, там переменная формы не при чём; `x | mai` — позиция имени
 * фильтра, значение там не подставляется.
 */
const variableContextPattern = new RegExp(
  String.raw`(?:^|[^A-Za-z0-9_$À-￿.\s|]|(?<![.\s|])\s)\s*` +
    String.raw`(?:[A-Za-z_$À-￿][A-Za-z0-9_$À-￿]*)?$`,
);

export function completionAt(
  index: SmartAppIndex,
  context: DocumentContext,
  offset: number,
): CompletionResult {
  if (context.kind === undefined) return EMPTY;

  const node = stringNodeAt(context.parsed.root, offset);
  if (node === undefined || !isValueNode(node)) return EMPTY;

  const value = node.value as string;

  // Ранняя Jinja-ветка: она обязана идти первой, иначе обычная ссылочная ветка
  // предложит имена форм там, где ожидается имя поля.
  if (isJinja(value)) {
    return jinjaCompletion(index, context, node, offset);
  }

  const property = propertyOf(node);
  if (
    property !== undefined &&
    propertyValue(property) === node &&
    propertyName(property) === typeContext.typeProperty
  ) {
    return keywordCompletion(context, property, node, offset);
  }

  if (isFileReference(node)) {
    return fileCompletion(index, context, node, offset);
  }

  return nameCompletion(index, context, node, offset);
}

function keywordCompletion(
  context: DocumentContext,
  property: Node,
  node: Node,
  offset: number,
): CompletionResult {
  const category = categoryFor(property, context.kind);
  const scoped = category === undefined ? undefined : keywordsByCategory.get(category);
  const keywords = scoped !== undefined && scoped.size > 0 ? scoped : allKeywords;

  return {
    kind: "keyword",
    items: [...keywords].map((label) => ({ label, detail: "type" })),
    ...literalRange(node, offset),
  };
}

function nameCompletion(
  index: SmartAppIndex,
  context: DocumentContext,
  node: Node,
  offset: number,
): CompletionResult {
  if (!index.isReady()) return EMPTY;

  const kinds = targetKinds(node, context.kind);
  if (kinds.length === 0) return EMPTY;

  const items: CompletionItem[] = [];
  for (const kind of kinds) {
    for (const name of index.namesOfKind(kind, context.scopeRoot)) {
      items.push({ label: name, detail: kind.toLowerCase() });
    }
  }
  return { kind: "name", items, ...literalRange(node, offset) };
}

/**
 * Файлы каталогов файловой ссылки (`"file"` при `type: unified_template`) —
 * пути относительно каталога поиска, вместе с расширением: ровно то, что
 * принимает резолв.
 *
 * Ветка молчит до готовности индекса: реестр файлов набора наполняется в ходе
 * первичного сканирования, и частичный список молча изменился бы под
 * пользователем. В плагине IDEA гейта нет — там источник (VFS) полон всегда.
 */
function fileCompletion(
  index: SmartAppIndex,
  context: DocumentContext,
  node: Node,
  offset: number,
): CompletionResult {
  if (!index.isReady() || context.scopeRoot === undefined) return EMPTY;

  const items: CompletionItem[] = [];
  for (const path of index.filesInDirs(context.scopeRoot, searchDirs(node))) {
    if (!isOfferablePath(path)) continue;
    items.push({ label: path, detail: "file" });
  }
  return { kind: "file", items, ...literalRange(node, offset) };
}

/**
 * Completion внутри выражения Jinja — интерполяции или statement-тега:
 *  - после `main_form.<caret>` — поля целевой формы;
 *  - на месте самого идентификатора (`{% if mai<caret> %}`) — переменная формы.
 *
 * Форма определяется так же, как при резолве; при динамической форме вариантов
 * нет ни там, ни там: предлагать имя, за которым не стоит известной формы,
 * значит предлагать вариант без семантики.
 */
function jinjaCompletion(
  index: SmartAppIndex,
  context: DocumentContext,
  node: Node,
  offset: number,
): CompletionResult {
  if (!index.isReady()) return EMPTY;

  const raw = rawText(context.text.slice(node.offset, node.offset + node.length));
  if (raw === undefined) return EMPTY;

  // Позиция каретки в raw-координатах литерала (без открывающей кавычки).
  const rawCaret = offset - node.offset - 1;
  if (rawCaret < 0 || rawCaret > raw.length) return EMPTY;

  const decoded = decode(raw);
  const decodedCaret = decodedCaretOf(decoded.decodedToRaw, rawCaret);

  // Контекст каретки определяем тем же лексером, что подсветка и резолв.
  // Fallback с достроенным разделителем: в момент набора выражение обычно ещё
  // не закрыто, и лексер отдаёт его одним TEXT.
  const exprOpenEnd =
    exprContextAt(decoded.text, decodedCaret) ??
    exprContextAt(`${decoded.text}}}`, decodedCaret) ??
    exprContextAt(`${decoded.text}%}`, decodedCaret);
  if (exprOpenEnd === undefined) return EMPTY;

  const afterOpen = decoded.text.slice(exprOpenEnd, decodedCaret);
  const fields = formContextPattern.test(afterOpen);
  if (!fields && !variableContextPattern.test(afterOpen)) return EMPTY;

  const form = targetFormOf(node, context.kind);
  if (form === undefined) return EMPTY;

  // Префикс поля — часть идентификатора после последней точки (пробел после
  // точки в идентификатор не входит); префикс переменной — сам начатый
  // идентификатор.
  const beforeCaret = decoded.text.slice(0, decodedCaret);
  const prefix = fields
    ? beforeCaret.slice(beforeCaret.lastIndexOf(".") + 1).replace(/^\s+/, "")
    : (/[A-Za-z0-9_$À-￿]*$/.exec(beforeCaret)?.[0] ?? "");

  const items: CompletionItem[] = [];
  if (fields) {
    for (const field of index.fieldsOfForm(form, context.scopeRoot)) {
      // Поля с не-identifier именами лексер резолвить не способен — предлагать их
      // значит предлагать заведомо битые варианты (контракт v1).
      if (!isLexerIdentifier(field)) continue;
      items.push({ label: field, detail: "field" });
    }
  } else {
    // Подпись — имя целевой формы: из текста её не видно, а именно она решает,
    // какие поля будут дальше.
    items.push({ label: jinja.formVariable, detail: form });
  }

  return {
    kind: fields ? "field" : "variable",
    items,
    replaceStart: offset - prefix.length,
    replaceEnd: identifierEnd(decoded, decodedCaret, node, offset),
  };
}

/**
 * Конец идентификатора, начатого до каретки, в координатах документа: вариант
 * заменяет слово целиком, а не только набранное начало. Иначе принятое
 * посреди слова `main_form` дало бы `main_formform`.
 */
function identifierEnd(
  decoded: { text: string; decodedToRaw: readonly number[] },
  decodedCaret: number,
  node: Node,
  offset: number,
): number {
  let end = decodedCaret;
  while (end < decoded.text.length && /[A-Za-z0-9_$À-￿]/.test(decoded.text[end] as string)) end++;
  const raw = decoded.decodedToRaw[end];
  // Открывающая кавычка литерала — +1 к смещению узла.
  return raw === undefined ? offset : node.offset + 1 + raw;
}

/** Диапазон содержимого литерала (без кавычек), устойчивый к незакрытой строке. */
function literalRange(node: Node, offset: number): { replaceStart: number; replaceEnd: number } {
  const start = node.offset + 1;
  const end = node.offset + node.length - 1;
  if (end < start) return { replaceStart: offset, replaceEnd: offset };
  return { replaceStart: start, replaceEnd: Math.max(end, offset) };
}

/**
 * Позиция каретки в decoded-координатах: число decoded-символов, чей raw-старт
 * строго левее raw-позиции каретки.
 */
function decodedCaretOf(decodedToRaw: readonly number[], rawCaret: number): number {
  let decodedCaret = 0;
  // Последний элемент карты — sentinel (конец текста), символом не является.
  while (decodedCaret < decodedToRaw.length - 1 && (decodedToRaw[decodedCaret] as number) < rawCaret) {
    decodedCaret++;
  }
  return decodedCaret;
}

/**
 * Смещение конца открывающего разделителя (`{{` или `{%`), если каретка стоит
 * внутри актуального открытого выражения. Каретка внутри строки Jinja позицией
 * поля не считается.
 */
function exprContextAt(decodedText: string, decodedCaret: number): number | undefined {
  let inExpr = false;
  let exprOpenEnd = -1;

  for (const token of tokenize(decodedText)) {
    if (token.start >= decodedCaret) break;
    switch (token.type) {
      case TokenType.INTERP_OPEN:
      case TokenType.STATEMENT_OPEN:
        inExpr = true;
        exprOpenEnd = token.end;
        break;
      case TokenType.INTERP_CLOSE:
      case TokenType.STATEMENT_CLOSE:
        inExpr = false;
        break;
      case TokenType.STRING:
        // Каретка строго внутри строки — это не позиция поля формы.
        if (decodedCaret < token.end) return undefined;
        break;
      default:
        break;
    }
  }
  return inExpr && exprOpenEnd >= 0 ? exprOpenEnd : undefined;
}

/** Имя, которое лексер способен распознать как идентификатор поля. */
function isLexerIdentifier(name: string): boolean {
  return /^[A-Za-z_$À-￿][A-Za-z0-9_$À-￿]*$/.test(name);
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}
