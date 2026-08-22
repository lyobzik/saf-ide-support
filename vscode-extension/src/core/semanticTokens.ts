import { typeContext } from "./contract";
import { propertyName, propertyValue, propertyOf, type Node } from "./ast";
import { isJinja } from "./jinja";
import { decode, rawText } from "./jsonDecode";
import { TokenType, tokenize } from "./jinjaLexer";
import { isKeywordInContext, isStructuralKey, keywordCategoryAt, type DocumentContext } from "./semantics";
import { isValueNode } from "./ast";
import type { CustomKeyword } from "./resourceKeywords";

/**
 * Семантическая подсветка — порт `SmartAppAnnotator`.
 *
 * Работает на частичном дереве: подсветка не зависит от индекса и не гаснет на
 * недописанном файле, ровно как в IDEA (там аннотатор `DumbAware`, а
 * `JsonPsi.hasError` вызывается только индексаторами).
 *
 * [custom] — ключевые слова, зарегистрированные приложением: их источник
 * (Python-файлы) знает только индекс, поэтому список передаётся снаружи.
 */

/** Типы токенов; адаптер отображает их в легенду VS Code. */
export enum SemanticTokenType {
  /** Значение `type`, являющееся ключевым словом в своём контексте. */
  KEYWORD = "smartappKeyword",
  /** Структурный ключ DSL (`form`, `actions`, `fields`, …). */
  STRUCTURAL_KEY = "smartappStructuralKey",
  /** Разделители Jinja: `{{`, `}}`, `{%`, `%}`. */
  JINJA_DELIMITER = "smartappJinjaDelimiter",
  /** Переменная внутри Jinja. */
  JINJA_VARIABLE = "smartappJinjaVariable",
  /** Оператор доступа к полю (`.`). */
  JINJA_OPERATOR = "smartappJinjaOperator",
  /** `|` и имя фильтра. */
  JINJA_FILTER = "smartappJinjaFilter",
  /** Строковый литерал внутри Jinja. */
  JINJA_STRING = "smartappJinjaString",
}

export interface SemanticToken {
  readonly start: number;
  readonly end: number;
  readonly type: SemanticTokenType;
}

export function semanticTokens(
  context: DocumentContext,
  custom: readonly CustomKeyword[] = [],
): SemanticToken[] {
  if (context.kind === undefined) return [];
  const tokens: SemanticToken[] = [];

  visit(context.parsed.root, (node) => {
    if (node.type === "property") {
      const name = propertyName(node);
      const key = node.children?.[0];
      if (name !== undefined && key !== undefined && isStructuralKey(name)) {
        tokens.push({
          start: key.offset,
          end: key.offset + key.length,
          type: SemanticTokenType.STRUCTURAL_KEY,
        });
      }
      return;
    }
    if (node.type !== "string" || !isValueNode(node)) return;

    const value = node.value as string;
    if (isJinja(value)) {
      collectJinjaTokens(context, node, tokens);
      return;
    }

    const property = propertyOf(node);
    if (property === undefined || propertyValue(property) !== node) return;
    const category = keywordCategoryAt(node, context.kind);
    if (propertyName(property) !== typeContext.typeProperty) return;
    if (!isKeywordInContext(value, category, custom)) return;

    // Диапазон вместе с кавычками — как textRange литерала в IDEA.
    tokens.push({
      start: node.offset,
      end: node.offset + node.length,
      type: SemanticTokenType.KEYWORD,
    });
  });

  return tokens.sort((a, b) => a.start - b.start);
}

/** Токены Jinja-разметки, переведённые из decoded-координат в документные. */
function collectJinjaTokens(context: DocumentContext, node: Node, out: SemanticToken[]): void {
  const raw = rawText(context.text.slice(node.offset, node.offset + node.length));
  if (raw === undefined) return;

  const decoded = decode(raw);
  // +1 — открывающая кавычка литерала.
  const base = node.offset + 1;

  for (const token of tokenize(decoded.text)) {
    const type = jinjaTokenType(token.type);
    if (type === undefined) continue;
    const rawStart = decoded.decodedToRaw[token.start];
    const rawEnd = decoded.decodedToRaw[token.end];
    if (rawStart === undefined || rawEnd === undefined) continue;
    out.push({ start: base + rawStart, end: base + rawEnd, type });
  }
}

function jinjaTokenType(type: TokenType): SemanticTokenType | undefined {
  switch (type) {
    case TokenType.INTERP_OPEN:
    case TokenType.INTERP_CLOSE:
    case TokenType.STATEMENT_OPEN:
    case TokenType.STATEMENT_CLOSE:
      return SemanticTokenType.JINJA_DELIMITER;
    case TokenType.VAR:
      return SemanticTokenType.JINJA_VARIABLE;
    case TokenType.DOT:
      return SemanticTokenType.JINJA_OPERATOR;
    case TokenType.FILTER_OP:
    case TokenType.FILTER_NAME:
      return SemanticTokenType.JINJA_FILTER;
    case TokenType.STRING:
      return SemanticTokenType.JINJA_STRING;
    // Обычный текст не подсвечиваем.
    case TokenType.TEXT:
      return undefined;
    default:
      return undefined;
  }
}

function visit(root: Node | undefined, action: (node: Node) => void): void {
  if (root === undefined) return;
  const stack: Node[] = [root];
  while (stack.length > 0) {
    const node = stack.pop() as Node;
    action(node);
    for (const child of node.children ?? []) stack.push(child);
  }
}
