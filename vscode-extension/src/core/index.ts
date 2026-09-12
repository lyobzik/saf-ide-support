import { KIND, fieldAccess, type RefKind } from "./contract";
import {
  forEachStringNode,
  isValueNode,
  parseDocument,
  propertyName,
  propertyOf,
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
import {
  customKeywords,
  hasExcludedDirBelow,
  hasExcludedSegment,
  type AppFiles,
  type CustomKeyword,
} from "./resourceKeywords";
import { userModelOf, type UserModelInfo } from "./userModel";
import { userRootOf, type UserRoot } from "./userRoot";
import { resourceScan, typeContext } from "./contract";
import { categoryFor } from "./typeContext";
import { indexEligibility } from "./indexGate";
import { isJinja } from "./jinja";
import { decode, rawText } from "./jsonDecode";
import { fieldCandidates, rootAccesses } from "./jinjaLexer";
import { targetFormOf } from "./fieldRef";
import { targetKinds } from "./refRules";

/** Разобранный Python приложения: слова, модель пользователя и её корень. */
interface AppPython {
  readonly keywords: readonly CustomKeyword[];
  readonly model: UserModelInfo | undefined;
  readonly root: UserRoot;
}

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

/**
 * Обращение `<root>.<member>` в выражении Jinja — **любое**, а не только к
 * действующему корню.
 *
 * Корневые имена задаёт Python приложения и меняет правка параметризатора, а
 * индекс JSON от неё не зависит: отбор по действующим корням делается на
 * запросе. Тот же приём, что у позиций ключевых слов.
 */
export interface RootAccessUsage extends Location {
  readonly root: string;
  readonly member: string;
}

/**
 * Значение `type` — позиция ключевого слова. Хранится вместе с категорией
 * позиции: одноимённые слова разных категорий — разные слова, а
 * `undefined` (контекст не распознан) совпадает с любой категорией, ровно как
 * при резолве и подсветке.
 */
export interface KeywordUsage extends Location {
  readonly name: string;
  readonly category: string | undefined;
}

/** Python-файл приложения: URI нужен для позиции регистрации, текст — сканеру. */
interface PythonFile {
  readonly uri: string;
  readonly text: string;
}

interface FileEntry {
  readonly uri: string;
  readonly scopeRoot: string | undefined;
  readonly kind: RefKind;
  readonly definitions: Definition[];
  readonly fields: FieldDefinition[];
  readonly usages: Usage[];
  readonly fieldUsages: FieldUsage[];
  readonly keywordUsages: KeywordUsage[];
  readonly rootAccesses: RootAccessUsage[];
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
  private readonly pythonTexts = new Map<string, PythonFile>();

  /**
   * Разобранный Python по корням приложений. Сбрасывается целиком при любом
   * изменении Python: правка базового класса меняет и словарь производного, и
   * его модель пользователя, — обновить «только изменившийся файл» было бы
   * неверно.
   *
   * Три результата лежат в одной записи не для экономии: у них общий источник и
   * общая инвалидация, а раздельные кэши пришлось бы сбрасывать в семи местах и
   * рано или поздно разойтись.
   */
  private readonly appCache = new Map<string, AppPython>();

  /**
   * Снимок множества корней приложений. Появление нового корня (в том числе
   * вложенного) меняет владельца Python-файлов, поэтому пересчитанный словарь
   * соседей тоже становится неверным — кэш сбрасывается целиком.
   */
  private rootsSignature: string | undefined;

  /**
   * Число идущих сканирований Python. Пока оно не ноль, словарь приложения
   * заведомо неполон: часть модулей цепочки ещё не прочитана. Отдавать такой
   * словарь (и тем более кэшировать) нельзя — пользователь увидел бы слова,
   * которые через секунду поменяются.
   */
  private pythonScans = 0;
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

  /** Отмечает начало сканирования Python: словарь приложения на это время пуст. */
  beginPythonScan(): void {
    this.pythonScans++;
  }

  endPythonScan(): void {
    this.pythonScans = Math.max(0, this.pythonScans - 1);
    this.appCache.clear();
  }

  clear(): void {
    this.files.clear();
    this.assets.clear();
    this.pythonTexts.clear();
    this.appCache.clear();
    this.rootsSignature = undefined;
    this.pythonScans = 0;
    this.ready = false;
  }

  /** Добавляет или заменяет текст Python-файла приложения. */
  upsertPython(uri: string, text: string): void {
    this.pythonTexts.set(pathKey(uri), { uri, text });
    this.appCache.clear();
  }

  removePython(uri: string): void {
    if (this.pythonTexts.delete(pathKey(uri))) this.appCache.clear();
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
    const signature = [...roots].sort().join("\n");
    if (signature !== this.rootsSignature) {
      this.rootsSignature = signature;
      this.appCache.clear();
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
    return this.appPythonOf(scopeRoot)?.keywords ?? [];
  }

  /**
   * Словарь модели пользователя приложения набора [scopeRoot].
   * `undefined` — словаря нет либо индекс ещё не готов.
   */
  userModelOf(scopeRoot: string | undefined): UserModelInfo | undefined {
    return this.appPythonOf(scopeRoot)?.model;
  }

  /**
   * Корневое имя (имена) модели пользователя. `undefined` — индекс не готов;
   * отсутствие доказательства отказом не является и живёт внутри [UserRoot].
   */
  userRootOf(scopeRoot: string | undefined): UserRoot | undefined {
    return this.appPythonOf(scopeRoot)?.root;
  }

  /**
   * Ключевые слова приложения с корнем [appRoot]. Нужен запросам, у которых
   * набора `references` под рукой нет: каретка стоит в Python-файле, и корень
   * приложения известен только по его пути (`ownerApplicationRoot`).
   */
  customKeywordsOfApplication(appRoot: string): readonly CustomKeyword[] {
    return this.appPythonOfRoot(appRoot)?.keywords ?? [];
  }

  /**
   * Разобранный Python приложения набора [scopeRoot].
   *
   * Читаются только файлы, которыми владеет это же приложение: вложенный
   * `subapp` — отдельное приложение, и его ресурсы в словарь внешнего не идут.
   */
  private appPythonOf(scopeRoot: string | undefined): AppPython | undefined {
    if (scopeRoot === undefined) return undefined;
    return this.appPythonOfRoot(applicationRootOf(scopeRoot));
  }

  /** Разобранный Python приложения с корнем [appRoot]. */
  private appPythonOfRoot(appRoot: string): AppPython | undefined {
    // До готовности и во время сканирования разбор неполон: молчим, как и
    // остальные запросы, зависящие от индекса.
    if (!this.ready || this.pythonScans > 0) return undefined;
    // Корни считаются первыми: их изменение сбрасывает кэш, и только после
    // этого можно смотреть в него.
    const roots = this.applicationRoots();
    const cached = this.appCache.get(appRoot);
    if (cached !== undefined) return cached;

    const files = this.appFilesOf(appRoot, roots);
    const resolved: AppPython = {
      keywords: customKeywords(files),
      model: userModelOf(files),
      root: userRootOf(files),
    };
    this.appCache.set(appRoot, resolved);
    return resolved;
  }

  /** Доступ к Python-файлам приложения [appRoot] — с правилом владения. */
  private appFilesOf(appRoot: string, roots: Set<string>): AppFiles {
    const absolute = (relative: string): string =>
      appRoot.length === 0 ? relative : `${appRoot}/${relative}`;
    const owned = (path: string): boolean => ownerApplicationRoot(path, roots) === appRoot;

    return {
      read: (relative) => {
        const path = absolute(relative);
        return owned(path) ? this.pythonTexts.get(path)?.text : undefined;
      },
      // Существование модуля или пакета видно по известным индексу файлам:
      // отдельного обхода файловой системы у ядра нет и быть не должно.
      exists: (relative) => {
        const base = absolute(relative);
        if (this.pythonTexts.has(`${base}${resourceScan.fileExtension}`)) {
          return owned(`${base}${resourceScan.fileExtension}`);
        }
        const prefix = `${base}/`;
        // Нужен хотя бы один свой файл: у вложенного приложения файлы с тем же
        // префиксом чужие, и остановка на первом совпадении дала бы неверный
        // ответ в зависимости от порядка обхода.
        for (const path of this.pythonTexts.keys()) {
          if (path.startsWith(prefix) && owned(path)) return true;
        }
        return false;
      },
    };
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
      rootAccesses: [],
      keywordUsages: [],
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
    if (this.files.delete(uri)) this.appCache.clear();
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

  /**
   * URI Python-файла [relative] (путь относительно корня приложения [appRoot]),
   * если такой файл известен индексу. Нужен позиции регистрации: сама запись
   * словаря знает только путь внутри приложения.
   */
  registrationUri(appRoot: string, relative: string): string | undefined {
    const path = appRoot.length === 0 ? relative : `${appRoot}/${relative}`;
    return this.pythonTexts.get(path)?.uri;
  }

  /**
   * Вхождения ключевого слова [name] в DSL-файлах приложения [appRoot].
   *
   * Область — приложение целиком, а не набор `references`: слово регистрируется
   * в его Python-коде и живёт во всех его файлах. Вложенное приложение —
   * чужое (у него другой корень), каталоги-зависимости исключаются: набор
   * внутри `venv` выглядит настоящим, но принадлежит библиотеке.
   */
  findKeywordUsages(
    name: string,
    categories: ReadonlySet<string>,
    appRoot: string,
  ): Location[] {
    const result: Location[] = [];
    for (const entry of this.files.values()) {
      if (entry.scopeRoot === undefined) continue;
      if (applicationRootOf(entry.scopeRoot) !== appRoot) continue;
      if (hasExcludedDirBelow(pathKey(entry.uri), appRoot)) continue;
      for (const usage of entry.keywordUsages) {
        if (usage.name !== name) continue;
        if (usage.category !== undefined && !categories.has(usage.category)) continue;
        result.push({ uri: usage.uri, start: usage.start, end: usage.end });
      }
    }
    return result;
  }

  /**
   * Вхождения атрибута [name] модели пользователя в DSL-файлах приложения.
   *
   * Корень — любое из действующих имён [roots]: все они псевдонимы одного
   * объекта, и вхождением считается обращение под любым из них.
   */
  findUserFieldUsages(name: string, roots: readonly string[], appRoot: string): Location[] {
    const result: Location[] = [];
    for (const entry of this.files.values()) {
      if (entry.scopeRoot === undefined) continue;
      if (applicationRootOf(entry.scopeRoot) !== appRoot) continue;
      if (hasExcludedDirBelow(pathKey(entry.uri), appRoot)) continue;
      for (const access of entry.rootAccesses) {
        if (access.member !== name || !roots.includes(access.root)) continue;
        result.push({ uri: access.uri, start: access.start, end: access.end });
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
      collectRootAccesses(node, parsed.text, uri, entry);
      return;
    }
    if (value.length === 0) return;
    // Диапазон — имя без кавычек: в IDEA ссылка живёт в `rangeInElement`
    // литерала, и именно её подсвечивает Find Usages. Диагностика, наоборот,
    // покрывает литерал целиком и считается отдельно.
    const start = node.offset + 1;
    const end = Math.max(start, node.offset + node.length - 1);

    const kinds = targetKinds(node, kind);
    if (kinds.length > 0) {
      entry.usages.push({ uri, start, end, kinds, name: value });
      return;
    }

    // Значение `type`: слово может быть зарегистрировано самим приложением, и
    // тогда эта позиция — его использование. Позиции собираются всегда, а не
    // только для известных слов: словарь приложения меняется правкой Python, а
    // индекс JSON от неё не зависит.
    const property = propertyOf(node);
    if (property === undefined || propertyValue(property) !== node) return;
    if (propertyName(property) !== typeContext.typeProperty) return;
    entry.keywordUsages.push({ uri, start, end, name: value, category: categoryFor(property, kind) });
  });
}

/**
 * Обращения к корневым переменным в значении. Целевая форма здесь ни при чём:
 * какие корни действуют, решает Python приложения, и знать это на индексации
 * файла не нужно.
 */
function collectRootAccesses(
  node: Node,
  documentText: string,
  uri: string,
  entry: FileEntry,
): void {
  const raw = rawText(documentText.slice(node.offset, node.offset + node.length));
  if (raw === undefined) return;
  const decoded = decode(raw);
  for (const access of rootAccesses(decoded.text)) {
    if (access.member === undefined) continue;
    const start = decoded.decodedToRaw[access.memberStart as number];
    const end = decoded.decodedToRaw[access.memberEnd as number];
    if (start === undefined || end === undefined) continue;
    entry.rootAccesses.push({
      uri,
      // +1 — открывающая кавычка литерала.
      start: node.offset + 1 + start,
      end: node.offset + 1 + end,
      root: access.root,
      member: access.member,
    });
  }
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
