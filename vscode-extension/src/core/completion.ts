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

export type CompletionKind = "keyword" | "name" | "field" | "file";

export interface CompletionItem {
  readonly label: string;
  /** Подпись справа в списке: `type`, вид сущности, `field` или `file`. */
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
 * формы, точка и, возможно, начатый идентификатор — и конец. Слева от переменной
 * — начало выражения либо ближайший непробельный символ, не входящий в
 * идентификатор и не точка: `variables.main_form.` — обращение к чужому полю.
 * Проверяется именно хвост: в statement-теге слева стоит ещё и `set x = `.
 * Цепочка (`main_form.x.`) и не-идентификатор (`main_form.2`) completion не
 * открывают — такие позиции всё равно не резолвятся.
 */
const formContextPattern = new RegExp(
  String.raw`(?:^|[^A-Za-z0-9_$À-￿.\s])\s*${escapeRegExp(jinja.formVariable)}\s*\.\s*` +
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
    return fieldCompletion(index, context, node, offset);
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
 * Поля целевой формы после `main_form.<caret>` внутри выражения Jinja —
 * интерполяции или statement-тега. Форма определяется так же, как при резолве;
 * при динамической форме вариантов нет.
 */
function fieldCompletion(
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
  if (!formContextPattern.test(afterOpen)) return EMPTY;

  const form = targetFormOf(node, context.kind);
  if (form === undefined) return EMPTY;

  // Префикс — часть идентификатора после последней точки; пробел после точки в
  // идентификатор не входит.
  const beforeCaret = decoded.text.slice(0, decodedCaret);
  const prefix = beforeCaret.slice(beforeCaret.lastIndexOf(".") + 1).replace(/^\s+/, "");

  const items: CompletionItem[] = [];
  for (const field of index.fieldsOfForm(form, context.scopeRoot)) {
    // Поля с не-identifier именами лексер резолвить не способен — предлагать их
    // значит предлагать заведомо битые варианты (контракт v1).
    if (!isLexerIdentifier(field)) continue;
    items.push({ label: field, detail: "field" });
  }

  return { kind: "field", items, replaceStart: offset - prefix.length, replaceEnd: offset };
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
