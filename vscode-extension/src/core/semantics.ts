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
import { applicationRootOf, kindOf, referencesRoot } from "./files";
import { isJinja } from "./jinja";
import type { CustomKeyword } from "./resourceKeywords";
import { decode, rawText } from "./jsonDecode";
import { fieldCandidates, formVariableOccurrences, rootAccesses } from "./jinjaLexer";
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

/**
 * Обращение к модели пользователя, найденное в Jinja-значении.
 *
 * `member` пуст, когда каретка стоит на самой корневой переменной: у неё своя
 * цель — класс пользователя, а не атрибут.
 */
export interface UserHit {
  readonly root: string;
  readonly rootStart: number;
  readonly rootEnd: number;
  readonly member: string | undefined;
  readonly memberStart: number;
  readonly memberEnd: number;
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

/**
 * Строка, где приложение зарегистрировало ключевое слово в своём Python-коде.
 * Диапазон — имя без кавычек, в сырых координатах Python-файла.
 */
export interface RegistrationTarget extends Location {
  readonly target: "registration";
  readonly category: string;
  readonly name: string;
}

/**
 * Объявление имени модели пользователя в Python-коде приложения.
 *
 * `kind` различает два вида цели: атрибут (`userField`) и сам класс
 * пользователя (`userClass`). У класса — только переход: имени класса в тексте
 * DSL нет, там стоит псевдоним из параметризатора, поэтому вхождений у него не
 * бывает (план, раздел 9).
 */
export interface UserDeclarationTarget extends Location {
  readonly target: "userDeclaration";
  readonly kind: "userField" | "userClass";
  readonly name: string;
}

/** Определения, на которые ведёт позиция offset. */
export function definitionsAt(
  index: SmartAppIndex,
  context: DocumentContext,
  offset: number,
): (Definition | FieldDefinition | FileTarget | RegistrationTarget | UserDeclarationTarget)[] {
  if (!index.isReady() || context.kind === undefined) return [];

  const node = stringNodeAt(context.parsed.root, offset);
  if (node === undefined) return [];

  const value = node.value as string;
  if (isJinja(value)) {
    // Сама переменная `main_form` ведёт на определение целевой формы.
    const form = formVariableAt(node, context, offset);
    if (form !== undefined) return index.findDefinitions(form, [KIND.FORM], context.scopeRoot);

    // Проход по модели пользователя — отдельный, а не ветка форменного: тот
    // требует известной целевой формы, а у пользователя её нет вовсе, и в
    // `behaviors` ранний выход случился бы до нашей ветки (план, раздел 0).
    const user = userTargetsAt(index, context, node, offset);
    if (user.length > 0) return user;

    const hit = fieldHitAt(node, context, offset);
    if (hit === undefined) return [];
    return index.findFields(hit.form, hit.field, context.scopeRoot);
  }

  if (isFileReference(node)) {
    const uri = index.findFile(candidateUris(node, value, context.scopeRoot));
    return uri === undefined ? [] : [{ target: "file", uri, start: 0, end: 0 }];
  }

  // Значение `type`: слово может быть зарегистрировано самим приложением —
  // тогда переход ведёт на строку регистрации в его Python-коде.
  const registrations = registrationsAt(index, context, node);
  if (registrations.length > 0) return registrations;

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
    // Проход по модели пользователя — первый и отдельный, по той же причине,
    // что и в переходе: форменный требует известной формы (план, раздел 0).
    const user = userOccurrencesAt(index, context, node, offset, includeDeclaration);
    if (user !== undefined) return user;

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

  // Каретка на значении `type`, зарегистрированном приложением: вхождения
  // ищутся по всему приложению, а объявление — строка регистрации в Python.
  const registrations = registrationsAt(index, context, node);
  if (registrations.length > 0 && context.scopeRoot !== undefined) {
    const categories = new Set(registrations.map((registration) => registration.category));
    const usages = index.findKeywordUsages(value, categories, applicationRootOf(context.scopeRoot));
    return includeDeclaration ? dedupe([...usages, ...registrations]) : usages;
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
      result.push(...userDiagnostics(index, context, node));
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

/**
 * Регистрации приложения, на которые ведёт значение `type` под узлом [node].
 *
 * Категория позиции сужает выбор: слово одной категории не ведёт в регистрацию
 * другой. Нераспознанная категория целями не ограничивает — ровно как подсветка
 * в этом случае откатывается к словарю целиком.
 */
export function registrationsAt(
  index: SmartAppIndex,
  context: DocumentContext,
  node: Node,
): RegistrationTarget[] {
  if (context.scopeRoot === undefined || !isTypeValue(node)) return [];
  const value = node.value as string;
  if (value.length === 0) return [];

  const category = keywordCategoryAt(node, context.kind);
  const appRoot = applicationRootOf(context.scopeRoot);
  const targets: RegistrationTarget[] = [];
  for (const keyword of index.customKeywordsOf(context.scopeRoot)) {
    if (keyword.name !== value) continue;
    if (category !== undefined && keyword.category !== category) continue;
    const uri = index.registrationUri(appRoot, keyword.file);
    if (uri === undefined) continue;
    targets.push({
      target: "registration",
      uri,
      start: keyword.nameStart,
      end: keyword.nameEnd,
      category: keyword.category,
      name: keyword.name,
    });
  }
  return targets;
}

/** Узел стоит в позиции значения свойства `type`. */
function isTypeValue(node: Node): boolean {
  const property = propertyOf(node);
  if (property === undefined || propertyValue(property) !== node) return false;
  return propertyName(property) === typeContext.typeProperty;
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
 *
 * [custom] — слова, зарегистрированные приложением: словарь фреймворка играет
 * роль пола, приложение только добавляет (см. план, раздел 6).
 */
export function isKeywordInContext(
  value: string,
  category: string | undefined,
  custom: readonly CustomKeyword[] = [],
): boolean {
  if (custom.some((k) => k.name === value && (category === undefined || k.category === category))) {
    return true;
  }
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
 * Обращения к модели пользователя в значении [node].
 *
 * Корневых имён может быть несколько — все они псевдонимы одного объекта.
 * Целевой формы этот проход не требует: словарь параметров шаблона один и тот
 * же на любой рендер, поэтому `user.<name>` значим в любом виде DSL-файла.
 */
export function userHits(
  index: SmartAppIndex,
  context: DocumentContext,
  node: Node,
): UserHit[] {
  // Семантика — только у значений JSON: ключ `"{{ user.x }}"` ссылкой не
  // является (тот же контракт, что у полей формы).
  if (!isValueNode(node)) return [];
  // Без словаря корневое имя ничего не значит: обращаться не к чему.
  if (index.userModelOf(context.scopeRoot) === undefined) return [];
  const roots = index.userRootOf(context.scopeRoot)?.names ?? [];
  if (roots.length === 0) return [];
  const raw = rawText(context.text.slice(node.offset, node.offset + node.length));
  if (raw === undefined) return [];

  const decoded = decode(raw);
  const hits: UserHit[] = [];
  for (const access of rootAccesses(decoded.text)) {
    if (!roots.includes(access.root)) continue;
    const rootStart = decoded.decodedToRaw[access.rootStart];
    const rootEnd = decoded.decodedToRaw[access.rootEnd];
    if (rootStart === undefined || rootEnd === undefined) continue;
    const memberStart =
      access.memberStart === undefined ? undefined : decoded.decodedToRaw[access.memberStart];
    const memberEnd =
      access.memberEnd === undefined ? undefined : decoded.decodedToRaw[access.memberEnd];
    const hasMember = access.member !== undefined && memberStart !== undefined && memberEnd !== undefined;
    hits.push({
      root: access.root,
      // +1 — открывающая кавычка литерала.
      rootStart: node.offset + 1 + rootStart,
      rootEnd: node.offset + 1 + rootEnd,
      member: hasMember ? access.member : undefined,
      memberStart: node.offset + 1 + (memberStart ?? 0),
      memberEnd: node.offset + 1 + (memberEnd ?? 0),
    });
  }
  return hits;
}

/**
 * Цели перехода для позиции внутри обращения к модели пользователя.
 *
 * На имени атрибута — все его объявления в Python-коде приложения; на самой
 * корневой переменной — класс пользователя. У имени из снимка фреймворка
 * объявлений нет, и переходить некуда: это не отказ, а «в коде проекта такой
 * строки не существует».
 */
function userTargetsAt(
  index: SmartAppIndex,
  context: DocumentContext,
  node: Node,
  offset: number,
): UserDeclarationTarget[] {
  const model = index.userModelOf(context.scopeRoot);
  if (model === undefined || context.scopeRoot === undefined) return [];
  const appRoot = applicationRootOf(context.scopeRoot);

  for (const hit of userHits(index, context, node)) {
    if (hit.member !== undefined && containsCaret(hit.memberStart, hit.memberEnd, offset)) {
      const attribute = model.attributes.get(hit.member);
      if (attribute === undefined) return [];
      return attribute.declarations.flatMap((site) => {
        const uri = index.registrationUri(appRoot, site.file);
        return uri === undefined
          ? []
          : [
              {
                target: "userDeclaration" as const,
                kind: "userField" as const,
                uri,
                start: site.nameStart,
                end: site.nameEnd,
                name: hit.member as string,
              },
            ];
      });
    }
    if (containsCaret(hit.rootStart, hit.rootEnd, offset)) {
      const cls = model.userClass;
      if (cls === undefined) return [];
      const uri = index.registrationUri(appRoot, cls.file);
      return uri === undefined
        ? []
        : [
            {
              target: "userDeclaration",
              kind: "userClass",
              uri,
              start: cls.nameStart,
              end: cls.nameEnd,
              name: cls.name,
            },
          ];
    }
  }
  return [];
}

/**
 * Вхождения атрибута модели пользователя, если каретка стоит на его имени.
 *
 * `undefined` — каретка не на атрибуте: вызывающий продолжает своими ветками.
 * Пустой список — атрибут есть, а вхождений нет, и это другой ответ.
 *
 * Вхождением считается `<корень>.<имя>` в значении DSL-файла того же
 * приложения — под **любым** действующим корнем: все они псевдонимы одного
 * объекта. С [includeDeclaration] добавляются строки объявления в Python; у
 * имени из снимка фреймворка их нет, и список не меняется. Строка `class <Name>`
 * в него не входит: имени класса в тексте DSL нет вовсе (план, раздел 9).
 */
function userOccurrencesAt(
  index: SmartAppIndex,
  context: DocumentContext,
  node: Node,
  offset: number,
  includeDeclaration: boolean,
): Location[] | undefined {
  if (context.scopeRoot === undefined) return undefined;
  const model = index.userModelOf(context.scopeRoot);
  const roots = index.userRootOf(context.scopeRoot)?.names ?? [];
  if (model === undefined) return undefined;

  const hit = userHits(index, context, node).find(
    (candidate) =>
      candidate.member !== undefined &&
      containsCaret(candidate.memberStart, candidate.memberEnd, offset),
  );
  const name = hit?.member;
  if (name === undefined) return undefined;

  const appRoot = applicationRootOf(context.scopeRoot);
  const usages = index.findUserFieldUsages(name, roots, appRoot);
  if (!includeDeclaration) return usages;

  const declarations = (model.attributes.get(name)?.declarations ?? []).flatMap((site) => {
    const uri = index.registrationUri(appRoot, site.file);
    return uri === undefined ? [] : [{ uri, start: site.nameStart, end: site.nameEnd }];
  });
  return dedupe([...declarations, ...usages]);
}

/**
 * WARNING на имени, которого нет в словаре модели пользователя.
 *
 * Утверждать можно только при двух условиях сразу: словарь полон
 * (`diagnosticsSafe`, раздел 5) и корневое имя доказано разбором
 * параметризатора (раздел 3). Без второго под именем `user` может лежать что
 * угодно, и подчёркивать по нему — выдумывать ошибку.
 */
function userDiagnostics(
  index: SmartAppIndex,
  context: DocumentContext,
  node: Node,
): Diagnostic[] {
  const model = index.userModelOf(context.scopeRoot);
  const root = index.userRootOf(context.scopeRoot);
  if (model === undefined || root === undefined) return [];
  if (!model.diagnosticsSafe || root.state !== "proven") return [];

  const result: Diagnostic[] = [];
  for (const hit of userHits(index, context, node)) {
    if (hit.member === undefined) continue;
    if (model.attributes.has(hit.member)) continue;
    result.push({
      start: hit.memberStart,
      end: hit.memberEnd,
      message: `Не удаётся разрешить поле '${hit.member}' модели пользователя`,
    });
  }
  return result;
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
