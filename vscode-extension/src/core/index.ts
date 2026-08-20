import { KIND, fieldAccess, type RefKind } from "./contract";
import {
  forEachStringNode,
  isValueNode,
  parseDocument,
  propertyName,
  propertyValue,
  topLevelProperties,
  type Node,
  type ParsedDocument,
} from "./ast";
import { kindOf, referencesRoot } from "./files";
import { indexEligibility } from "./indexGate";
import { isJinja } from "./jinja";
import { decode, rawText } from "./jsonDecode";
import { fieldCandidates } from "./jinjaLexer";
import { targetFormOf } from "./fieldRef";
import { targetKinds } from "./refRules";

/**
 * Воркспейс-индекс SmartApp DSL — замена четырёх `FileBasedIndex` плагина IDEA.
 *
 * Ядро не знает ни о `vscode`, ни о файловой системе: адаптер сам находит файлы
 * и передаёт сюда снимки текста через [upsert]/[remove]. Благодаря этому весь
 * индекс и вся семантика проверяются conformance-тестами без запуска редактора.
 */

/** Позиция в документе; смещения — в символах, перевод в Position делает адаптер. */
export interface Location {
  readonly uri: string;
  readonly start: number;
  readonly end: number;
}

/**
 * Определение сущности. [ordinal] — номер среди одноимённых top-level ключей
 * файла: пары «файл + имя» недостаточно, дубликат ключа обязан давать два
 * различимых определения.
 */
export interface Definition extends Location {
  readonly kind: RefKind;
  readonly name: string;
  readonly ordinal: number;
}

/** Определение поля формы (`forms.<form>.fields.<field>`). */
export interface FieldDefinition extends Location {
  readonly form: string;
  readonly field: string;
  readonly ordinal: number;
}

/** Использование — ссылочная позиция, найденная в файле. */
export interface Usage extends Location {
  /** Виды, на которые может ссылаться значение (для кросс-ссылок). */
  readonly kinds: readonly RefKind[];
  readonly name: string;
}

/** Использование поля формы внутри Jinja-интерполяции. */
export interface FieldUsage extends Location {
  readonly form: string;
  readonly field: string;
}

interface FileEntry {
  readonly uri: string;
  readonly scopeRoot: string | undefined;
  readonly kind: RefKind;
  readonly definitions: Definition[];
  readonly fields: FieldDefinition[];
  readonly usages: Usage[];
  readonly fieldUsages: FieldUsage[];
}

const inScope = (entry: FileEntry, scopeRoot: string | undefined): boolean =>
  scopeRoot === undefined || entry.scopeRoot === scopeRoot;

export class SmartAppIndex {
  private readonly files = new Map<string, FileEntry>();
  private ready = false;

  /**
   * Индекс считается готовым только после первичного сканирования воркспейса.
   * До этого резолв и диагностика обязаны молчать — аналог dumb mode в IDEA,
   * иначе пользователь увидит волну ложных «не удаётся разрешить».
   */
  isReady(): boolean {
    return this.ready;
  }

  markReady(): void {
    this.ready = true;
  }

  clear(): void {
    this.files.clear();
    this.ready = false;
  }

  /** Добавляет или заменяет содержимое файла в индексе. */
  upsert(uri: string, text: string): void {
    this.remove(uri);
    const kind = kindOf(uri);
    if (kind === undefined) return;

    const parsed = parseDocument(text);
    const entry: FileEntry = {
      uri,
      scopeRoot: referencesRoot(uri),
      kind,
      definitions: [],
      fields: [],
      usages: [],
      fieldUsages: [],
    };

    // Определения — под гейтом строгости: битый файл не должен «подарить»
    // обрывочный ключ. Ссылки и Jinja-использования собираются всегда: в IDEA
    // они живут на частичном PSI и работают даже в недописанном файле.
    if (indexEligibility(uri, parsed).eligible) {
      collectDefinitions(parsed, kind, uri, entry);
    }
    collectUsages(parsed, kind, uri, entry);

    this.files.set(uri, entry);
  }

  remove(uri: string): void {
    this.files.delete(uri);
  }

  /** Все определения [name] среди [kinds]. */
  findDefinitions(
    name: string,
    kinds: readonly RefKind[],
    scopeRoot: string | undefined,
  ): Definition[] {
    const result: Definition[] = [];
    for (const entry of this.files.values()) {
      if (!inScope(entry, scopeRoot)) continue;
      if (!kinds.includes(entry.kind)) continue;
      for (const definition of entry.definitions) {
        if (definition.name === name) result.push(definition);
      }
    }
    return result;
  }

  /** Все определения поля [field] формы [form]. */
  findFields(form: string, field: string, scopeRoot: string | undefined): FieldDefinition[] {
    const result: FieldDefinition[] = [];
    for (const entry of this.files.values()) {
      if (!inScope(entry, scopeRoot)) continue;
      for (const definition of entry.fields) {
        if (definition.form === form && definition.field === field) result.push(definition);
      }
    }
    return result;
  }

  /** Имена определений вида [kind] — для автодополнения. */
  namesOfKind(kind: RefKind, scopeRoot: string | undefined): string[] {
    const names: string[] = [];
    const seen = new Set<string>();
    for (const entry of this.files.values()) {
      if (!inScope(entry, scopeRoot) || entry.kind !== kind) continue;
      for (const definition of entry.definitions) {
        if (seen.has(definition.name)) continue;
        seen.add(definition.name);
        names.push(definition.name);
      }
    }
    return names;
  }

  /** Имена полей формы [form] — для автодополнения внутри Jinja. */
  fieldsOfForm(form: string, scopeRoot: string | undefined): string[] {
    const names: string[] = [];
    const seen = new Set<string>();
    for (const entry of this.files.values()) {
      if (!inScope(entry, scopeRoot)) continue;
      for (const definition of entry.fields) {
        if (definition.form !== form || seen.has(definition.field)) continue;
        seen.add(definition.field);
        names.push(definition.field);
      }
    }
    return names;
  }

  /**
   * Использования определения [name] вида [kind] — обратный индекс, роль
   * которого в IDEA играет Find Usages поверх PSI-ссылок.
   */
  findUsages(name: string, kind: RefKind, scopeRoot: string | undefined): Usage[] {
    const result: Usage[] = [];
    for (const entry of this.files.values()) {
      if (!inScope(entry, scopeRoot)) continue;
      for (const usage of entry.usages) {
        if (usage.name === name && usage.kinds.includes(kind)) result.push(usage);
      }
    }
    return result;
  }

  /** Использования поля [field] формы [form]. */
  findFieldUsages(form: string, field: string, scopeRoot: string | undefined): FieldUsage[] {
    const result: FieldUsage[] = [];
    for (const entry of this.files.values()) {
      if (!inScope(entry, scopeRoot)) continue;
      for (const usage of entry.fieldUsages) {
        if (usage.form === form && usage.field === field) result.push(usage);
      }
    }
    return result;
  }

  /** Все проиндексированные DSL-файлы — для отладки и тестов. */
  indexedUris(): string[] {
    return [...this.files.keys()];
  }
}

function collectDefinitions(
  parsed: ParsedDocument,
  kind: RefKind,
  uri: string,
  entry: FileEntry,
): void {
  const ordinals = new Map<string, number>();
  for (const property of topLevelProperties(parsed.root)) {
    const name = propertyName(property);
    if (name === undefined) continue;
    const key = property.children?.[0];
    if (key === undefined) continue;

    const ordinal = ordinals.get(name) ?? 0;
    ordinals.set(name, ordinal + 1);
    entry.definitions.push({
      uri,
      start: key.offset,
      end: key.offset + key.length,
      kind,
      name,
      ordinal,
    });

    if (kind === KIND.FORM) collectFormFields(property, name, uri, entry);
  }
}

/** Поля формы: `forms.<form>.fields.<field>` (дубликаты сохраняются). */
function collectFormFields(
  formProperty: Node,
  formName: string,
  uri: string,
  entry: FileEntry,
): void {
  const formObject = propertyValue(formProperty);
  if (formObject?.type !== "object") return;

  for (const property of formObject.children ?? []) {
    if (property.type !== "property") continue;
    if (propertyName(property) !== fieldAccess.fieldsProperty) continue;
    const fieldsObject = propertyValue(property);
    if (fieldsObject?.type !== "object") continue;

    const ordinals = new Map<string, number>();
    for (const field of fieldsObject.children ?? []) {
      if (field.type !== "property") continue;
      const fieldName = propertyName(field);
      const key = field.children?.[0];
      if (fieldName === undefined || key === undefined) continue;

      const ordinal = ordinals.get(fieldName) ?? 0;
      ordinals.set(fieldName, ordinal + 1);
      entry.fields.push({
        uri,
        start: key.offset,
        end: key.offset + key.length,
        form: formName,
        field: fieldName,
        ordinal,
      });
    }
  }
}

/**
 * Ссылочные позиции файла: кросс-ссылки и обращения к полям формы внутри
 * Jinja-интерполяций. Собираются независимо от гейта строгости.
 */
function collectUsages(parsed: ParsedDocument, kind: RefKind, uri: string, entry: FileEntry): void {
  forEachStringNode(parsed.root, (node) => {
    const value = node.value as string;
    if (typeof value !== "string") return;

    // Семантика — только у значений JSON: ключ "{{ main_form.name }}" ссылкой не
    // является, иначе переименование поля переписало бы ключ объекта.
    if (!isValueNode(node)) return;

    if (isJinja(value)) {
      collectFieldUsages(node, parsed.text, kind, uri, entry);
      return;
    }
    const kinds = targetKinds(node, kind);
    if (kinds.length === 0 || value.length === 0) return;
    // Диапазон — имя без кавычек: в IDEA ссылка живёт в `rangeInElement`
    // литерала, и именно её подсвечивает Find Usages. Диагностика, наоборот,
    // покрывает литерал целиком и считается отдельно.
    const start = node.offset + 1;
    entry.usages.push({
      uri,
      start,
      end: Math.max(start, node.offset + node.length - 1),
      kinds,
      name: value,
    });
  });
}

function collectFieldUsages(
  node: Node,
  documentText: string,
  kind: RefKind,
  uri: string,
  entry: FileEntry,
): void {
  const raw = rawText(documentText.slice(node.offset, node.offset + node.length));
  if (raw === undefined) return;
  const form = targetFormOf(node, kind);
  if (form === undefined) return;

  const decoded = decode(raw);
  for (const candidate of fieldCandidates(decoded.text)) {
    const rawStart = decoded.decodedToRaw[candidate.fieldStart];
    const rawEnd = decoded.decodedToRaw[candidate.fieldEnd];
    if (rawStart === undefined || rawEnd === undefined) continue;
    entry.fieldUsages.push({
      uri,
      // +1 — открывающая кавычка литерала.
      start: node.offset + 1 + rawStart,
      end: node.offset + 1 + rawEnd,
      form,
      field: candidate.field,
    });
  }
}
