import rulesJson from "../../../shared/rules/rules.json";
import keywordsJson from "../../../shared/keywords/keywords.json";
import userFieldsJson from "../../../shared/keywords/user_fields.json";

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
export const EXPECTED_CONTRACT_VERSION = 6;

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
  /**
   * Имя, под которым доступна модель пользователя, — **значение по умолчанию**,
   * а не гарантия: фреймворк `user` в параметры шаблона не кладёт, его
   * дописывает параметризатор приложения. Пока привязка не доказана разбором,
   * под этим именем работают предложения, но не утверждения.
   */
  readonly userVariableDefault: string;
}

/** Правила чтения модели пользователя приложения: имена, доступные как `user.<name>`. */
export interface UserModelSpec {
  /** Переменная `app_config.py`, назначающая класс модели пользователя. */
  readonly configVariable: string;
  /** Класс, подставляемый фреймворком, если переменная не задана. */
  readonly defaultClass: string;
  /** Свойство класса, возвращающее список полей модели. */
  readonly fieldsProperty: string;
  /** Вызов, первый позиционный аргумент которого — имя атрибута. */
  readonly fieldFactory: string;
  readonly parametrizerVariable: string;
  readonly parametrizerDefaultClass: string;
  /** Метод параметризатора, собирающий словарь параметров шаблона. */
  readonly parametrizerMethod: string;
  /** Единственное значение, признаваемое доказательством привязки корневого имени. */
  readonly userValueExpression: string;
  /** Гасители диагностики — текстовые; ищутся по маскированному тексту. */
  readonly blockerTokens: ReadonlySet<string>;
  /** Гасители диагностики — структурные: контракт фиксирует состав проверок. */
  readonly blockerConstructs: ReadonlySet<string>;
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

/**
 * Снимок полей и атрибутов модели пользователя фреймворка — пол, поверх
 * которого работает половина приложения.
 *
 * `fields` и `attributes` разделены не для красоты: `fields` создаёт
 * `Model.__init__` из одноимённого списка, и они **отбрасываются**, если класс
 * приложения переопределил свойство без `super().fields`; `attributes`
 * свойством не управляются.
 */
export interface UserClassSnapshot {
  readonly fields: readonly string[];
  readonly attributes: readonly string[];
  /** Решение, принятое при вендоринге: можно ли включать WARNING поверх этого пола. */
  readonly diagnosticsSafe: boolean;
}

/** Правила чтения модели пользователя приложения. */
export const userModel: UserModelSpec = {
  ...rulesJson.userModel,
  blockerTokens: new Set(rulesJson.userModel.blockerTokens),
  blockerConstructs: new Set(rulesJson.userModel.blockerConstructs),
};
if (userModel.configVariable.length === 0) fail("user model config variable is empty");

/** Точечное имя библиотечного класса -> его имена. Ключ — то же, что `ChainResult.libraryBase`. */
export const userClasses: ReadonlyMap<string, UserClassSnapshot> = new Map(
  Object.entries(userFieldsJson.classes ?? {}),
);
// Тихая деградация недопустима: пустой пол выключил бы семантику модели
// пользователя целиком, а на типовом приложении все имена приходят именно из него.
if (userClasses.size === 0) fail("user model snapshot has no classes");
// Класс по умолчанию — пол типового приложения: `USER` там либо не задан вовсе,
// либо назначает наследника именно его. Непустой снимок без этого класса прошёл
// бы проверку выше и молча оставил такое приложение без библиотечных имён.
const defaultUserClass = userClasses.get(userModel.defaultClass);
if (defaultUserClass === undefined) {
  fail(`user model snapshot has no default class ${userModel.defaultClass}`);
} else if (defaultUserClass.fields.length === 0) {
  // Наличия мало: пустой `fields` проверку присутствия проходит, а пол теряет.
  fail(`default user class ${userModel.defaultClass} declares no fields`);
}

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
