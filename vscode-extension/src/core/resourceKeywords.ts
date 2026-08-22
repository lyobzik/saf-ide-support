import { keywordRegistries, resourceScan } from "./contract";
import { parseModule, type PyClass, type PyModule, type Registration } from "./pythonScan";

/**
 * Ресурсы приложения: какие ключевые слова регистрирует активный класс.
 *
 * Активный класс — тот, что назначен переменной `RESOURCES` в `app_config.py`;
 * сканировать всякий наследник `SmartAppResources` нельзя, иначе в словарь
 * попадут неиспользуемые и тестовые классы. Цепочка наследования разрешается по
 * правилам Python: регистрации базы учитываются, только если производный метод
 * вызвал `super()`, а при совпадении имени побеждает производный класс.
 *
 * Порт 1:1 — `SmartAppResourceResolver.kt`. Файлы читает вызывающий: ядро в
 * файловую систему не ходит.
 */

/** Ключевое слово, зарегистрированное приложением. */
export interface CustomKeyword {
  readonly category: string;
  /** Декодированное имя — в тех же координатах, что значение JSON. */
  readonly name: string;
  /** Сырой диапазон содержимого литерала в файле регистрации. */
  readonly nameStart: number;
  readonly nameEnd: number;
  /** Правая часть регистрации, если это идентификатор. */
  readonly className: string | undefined;
  /** Путь файла регистрации относительно корня приложения. */
  readonly file: string;
}

/**
 * Доступ к файлам приложения. Кроме чтения нужен и предикат существования: по
 * нему отличается «модуль вне приложения» (библиотечная база — нормальный конец
 * цепочки) от «модуль приложения, которого нет» (обрыв, слова не подтверждены).
 */
export interface AppFiles {
  read(relativePath: string): string | undefined;
  /**
   * Есть ли у приложения модуль или пакет с таким путём: файл `<path>.py` либо
   * хотя бы один `.py` внутри каталога `<path>`. Обе реализации обязаны
   * отвечать одинаково — иначе «пропавший модуль приложения» и «библиотечная
   * база» поменяются местами (см. таблицу входов в тестах).
   */
  exists(path: string): boolean;
}

interface ClassRef {
  readonly file: string;
  readonly name: string;
}

/**
 * Ключевые слова активного класса ресурсов приложения.
 * Пустой список — «нечего предложить»: это не ошибка и диагностики не даёт.
 */
export function customKeywords(files: AppFiles): CustomKeyword[] {
  const configText = files.read(resourceScan.configFile);
  if (configText === undefined) return [];
  const config = parseModule(configText);

  // Присваивание RESOURCES в ветке `if` делает выбор класса динамическим:
  // угадывать ветку нельзя, поэтому слов нет вовсе (план, раздел 1).
  if (config.conditionalVars.has(resourceScan.resourcesVariable)) return [];
  const value = config.topLevelVars.get(resourceScan.resourcesVariable);
  if (value === undefined) return [];

  const start = classRefOf(value, config, resourceScan.configFile);
  if (start === undefined) return [];

  const chain = resolveChain(start, files);
  if (chain === undefined) return [];
  return effectiveKeywords(chain);
}

/** Куда указывает точечное значение в модуле [module], разобранном из [moduleFile]. */
function classRefOf(value: string, module: PyModule, moduleFile: string): ClassRef | undefined {
  const direct = module.imports.get(value);
  if (direct !== undefined) return { file: modulePath(direct.module), name: direct.name };

  if (value.includes(".")) {
    const lastDot = value.lastIndexOf(".");
    const alias = value.slice(0, lastDot);
    const name = value.slice(lastDot + 1);
    const imported = module.moduleImports.get(alias);
    return imported === undefined ? undefined : { file: modulePath(imported), name };
  }
  // Класс объявлен в самом файле, который мы уже разобрали.
  return module.classes.some((cls) => cls.name === value) ? { file: moduleFile, name: value } : undefined;
}

/** `a.b.c` -> `a/b/c.py`; пакетный вариант пробует [readModule]. */
function modulePath(module: string): string {
  return `${module.split(".").join("/")}${resourceScan.fileExtension}`;
}

interface ChainEntry {
  readonly cls: PyClass;
  readonly file: string;
}

/**
 * Цепочка классов от базы к производному. `undefined` — цепочка непригодна, и
 * тогда слов нет вовсе: частичная цепочка дала бы слова, которые нечем
 * подтвердить.
 *
 * Нормальный конец ровно один: база, которую не удалось прочитать внутри
 * приложения, — это библиотечный `SmartAppResources`. Всё остальное —
 * множественное наследование, цикл, исчерпанная глубина и класс, которого нет в
 * прочитанном модуле, — ошибка.
 */
function resolveChain(start: ClassRef, files: AppFiles): ChainEntry[] | undefined {
  const chain: ChainEntry[] = [];
  const visited = new Set<string>();
  let current: ClassRef | undefined = start;
  let depth = 0;

  while (current !== undefined) {
    if (depth++ >= resourceScan.maxBaseDepth) return undefined;
    const key = `${current.file}#${current.name}`;
    if (visited.has(key)) return undefined; // циклический импорт
    visited.add(key);

    const text = readModule(files, current.file);
    if (text === undefined) {
      // Модуль не прочитан. Если его корневой пакет есть в приложении, значит
      // это наш модуль, которого не хватает: цепочку подтвердить нечем. Если
      // пакета нет — это библиотека (база фреймворка), и цепочка закончилась.
      const expectedInApp = files.exists(rootPackageOf(current.file));
      if (expectedInApp || chain.length === 0) return undefined;
      return finish(chain);
    }
    const module = parseModule(text);
    const name: string = current.name;
    const cls: PyClass | undefined = module.classes.find((candidate) => candidate.name === name);
    if (cls === undefined) return undefined;

    chain.push({ cls, file: current.file });
    if (cls.bases.length > 1) return undefined;
    const base: string | undefined = cls.bases[0];
    if (base === undefined) break; // класс без базы — цепочка закончилась
    // База, которую не удалось даже сопоставить с модулем (нет импорта, нет
    // класса рядом), — это не библиотечная база, а неизвестность: подтвердить
    // цепочку нечем.
    const next = classRefOf(base, module, current.file);
    if (next === undefined) return undefined;
    current = next;
  }
  return finish(chain);
}

/** Цепочка в порядке применения: от базы к производному. */
function finish(chain: ChainEntry[]): ChainEntry[] {
  return [...chain].reverse();
}

/** Текст модуля: сначала `a/b/c.py`, затем `a/b/c/__init__.py`. */
function readModule(files: AppFiles, file: string): string | undefined {
  if (file.length === 0 || hasExcludedSegment(file)) return undefined;
  const direct = files.read(file);
  if (direct !== undefined) return direct;
  if (!file.endsWith(resourceScan.fileExtension)) return undefined;
  const asPackage = `${file.slice(0, -resourceScan.fileExtension.length)}/${resourceScan.packageInitFile}`;
  return hasExcludedSegment(asPackage) ? undefined : files.read(asPackage);
}

/** Корневой пакет пути модуля: `app/resources/x.py` -> `app`. */
function rootPackageOf(file: string): string {
  return file.split("/")[0] ?? file;
}

/** Путь внутри исключённого каталога (venv, site-packages, …) — не код приложения. */
export function hasExcludedSegment(path: string): boolean {
  return path.split("/").some((segment) => resourceScan.excludedDirs.has(segment));
}

/** Ключ словаря действующих регистраций: пара «категория + имя», без склейки строк. */
const keyOf = (category: string, name: string): string => JSON.stringify([category, name]);

/**
 * Свёртка цепочки в действующий словарь: метод производного класса заменяет
 * одноимённый метод базы и наследует его регистрации только через `super()`.
 */
function effectiveKeywords(chain: readonly ChainEntry[]): CustomKeyword[] {
  const byMethod = new Map<string, Map<string, CustomKeyword>>();
  for (const entry of chain) {
    for (const method of entry.cls.methods) {
      if (!method.name.startsWith(resourceScan.methodPrefix)) continue;
      const inherited = method.callsSuper ? byMethod.get(method.name) : undefined;
      const merged = new Map(inherited ?? []);
      for (const registration of method.registrations) {
        const keyword = keywordOf(registration, entry.file);
        if (keyword === undefined) continue;
        merged.set(keyOf(keyword.category, keyword.name), keyword);
      }
      byMethod.set(method.name, merged);
    }
  }

  const result = new Map<string, CustomKeyword>();
  for (const merged of byMethod.values()) {
    for (const [key, keyword] of merged) result.set(key, keyword);
  }
  return [...result.values()];
}

function keywordOf(registration: Registration, file: string): CustomKeyword | undefined {
  const category = keywordRegistries.get(registration.registry);
  if (category === undefined) return undefined;
  return {
    category,
    name: registration.name,
    nameStart: registration.nameStart,
    nameEnd: registration.nameEnd,
    className: registration.className,
    file,
  };
}
