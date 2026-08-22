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
import {
  applicationRootOf,
  kindOf,
  ownerApplicationRoot,
  pathSegments,
  referencesRoot,
} from "./files";
import { customKeywords, hasExcludedSegment, type CustomKeyword } from "./resourceKeywords";
import { resourceScan } from "./contract";
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

/** Путь URI без схемы — те же координаты, в которых работает `referencesRoot`. */
const pathKey = (uri: string): string => pathSegments(uri).join("/");

export class SmartAppIndex {
  private readonly files = new Map<string, FileEntry>();
  /**
   * Файлы набора `references`, не являющиеся DSL-файлами: шаблоны Jinja и
   * прочее содержимое, на которое ссылаются по имени. Ключ — путь без схемы
   * URI, в тех же координатах, что отдаёт `referencesRoot`, чтобы кандидата
   * можно было собрать конкатенацией. Текст таких файлов не читается: нужен
   * только факт существования.
   */
  private readonly assets = new Map<string, string>();

  /**
   * Тексты Python-файлов приложений: словарь ключевых слов — свойство навыка,
   * и собрать его можно только прочитав цепочку от `RESOURCES`.
   */
  private readonly pythonTexts = new Map<string, string>();

  /**
   * Разобранный словарь по корням приложений. Сбрасывается целиком при любом
   * изменении Python: правка базового класса меняет словарь производного, и
   * обновить «только изменившийся файл» было бы неверно.
   */
  private readonly customCache = new Map<string, readonly CustomKeyword[]>();
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
    this.assets.clear();
    this.pythonTexts.clear();
    this.customCache.clear();
    this.ready = false;
  }

  /** Добавляет или заменяет текст Python-файла приложения. */
  upsertPython(uri: string, text: string): void {
    this.pythonTexts.set(pathKey(uri), text);
    this.customCache.clear();
  }

  removePython(uri: string): void {
    if (this.pythonTexts.delete(pathKey(uri))) this.customCache.clear();
  }

  /** Файл относится к ресурсам приложения (не DSL, не шаблон)? */
  static isPythonFile(uri: string): boolean {
    const path = pathSegments(uri).join("/");
    return path.endsWith(resourceScan.fileExtension) && !hasExcludedSegment(path);
  }

  /** Корни приложений, известные индексу (по найденным наборам references). */
  applicationRoots(): Set<string> {
    const roots = new Set<string>();
    for (const entry of this.files.values()) {
      if (entry.scopeRoot !== undefined) roots.add(applicationRootOf(entry.scopeRoot));
    }
    return roots;
  }

  /**
   * Ключевые слова, зарегистрированные приложением набора [scopeRoot].
   *
   * Читаются только файлы, которыми владеет это же приложение: вложенный
   * `subapp` — отдельное приложение, и его ресурсы в словарь внешнего не идут.
   */
  customKeywordsOf(scopeRoot: string | undefined): readonly CustomKeyword[] {
    if (scopeRoot === undefined) return [];
    const appRoot = applicationRootOf(scopeRoot);
    const cached = this.customCache.get(appRoot);
    if (cached !== undefined) return cached;

    const roots = this.applicationRoots();
    const resolved = customKeywords((relative) => {
      const path = appRoot.length === 0 ? relative : `${appRoot}/${relative}`;
      if (ownerApplicationRoot(path, roots) !== appRoot) return undefined;
      return this.pythonTexts.get(path);
    });
    this.customCache.set(appRoot, resolved);
    return resolved;
  }

  /**
   * Регистрирует не-DSL файл набора `references` (шаблон и т.п.). Вызывается
   * адаптером напрямую, чтобы не читать содержимое такого файла.
   */
  noteFile(uri: string): void {
    this.assets.set(pathKey(uri), uri);
  }

  /**
   * Пути файлов внутри `<referencesRoot>/<dir>` для каждого dir из [dirs],
   * относительно самого dir: объединение каталогов, одинаковый путь — один
   * вариант.
   *
   * Список каталогов приходит параметром, а не берётся из правила: так
   * многокаталожный случай проверяется тестом, не подделывая данные контракта.
   * Приоритета у результата нет — какой из одноимённых файлов откроется, решает
   * резолв ([findFile]); здесь важно лишь, что каждый путь резолвится.
   */
  filesInDirs(referencesRoot: string, dirs: readonly string[]): string[] {
    const paths: string[] = [];
    const seen = new Set<string>();
    for (const dir of dirs) {
      const prefix = `${referencesRoot}/${dir}/`;
      for (const key of this.assets.keys()) {
        if (!key.startsWith(prefix)) continue;
        const relative = key.slice(prefix.length);
        if (relative.length === 0 || seen.has(relative)) continue;
        seen.add(relative);
        paths.push(relative);
      }
    }
    return paths;
  }

  /** URI первого существующего файла из кандидатов, либо `undefined`. */
  findFile(candidates: readonly string[]): string | undefined {
    for (const candidate of candidates) {
      const uri = this.assets.get(candidate);
      if (uri !== undefined) return uri;
    }
    return undefined;
  }

  /** Добавляет или заменяет содержимое файла в индексе. */
  upsert(uri: string, text: string): void {
    this.remove(uri);
    if (SmartAppIndex.isPythonFile(uri)) {
      this.upsertPython(uri, text);
      return;
    }
    const kind = kindOf(uri);
    if (kind === undefined) {
      // Не DSL-файл внутри набора references (шаблон Jinja и т.п.): содержимое
      // не разбирается, важен только факт существования — по нему резолвятся
      // файловые ссылки.
      if (referencesRoot(uri) !== undefined) this.noteFile(uri);
      return;
    }

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
    if (this.files.delete(uri)) this.customCache.clear();
    this.assets.delete(pathKey(uri));
    this.removePython(uri);
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
