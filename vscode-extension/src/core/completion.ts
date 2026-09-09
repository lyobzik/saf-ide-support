import { allKeywords, jinja, keywordsByCategory, typeContext } from "./contract";
import { propertyName, propertyValue, propertyOf, stringNodeAt, type Node } from "./ast";
import { isJinja } from "./jinja";
import { isFileReference, isOfferablePath, searchDirs } from "./fileRefRules";
import { decode, rawText } from "./jsonDecode";
import {
  IDENTIFIER_PART_CLASS,
  IDENTIFIER_START_CLASS,
  isAddressableName,
  isIdentifierPart,
} from "./identifiers";
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

export type CompletionKind = "keyword" | "name" | "field" | "file" | "variable" | "user_field";

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
 * Левая граница обращения: начало выражения; символ, не входящий в
 * идентификатор и не точка (`(`, `,`, `=`); пробел, перед которым нет точки.
 * Исключение — `|`: после него Jinja ждёт имя фильтра, а не значение.
 *
 * Классы символов берутся из грамматики идентификатора, а не пишутся руками:
 * иначе к двум определениям возвращаются через шаблон.
 */
const LEFT_BOUNDARY = `(?:^|[^${IDENTIFIER_PART_CLASS}.\\s|]|(?<![.\\s|])\\s)`;

/** Начатый (возможно пустой) идентификатор. */
const OPTIONAL_IDENTIFIER =
  `(?:[${IDENTIFIER_START_CLASS}][${IDENTIFIER_PART_CLASS}]*)?`;

/** Набранное начало идентификатора непосредственно перед кареткой. */
const identifierTail = new RegExp(`[${IDENTIFIER_PART_CLASS}]*$`, "u");

/** Хвост `<root>.<начатое имя>` в конце выражения. */
function rootTailPattern(root: string): RegExp {
  return new RegExp(
    `${LEFT_BOUNDARY}\\s*${escapeRegExp(root)}\\s*\\.\\s*${OPTIONAL_IDENTIFIER}$`,
    "u",
  );
}

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
const formContextPattern = rootTailPattern(jinja.formVariable);

/**
 * Хвост выражения перед кареткой, открывающий completion самой переменной
 * формы: начатый идентификатор (возможно пустой), перед которым нет точки.
 * Левая граница — та же, что у поля: начало выражения, не-идентификатор или
 * пробел без точки перед ним. `variables.mai` и `variables. mai` — обращение к
 * чужому объекту, там переменная формы не при чём; `x | mai` — позиция имени
 * фильтра, значение там не подставляется.
 */
const variableContextPattern = new RegExp(
  `${LEFT_BOUNDARY}\\s*${OPTIONAL_IDENTIFIER}$`,
  "u",
);

/**
 * Хвост выражения `<root>.<начатое имя>` для произвольной корневой переменной.
 * Корневых имён модели пользователя несколько и они зависят от приложения,
 * поэтому шаблон строится по имени и запоминается.
 */
const rootPatterns = new Map<string, RegExp>();

function rootContextPattern(root: string): RegExp {
  const cached = rootPatterns.get(root);
  if (cached !== undefined) return cached;
  const pattern = rootTailPattern(root);
  rootPatterns.set(root, pattern);
  return pattern;
}

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
    return keywordCompletion(index, context, property, node, offset);
  }

  if (isFileReference(node)) {
    return fileCompletion(index, context, node, offset);
  }

  return nameCompletion(index, context, node, offset);
}

function keywordCompletion(
  index: SmartAppIndex,
  context: DocumentContext,
  property: Node,
  node: Node,
  offset: number,
): CompletionResult {
  const category = categoryFor(property, context.kind);
  const scoped = category === undefined ? undefined : keywordsByCategory.get(category);
  const keywords = scoped !== undefined && scoped.size > 0 ? scoped : allKeywords;
  const items: CompletionItem[] = [...keywords].map((label) => ({ label, detail: "type" }));

  // Слова приложения: подпись — класс, который за ними стоит. Из значения type
  // его не видно, а это единственное, чем кастомное слово отличается от
  // фреймворкового.
  const offered = new Set(items.map((item) => item.label));
  for (const keyword of index.customKeywordsOf(context.scopeRoot)) {
    if (category !== undefined && keyword.category !== category) continue;
    if (offered.has(keyword.name)) continue;
    offered.add(keyword.name);
    items.push({ label: keyword.name, detail: keyword.className ?? "type" });
  }

  return { kind: "keyword", items, ...literalRange(node, offset) };
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
  // Корневое имя предлагается только вместе со словарём: без модели за ним не
  // стоит ничего, и вариант был бы пустым обещанием.
  const model = index.userModelOf(context.scopeRoot);
  const roots = model === undefined ? [] : (index.userRootOf(context.scopeRoot)?.names ?? []);
  const fields = formContextPattern.test(afterOpen);
  // После `<корень>.` предлагаются атрибуты модели пользователя. Проверяется
  // после формы: при совпадении имён (приложение связало `self._user` с
  // `main_form`) корень из набора уже вычтен контрактом (план, раздел 0).
  const userRoot = fields
    ? undefined
    : roots.find((root) => rootContextPattern(root).test(afterOpen));
  const variable = !fields && userRoot === undefined && variableContextPattern.test(afterOpen);
  if (!fields && userRoot === undefined && !variable) return EMPTY;

  const form = targetFormOf(node, context.kind);
  // Полю формы известная форма нужна; модели пользователя — нет вовсе, её
  // словарь один и тот же на любой рендер (план, раздел 0).
  if (fields && form === undefined) return EMPTY;

  // Префикс поля — часть идентификатора после последней точки (пробел после
  // точки в идентификатор не входит); префикс переменной — сам начатый
  // идентификатор.
  const beforeCaret = decoded.text.slice(0, decodedCaret);
  const afterDot = fields || userRoot !== undefined;
  const prefix = afterDot
    ? beforeCaret.slice(beforeCaret.lastIndexOf(".") + 1).replace(/^\s+/, "")
    : (identifierTail.exec(beforeCaret)?.[0] ?? "");

  const items: CompletionItem[] = [];
  let kind: CompletionKind = "variable";
  if (fields && form !== undefined) {
    kind = "field";
    for (const field of index.fieldsOfForm(form, context.scopeRoot)) {
      // Поля с не-identifier именами лексер резолвить не способен — предлагать их
      // значит предлагать заведомо битые варианты (контракт v1).
      if (!isAddressableName(field)) continue;
      items.push({ label: field, detail: "field" });
    }
  } else if (userRoot !== undefined) {
    kind = "user_field";
    // Подпись — класс, чьи атрибуты предлагаются; у библиотечного активного
    // класса его имени нет, и подписью служит сам корень.
    const detail = model?.userClass?.name ?? userRoot;
    for (const name of model?.attributes.keys() ?? []) items.push({ label: name, detail });
  } else {
    // Подпись — имя целевой формы: из текста её не видно, а именно она решает,
    // какие поля будут дальше.
    if (form !== undefined) items.push({ label: jinja.formVariable, detail: form });
    for (const root of roots) {
      items.push({ label: root, detail: model?.userClass?.name ?? root });
    }
    // Ни формы, ни модели: предлагать имя без семантики незачем.
    if (items.length === 0) return EMPTY;
  }

  return {
    kind,
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
  while (end < decoded.text.length && isIdentifierPart(decoded.text[end] as string)) end++;
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
function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}
