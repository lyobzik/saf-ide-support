import {
  KIND,
  allKeywords,
  fieldAccess,
  keywordsByCategory,
  structuralKeys,
  typeContext,
  type RefKind,
} from "./contract";
import {
  isValueNode,
  parseDocument,
  propertyName,
  propertyValue,
  propertyOf,
  stringNodeAt,
  type Node,
  type ParsedDocument,
} from "./ast";
import { kindOf, referencesRoot } from "./files";
import { isJinja } from "./jinja";
import { decode, rawText } from "./jsonDecode";
import { fieldCandidates, formVariableOccurrences } from "./jinjaLexer";
import { targetFormOf } from "./fieldRef";
import { targetKinds } from "./refRules";
import { candidateUris, isFileReference } from "./fileRefRules";
import { categoryFor } from "./typeContext";
import type { Definition, FieldDefinition, Location, SmartAppIndex } from "./index";

/**
 * Семантика поверх индекса: резолв, использования и диагностика.
 *
 * Ядро возвращает смещения; перевод в Position/Uri и в объекты VS Code — забота
 * адаптера. Благодаря этому conformance-корпус гоняется здесь напрямую, без
 * запуска редактора.
 */

export interface Diagnostic {
  readonly start: number;
  readonly end: number;
  readonly message: string;
}

export interface DocumentContext {
  readonly uri: string;
  readonly text: string;
  readonly kind: RefKind | undefined;
  readonly scopeRoot: string | undefined;
  readonly parsed: ParsedDocument;
}

export function documentContext(uri: string, text: string): DocumentContext {
  return {
    uri,
    text,
    kind: kindOf(uri),
    scopeRoot: referencesRoot(uri),
    parsed: parseDocument(text),
  };
}

/** Обращение к полю формы, найденное в Jinja-значении. */
export interface FieldHit {
  readonly form: string;
  readonly field: string;
  readonly start: number;
  readonly end: number;
}

/** Файл-цель: у ссылки на шаблон нет имени и ordinal, позиция — начало файла. */
export interface FileTarget extends Location {
  readonly target: "file";
}

/** Определения, на которые ведёт позиция offset. */
export function definitionsAt(
  index: SmartAppIndex,
  context: DocumentContext,
  offset: number,
): (Definition | FieldDefinition | FileTarget)[] {
  if (!index.isReady() || context.kind === undefined) return [];

  const node = stringNodeAt(context.parsed.root, offset);
  if (node === undefined) return [];

  const value = node.value as string;
  if (isJinja(value)) {
    // Сама переменная `main_form` ведёт на определение целевой формы.
    const form = formVariableAt(node, context, offset);
    if (form !== undefined) return index.findDefinitions(form, [KIND.FORM], context.scopeRoot);

    const hit = fieldHitAt(node, context, offset);
    if (hit === undefined) return [];
    return index.findFields(hit.form, hit.field, context.scopeRoot);
  }

  if (isFileReference(node)) {
    const uri = index.findFile(candidateUris(node, value, context.scopeRoot));
    return uri === undefined ? [] : [{ target: "file", uri, start: 0, end: 0 }];
  }

  const kinds = targetKinds(node, context.kind);
  if (kinds.length === 0) return [];
  return index.findDefinitions(value, kinds, context.scopeRoot);
}

/**
 * Использования сущности или поля под кареткой. Позиция может стоять и на
 * определении (top-level ключ либо имя поля), и на самой ссылке.
 */
export function referencesAt(
  index: SmartAppIndex,
  context: DocumentContext,
  offset: number,
  includeDeclaration: boolean,
): Location[] {
  if (!index.isReady() || context.kind === undefined) return [];

  const node = stringNodeAt(context.parsed.root, offset);
  if (node === undefined) return [];

  const value = node.value as string;
  if (isJinja(value)) {
    const hit = fieldHitAt(node, context, offset);
    if (hit === undefined) return [];
    return collectFieldOccurrences(index, context, hit.form, hit.field, includeDeclaration);
  }

  // Каретка на определении сущности: top-level ключ файла.
  const property = propertyOf(node);
  if (property !== undefined && property.children?.[0] === node && isTopLevelProperty(property)) {
    const usages = index.findUsages(value, context.kind, context.scopeRoot);
    return includeDeclaration
      ? dedupe([...index.findDefinitions(value, [context.kind], context.scopeRoot), ...usages])
      : usages;
  }

  // Каретка на определении поля формы.
  const fieldOwner = fieldDefinitionAt(context, node);
  if (fieldOwner !== undefined) {
    return collectFieldOccurrences(
      index,
      context,
      fieldOwner.form,
      fieldOwner.field,
      includeDeclaration,
    );
  }

  // Каретка на ссылке: показываем все её вхождения.
  const kinds = targetKinds(node, context.kind);
  if (kinds.length === 0) return [];
  const result: Location[] = [];
  for (const kind of kinds) {
    result.push(...index.findUsages(value, kind, context.scopeRoot));
    if (includeDeclaration) {
      result.push(...index.findDefinitions(value, [kind], context.scopeRoot));
    }
  }
  return dedupe(result);
}

/**
 * Предупреждения о неразрешённых ссылках и полях. Severity — warning, а не
 * error: целевая сущность может быть ещё не создана.
 */
export function diagnostics(index: SmartAppIndex, context: DocumentContext): Diagnostic[] {
  if (!index.isReady() || context.kind === undefined) return [];
  const result: Diagnostic[] = [];

  visitStringNodes(context.parsed.root, (node) => {
    const value = node.value as string;
    if (typeof value !== "string") return;

    if (isJinja(value)) {
      for (const hit of fieldHits(node, context)) {
        if (index.findFields(hit.form, hit.field, context.scopeRoot).length > 0) continue;
        result.push({
          start: hit.start,
          end: hit.end,
          message: `Не удаётся разрешить поле '${hit.field}' формы '${hit.form}'`,
        });
      }
      return;
    }

    // Ссылка на файл шаблона: цель ищется по пути, а не по индексу определений.
    if (isFileReference(node)) {
      if (value.length === 0) return;
      const candidates = candidateUris(node, value, context.scopeRoot);
      // Пустой список — значение динамическое или выходит за пределы каталога:
      // чем оно является, из контракта не следует, и молчание честнее ошибки.
      if (candidates.length === 0) return;
      if (index.findFile(candidates) !== undefined) return;
      result.push({
        start: node.offset,
        end: node.offset + node.length,
        message: `Не удаётся разрешить файл шаблона '${value}'`,
      });
      return;
    }

    const kinds = targetKinds(node, context.kind);
    if (kinds.length === 0 || value.length === 0) return;
    if (index.findDefinitions(value, kinds, context.scopeRoot).length > 0) return;

    const label = kinds.map((kind) => kind.toLowerCase()).join("/");
    result.push({
      start: node.offset,
      end: node.offset + node.length,
      message: `Не удаётся разрешить ${label} '${value}'`,
    });
  });

  return result;
}

/** Категория ключевых слов для значения type, если узел стоит в этой позиции. */
export function keywordCategoryAt(node: Node, kind: RefKind | undefined): string | undefined {
  const property = propertyOf(node);
  if (property === undefined || propertyValue(property) !== node) return undefined;
  if (propertyName(property) !== typeContext.typeProperty) return undefined;
  return categoryFor(property, kind);
}

/**
 * true, если значение — ключевое слово в своём контексте. Если категория
 * распознана, слово обязано принадлежать именно ей; иначе — мягкий откат к
 * принадлежности любой категории.
 */
export function isKeywordInContext(value: string, category: string | undefined): boolean {
  if (category === undefined) return allKeywords.has(value);
  return keywordsByCategory.get(category)?.has(value) === true;
}

export function isStructuralKey(name: string): boolean {
  return structuralKeys.has(name);
}

/** Все обращения к полям формы внутри Jinja-значения node. */
export function fieldHits(node: Node, context: DocumentContext): FieldHit[] {
  // Семантика полей — только у значений JSON: ключ "{{ main_form.name }}"
  // ссылкой не является (тот же контракт, что у SmartAppReferenceContributor).
  if (!isValueNode(node)) return [];
  const raw = rawText(context.text.slice(node.offset, node.offset + node.length));
  if (raw === undefined) return [];
  const form = targetFormOf(node, context.kind);
  if (form === undefined) return [];

  const decoded = decode(raw);
  const hits: FieldHit[] = [];
  for (const candidate of fieldCandidates(decoded.text)) {
    const rawStart = decoded.decodedToRaw[candidate.fieldStart];
    const rawEnd = decoded.decodedToRaw[candidate.fieldEnd];
    if (rawStart === undefined || rawEnd === undefined) continue;
    hits.push({
      form,
      field: candidate.field,
      // +1 — открывающая кавычка литерала.
      start: node.offset + 1 + rawStart,
      end: node.offset + 1 + rawEnd,
    });
  }
  return hits;
}

/**
 * Имя целевой формы, если [offset] стоит на переменной формы внутри Jinja.
 * Имени формы в тексте нет — оно вычислено по контексту, поэтому использованием
 * формы такое вхождение не считается и переименованию не подлежит.
 */
function formVariableAt(
  node: Node,
  context: DocumentContext,
  offset: number,
): string | undefined {
  if (!isValueNode(node)) return undefined;
  const raw = rawText(context.text.slice(node.offset, node.offset + node.length));
  if (raw === undefined) return undefined;
  const form = targetFormOf(node, context.kind);
  if (form === undefined) return undefined;

  const decoded = decode(raw);
  for (const occurrence of formVariableOccurrences(decoded.text)) {
    const rawStart = decoded.decodedToRaw[occurrence.start];
    const rawEnd = decoded.decodedToRaw[occurrence.end];
    if (rawStart === undefined || rawEnd === undefined) continue;
    // +1 — открывающая кавычка литерала.
    const start = node.offset + 1 + rawStart;
    const end = node.offset + 1 + rawEnd;
    if (containsCaret(start, end, offset)) return form;
  }
  return undefined;
}

/**
 * Попадает ли каретка в диапазон токена. Конец **включается**: это измеренное
 * поведение платформы IntelliJ (`TextRange.containsOffset`), проверенное
 * характеризационным тестом `testReferenceBoundaryAtDotIsInclusive`. Каретка
 * сразу за словом (например на точке после `main_form`) считается стоящей на
 * нём — иначе F12 в конце слова работал бы в двух редакторах по-разному.
 */
function containsCaret(start: number, end: number, offset: number): boolean {
  return offset >= start && offset <= end;
}

function fieldHitAt(node: Node, context: DocumentContext, offset: number): FieldHit | undefined {
  return fieldHits(node, context).find((hit) => containsCaret(hit.start, hit.end, offset));
}

function collectFieldOccurrences(
  index: SmartAppIndex,
  context: DocumentContext,
  form: string,
  field: string,
  includeDeclaration: boolean,
): Location[] {
  const usages = index.findFieldUsages(form, field, context.scopeRoot);
  return includeDeclaration
    ? dedupe([...index.findFields(form, field, context.scopeRoot), ...usages])
    : usages;
}

/** Определение поля формы, если node — ключ внутри fields формы. */
function fieldDefinitionAt(
  context: DocumentContext,
  node: Node,
): { form: string; field: string } | undefined {
  if (context.kind !== KIND.FORM) return undefined;
  const property = propertyOf(node);
  if (property === undefined || property.children?.[0] !== node) return undefined;

  const fieldsObject = property.parent;
  const fieldsProperty = fieldsObject?.parent;
  if (
    fieldsProperty?.type !== "property" ||
    propertyName(fieldsProperty) !== fieldAccess.fieldsProperty
  ) {
    return undefined;
  }
  const formProperty = fieldsProperty.parent?.parent;
  if (formProperty?.type !== "property" || !isTopLevelProperty(formProperty)) return undefined;

  const form = propertyName(formProperty);
  const field = propertyName(property);
  return form !== undefined && field !== undefined ? { form, field } : undefined;
}

function isTopLevelProperty(property: Node): boolean {
  return property.parent?.parent === undefined;
}

function visitStringNodes(root: Node | undefined, visit: (node: Node) => void): void {
  if (root === undefined) return;
  const stack: Node[] = [root];
  const collected: Node[] = [];
  while (stack.length > 0) {
    const node = stack.pop() as Node;
    if (node.type === "string") collected.push(node);
    for (const child of node.children ?? []) stack.push(child);
  }
  collected.sort((a, b) => a.offset - b.offset);
  for (const node of collected) visit(node);
}

function dedupe(locations: readonly Location[]): Location[] {
  const seen = new Set<string>();
  const result: Location[] = [];
  for (const location of locations) {
    const key = `${location.uri} ${location.start} ${location.end}`;
    if (seen.has(key)) continue;
    seen.add(key);
    result.push(location);
  }
  return result;
}
