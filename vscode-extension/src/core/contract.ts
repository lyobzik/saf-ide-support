import rulesJson from "../../../shared/rules/rules.json";
import keywordsJson from "../../../shared/keywords/keywords.json";

/**
 * Единственная точка доступа к общему контракту данных SmartApp DSL.
 *
 * Контракт — сгенерированный снимок Kotlin-таблиц плагина IDEA
 * (`shared/rules/rules.json`) и словарь ключевых слов
 * (`shared/keywords/keywords.json`). Ни один другой модуль ядра не импортирует
 * эти файлы: DSL-имена (виды, каталоги, сегменты пути, ключи, `main_form`)
 * приходят отсюда, а в коде остаётся только грамматика и алгоритмы.
 *
 * Проверки здесь fail-closed: несовпадение версии или отсутствие обязательной
 * секции — исключение при загрузке, а не молча пустая семантика. Полная
 * валидация по JSON Schema выполняется на сборке (`npm run verify:contract`).
 */

/** Версия контракта, с которой умеет работать расширение. */
export const EXPECTED_CONTRACT_VERSION = 5;

/** Имя вида сущности, как оно записано в контракте. */
export type RefKind = string;

export interface KindSpec {
  readonly kind: RefKind;
  readonly dirName: string;
}

export interface RefRule {
  readonly property: string;
  /** `null` — значение `type` у владельца не важно. */
  readonly ownerTypes: readonly string[] | null;
  /** `null` — правило действует в файлах любого вида. */
  readonly fileKind: RefKind | null;
  readonly targets: readonly RefKind[];
}

/**
 * Правило ссылки на файл: значение свойства называет файл в одном из каталогов
 * набора `references`. Отдельный вид правила, а не [RefRule]: цель — файл
 * целиком, найденный по пути, а не top-level ключ из индекса.
 */
export interface FileRefRule {
  readonly property: string;
  /** `null` — значение `type` у владельца не важно. */
  readonly ownerTypes: readonly string[] | null;
  /** Каталоги набора `references`, просматриваются по порядку. */
  readonly searchDirs: readonly string[];
}

/**
 * Правила чтения ресурсов приложения: словарь ключевых слов — свойство навыка,
 * а не фреймворка. Активный класс ресурсов назначает `app_config.py`.
 */
export interface ResourceScanSpec {
  /** Файл в корне приложения, назначающий активный класс ресурсов. */
  readonly configFile: string;
  /** Переменная в [configFile], хранящая активный класс. */
  readonly resourcesVariable: string;
  /** Префикс методов класса ресурсов, в которых происходит регистрация. */
  readonly methodPrefix: string;
  readonly fileExtension: string;
  /** `a.b.c` разрешается в `a/b/c.py`, иначе в `a/b/c/<packageInitFile>`. */
  readonly packageInitFile: string;
  /** Предел длины цепочки наследования — страховка от циклического импорта. */
  readonly maxBaseDepth: number;
  /** Сегменты пути, внутрь которых сканер не заходит. */
  readonly excludedDirs: ReadonlySet<string>;
}

export interface PathSpec {
  readonly rootSegments: readonly string[];
  readonly fileExtension: string;
  readonly extensionIgnoreCase: boolean;
}

export interface TypeContextSpec {
  /** Свойство, чьё значение — ключевое слово DSL и тип объекта-владельца. */
  readonly typeProperty: string;
  readonly actionKeys: ReadonlySet<string>;
  /** Категория для любого ключа из actionKeys. */
  readonly actionCategory: string;
  readonly contextKeys: ReadonlySet<string>;
  /** Структурный ключ -> категория ключевых слов для его `type`. */
  readonly keyCategories: ReadonlyMap<string, string>;
  /** Переопределение категории для ключей внутри описания поля. */
  readonly insideFieldsCategories: ReadonlyMap<string, string>;
  readonly fileKindCategory: ReadonlyMap<RefKind, string>;
}

export interface FieldAccessSpec {
  readonly formProperty: string;
  readonly fieldsProperty: string;
}

export interface JinjaSpec {
  readonly formVariable: string;
}

/**
 * Виды, на которые ядро ссылается по смыслу, а не только как на данные:
 * сценарий владеет свойством `form`, форма содержит `fields`. Если контракт
 * перестанет их объявлять, загрузка упадёт — это ошибка контракта, а не повод
 * работать вполсилы.
 */
const REQUIRED_KINDS = ["SCENARIO", "FORM"] as const;

const fail = (message: string): never => {
  throw new Error(`SmartApp DSL contract is unusable: ${message}`);
};

const meta = rulesJson._meta;
if (meta?.contractVersion !== EXPECTED_CONTRACT_VERSION) {
  fail(
    `version mismatch — snapshot declares ${String(meta?.contractVersion)}, ` +
      `extension expects ${EXPECTED_CONTRACT_VERSION}`,
  );
}

export const kinds: readonly KindSpec[] = rulesJson.kinds;
if (kinds.length === 0) fail("no entity kinds declared");

const kindNames = new Set<string>(kinds.map((k) => k.kind));
for (const required of REQUIRED_KINDS) {
  if (!kindNames.has(required)) fail(`kind '${required}' is missing`);
}

/** Виды по имени — ссылка на контракт вместо строкового литерала в коде. */
export const KIND = Object.freeze(
  Object.fromEntries(kinds.map((k) => [k.kind, k.kind])) as Record<string, RefKind> & {
    SCENARIO: RefKind;
    FORM: RefKind;
  },
);

/** Соответствие «каталог -> вид» (`static/references/<dirName>/`). */
export const kindByDir: ReadonlyMap<string, RefKind> = new Map(
  kinds.map((k) => [k.dirName, k.kind]),
);

export const paths: PathSpec = rulesJson.paths;
if (paths.rootSegments.length === 0) fail("path root segments are empty");

export const refRules: readonly RefRule[] = rulesJson.refRules;
if (refRules.length === 0) fail("reference rule table is empty");

export const fileRefRules: readonly FileRefRule[] = rulesJson.fileRefRules;
if (fileRefRules.length === 0) fail("file reference rule table is empty");

export const typeContext: TypeContextSpec = {
  typeProperty: rulesJson.typeContext.typeProperty,
  actionKeys: new Set(rulesJson.typeContext.actionKeys),
  actionCategory: rulesJson.typeContext.actionCategory,
  contextKeys: new Set(rulesJson.typeContext.contextKeys),
  keyCategories: new Map(Object.entries(rulesJson.typeContext.keyCategories)),
  insideFieldsCategories: new Map(Object.entries(rulesJson.typeContext.insideFieldsCategories)),
  fileKindCategory: new Map(Object.entries(rulesJson.typeContext.fileKindCategory)),
};
if (typeContext.keyCategories.size === 0) fail("type context key categories are empty");

export const structuralKeys: ReadonlySet<string> = new Set(rulesJson.structuralKeys);

export const fieldAccess: FieldAccessSpec = rulesJson.fieldAccess;

export const jinja: JinjaSpec = rulesJson.jinja;

/** Реестр фреймворка -> категория ключевых слов, которую он наполняет. */
export const keywordRegistries: ReadonlyMap<string, string> = new Map(
  Object.entries(rulesJson.keywordRegistries),
);
if (keywordRegistries.size === 0) fail("keyword registry table is empty");

export const resourceScan: ResourceScanSpec = {
  ...rulesJson.resourceScan,
  excludedDirs: new Set(rulesJson.resourceScan.excludedDirs),
};
if (resourceScan.configFile.length === 0) fail("resource scan config file is empty");

/** Ключевые слова по категориям (значения свойства `type`). */
export const keywordsByCategory: ReadonlyMap<string, ReadonlySet<string>> = new Map(
  Object.entries(keywordsJson.categories).map(([category, values]) => [
    category,
    new Set(values as string[]),
  ]),
);

if (keywordsByCategory.size === 0) fail("keyword dictionary is empty");

/** Объединение всех ключевых слов по всем категориям. */
export const allKeywords: ReadonlySet<string> = new Set(
  [...keywordsByCategory.values()].flatMap((values) => [...values]),
);
