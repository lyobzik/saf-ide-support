import { resourceScan, userClasses, userModel } from "./contract";
import { isAddressableName } from "./identifiers";
import { parseModule, type DeclarationOrigin } from "./pythonScan";
import { classRefOf, resolveChain, type AppFiles, type ChainResult } from "./resourceKeywords";

/**
 * Словарь модели пользователя приложения: имена, доступные в Jinja как
 * `<root>.<name>`.
 *
 * Устроен как словарь ключевых слов: снимок фреймворка — **пол**, приложение
 * только добавляет. Без пола фича не даёт ничего на типовом приложении:
 * `CustomUser.fields` там возвращает `super().fields + []`.
 *
 * Порт 1:1 — `resources/SmartAppUserModel.kt`. Файлы читает вызывающий.
 */

/** Место объявления имени в коде приложения. */
export interface UserDeclarationSite {
  /** Путь файла относительно корня приложения. */
  readonly file: string;
  /** Сырой диапазон имени: цель перехода и вхождение. */
  readonly nameStart: number;
  readonly nameEnd: number;
  readonly origin: DeclarationOrigin;
}

export interface UserAttribute {
  readonly name: string;
  /** Пусто — имя пришло из снимка фреймворка, идти в коде проекта некуда. */
  readonly declarations: readonly UserDeclarationSite[];
}

/** Класс приложения, назначенный `USER`: цель перехода с корневой переменной. */
export interface UserClassSite {
  readonly name: string;
  readonly file: string;
  readonly nameStart: number;
  readonly nameEnd: number;
}

export interface UserModelInfo {
  /** Имя -> объявления. Порядок — по имени, чтобы состав сравнивался стабильно. */
  readonly attributes: ReadonlyMap<string, UserAttribute>;
  /**
   * Можно ли по этому словарю утверждать об ошибке.
   *
   * `false`, если сработал гаситель в коде приложения либо пол неизвестен или
   * помечен небезопасным: словарь тогда заведомо неполон, и подчёркивать по
   * нему нечего.
   */
  readonly diagnosticsSafe: boolean;
  /** `undefined` — активный класс библиотечный, переходить некуда. */
  readonly userClass?: UserClassSite;
}

/**
 * Словарь приложения или `undefined`, если его нет вовсе.
 *
 * `undefined` — это отказ: `USER` присвоен в ветке, цепочка недействительна,
 * либо пол неизвестен и классов приложения тоже нет. Пустой словарь при этом
 * — не отказ, а «нечего предложить».
 */
export function userModelOf(files: AppFiles): UserModelInfo | undefined {
  const chain = resolveUserChain(files);
  if (chain === undefined) return undefined;

  const floor = chain.libraryBase === undefined ? undefined : userClasses.get(chain.libraryBase);
  // Пол неизвестен и приложение ничего не добавило — предлагать нечего вовсе.
  if (floor === undefined && chain.classes.length === 0) return undefined;

  const attributes = new Map<string, UserDeclarationSite[]>();
  const add = (name: string, site?: UserDeclarationSite): void => {
    if (!isOfferable(name)) return;
    const sites = attributes.get(name);
    if (sites === undefined) attributes.set(name, site === undefined ? [] : [site]);
    else if (site !== undefined) sites.push(site);
  };

  // `fields` сворачиваются, `attributes` объединяются — правила разные, потому
  // что первые создаёт `Model.__init__` из одноимённого списка, а вторые нет.
  let fields = new Map<string, UserDeclarationSite[]>();
  for (const name of floor?.fields ?? []) fields.set(name, []);
  for (const name of floor?.attributes ?? []) add(name);

  for (const entry of chain.classes) {
    if (entry.cls.fieldsState === "withoutSuper") fields = new Map();
    for (const declaration of entry.cls.declarations) {
      const site: UserDeclarationSite = {
        file: entry.file,
        nameStart: declaration.nameStart,
        nameEnd: declaration.nameEnd,
        origin: declaration.origin,
      };
      if (declaration.origin === "field") {
        if (!isOfferable(declaration.name)) continue;
        const sites = fields.get(declaration.name);
        if (sites === undefined) fields.set(declaration.name, [site]);
        else sites.push(site);
        continue;
      }
      add(declaration.name, site);
    }
  }

  // Свёрнутые поля вливаются в общий словарь последними: имя могло быть
  // объявлено и полем, и `self.x` — тогда у него две цели перехода.
  for (const [name, sites] of fields) {
    if (!isOfferable(name)) continue;
    const existing = attributes.get(name);
    if (existing === undefined) attributes.set(name, [...sites]);
    else existing.push(...sites);
  }

  const derived = chain.classes[chain.classes.length - 1];
  return {
    attributes: new Map(
      [...attributes.entries()]
        .sort(([left], [right]) => (left < right ? -1 : left > right ? 1 : 0))
        .map(([name, declarations]) => [name, { name, declarations }]),
    ),
    diagnosticsSafe:
      floor?.diagnosticsSafe === true &&
      chain.classes.every((entry) => entry.cls.blockers.size === 0),
    userClass:
      derived === undefined
        ? undefined
        : {
            name: derived.cls.name,
            file: derived.file,
            nameStart: derived.cls.nameStart,
            nameEnd: derived.cls.nameEnd,
          },
  };
}

/** Годится ли имя как атрибут модели: адресуемо и без ведущего `_`. */
function isOfferable(name: string): boolean {
  return isAddressableName(name) && !name.startsWith("_");
}

/**
 * Цепочка от `USER`.
 *
 * Отсутствие переменной — не отказ: фреймворк подставляет свой класс
 * (`set_default(app_config, "USER", User)`), и работает один пол. А вот
 * присваивание в ветке делает выбор динамическим, и угадывать его нельзя.
 */
function resolveUserChain(files: AppFiles): ChainResult | undefined {
  const configText = files.read(resourceScan.configFile);
  if (configText === undefined) return undefined;
  const config = parseModule(configText);

  if (config.conditionalVars.has(userModel.configVariable)) return undefined;

  const value = config.topLevelVars.get(userModel.configVariable);
  if (value === undefined) {
    // Переменной нет — фреймворк подставляет свой класс: классов приложения в
    // цепочке нет, пол берётся по контрактному имени.
    return { classes: [], libraryBase: userModel.defaultClass };
  }

  // Тот же `app_config.py`, что у ресурсов: контракт хранит его имя один раз.
  const start = classRefOf(value, config, resourceScan.configFile);
  if (start === undefined) return undefined;
  return resolveChain(start, files);
}
