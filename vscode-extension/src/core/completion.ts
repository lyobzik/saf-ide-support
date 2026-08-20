import { allKeywords, jinja, keywordsByCategory } from "./contract";
import { propertyName, propertyValue, propertyOf, stringNodeAt, type Node } from "./ast";
import { isJinja } from "./jinja";
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

export type CompletionKind = "keyword" | "name" | "field";

export interface CompletionItem {
  readonly label: string;
  /** Подпись справа в списке: `type`, вид сущности или `field`. */
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
 * Контекст между `{{` и кареткой, открывающий completion имени поля:
 * опциональные пробелы, переменная формы, точка и, возможно, начатый
 * идентификатор. Полный матч: цепочка (`main_form.x.`) или не-идентификатор
 * (`main_form.2`) completion не открывают — такие позиции всё равно не резолвятся.
 */
const formContextPattern = new RegExp(
  String.raw`^\s*${escapeRegExp(jinja.formVariable)}\s*\.\s*(?:[A-Za-z_$À-￿][A-Za-z0-9_$À-￿]*)?$`,
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
  if (property !== undefined && propertyValue(property) === node && propertyName(property) === "type") {
    return keywordCompletion(context, property, node, offset);
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
 * Поля целевой формы внутри `{{ main_form.<caret> }}`. Форма определяется так же,
 * как при резолве; при динамической форме вариантов нет.
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
  // Fallback с достроенным `}}`: в момент набора интерполяция обычно ещё не
  // закрыта, и лексер отдаёт её одним TEXT.
  const interpOpenEnd =
    interpContextAt(decoded.text, decodedCaret) ??
    interpContextAt(`${decoded.text}}}`, decodedCaret);
  if (interpOpenEnd === undefined) return EMPTY;

  const afterInterp = decoded.text.slice(interpOpenEnd, decodedCaret);
  if (!formContextPattern.test(afterInterp)) return EMPTY;

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
 * Смещение конца открывающего `{{`, если каретка стоит внутри актуальной
 * открытой интерполяции. Каретка внутри statement-тега или строки Jinja
 * интерполяцией не считается.
 */
function interpContextAt(decodedText: string, decodedCaret: number): number | undefined {
  let inInterp = false;
  let interpOpenEnd = -1;

  for (const token of tokenize(decodedText)) {
    if (token.start >= decodedCaret) break;
    switch (token.type) {
      case TokenType.INTERP_OPEN:
        inInterp = true;
        interpOpenEnd = token.end;
        break;
      case TokenType.INTERP_CLOSE:
        inInterp = false;
        break;
      case TokenType.STATEMENT_OPEN:
        inInterp = false;
        break;
      case TokenType.STRING:
        // Каретка строго внутри строки — это не позиция поля формы.
        if (decodedCaret < token.end) return undefined;
        break;
      default:
        break;
    }
  }
  return inInterp && interpOpenEnd >= 0 ? interpOpenEnd : undefined;
}

/** Имя, которое лексер способен распознать как идентификатор поля. */
function isLexerIdentifier(name: string): boolean {
  return /^[A-Za-z_$À-￿][A-Za-z0-9_$À-￿]*$/.test(name);
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}
