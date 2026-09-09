import { jinja, resourceScan, userModel } from "./contract";
import { isAddressableName } from "./identifiers";
import { parseModule, type DictionaryBinding } from "./pythonScan";
import { classRefOf, resolveChain, type AppFiles, type ChainEntry } from "./resourceKeywords";

/**
 * Корневое имя пользователя в Jinja и право на диагностику.
 *
 * Имя `user` — не гарантия фреймворка: параметры шаблона собирает
 * `Parametrizer._get_user_data`, и библиотечный кладёт туда `message`,
 * `variables`, `forms` и прочее, но не самого пользователя. Имя появляется
 * только тогда, когда приложение дописало его в своём параметризаторе, — и
 * ровно это здесь и вычитывается.
 *
 * Цепочка `parametrizerChain` идёт от `PARAMETRIZER` и **не смешивается** с
 * `userModelChain` (`core/userModel.ts`): та даёт словарь имён, эта — корневое
 * имя и право утверждать. Отказ здесь ничего не отключает: словарь работает под
 * дефолтным именем, молча.
 *
 * Порт 1:1 — `SmartAppUserRootResolver.kt`.
 */

/**
 * `proven` — привязка доказана разбором: имена настоящие, WARNING разрешён.
 * `default` — доказательства нет: работает контрактный дефолт, но молча.
 * `disproved` — есть доказательство обратного: под дефолтным именем лежит
 * чужое значение, и предлагать поля модели под ним нельзя.
 */
export type RootState = "proven" | "default" | "disproved";

export interface UserRoot {
  readonly state: RootState;
  /** Корневые имена; для `disproved` — пусто: предлагать нечего. */
  readonly names: readonly string[];
}

const defaultRoot: UserRoot = { state: "default", names: [jinja.userVariableDefault] };

/** Корневое имя (имена) модели пользователя для приложения. */
export function userRootOf(files: AppFiles): UserRoot {
  const bindings = parametrizerBindings(files);
  if (bindings === undefined) return defaultRoot;

  // Годный ключ — связанный ровно с `self._user`, кроме имени переменной формы:
  // под ним уже работает своя семантика (план, раздел 0).
  // Ведущее подчёркивание корню не мешает (в отличие от полей): ключ словаря
  // параметров приложение выбирает осознанно, и `{{ _u.x }}` в шаблоне законно.
  const proven = [...bindings.values()]
    .filter((binding) => binding.value === userModel.userValueExpression)
    .map((binding) => binding.key)
    .filter((key) => key !== jinja.formVariable && isAddressableName(key));
  if (proven.length > 0) return { state: "proven", names: proven };

  // Доказательство обратного требует именно **распознанного** значения:
  // `data["user"] = build_user()` — это незнание, а не чужой корень, и
  // выключать по нему предложения нельзя.
  const underDefault = bindings.get(jinja.userVariableDefault);
  if (
    underDefault?.value !== undefined &&
    underDefault.value !== userModel.userValueExpression
  ) {
    return { state: "disproved", names: [] };
  }
  return defaultRoot;
}

/**
 * Действующие записи словаря параметров или `undefined`, если доказательства
 * нет вовсе: цепочка непригодна, метод не найден либо правило `<d>` нарушено в
 * методе, участвующем в свёртке.
 */
function parametrizerBindings(files: AppFiles): Map<string, DictionaryBinding> | undefined {
  const configText = files.read(resourceScan.configFile);
  if (configText === undefined) return undefined;
  const config = parseModule(configText);

  // Присваивание в ветке делает выбор класса динамическим — как у `RESOURCES`.
  if (config.conditionalVars.has(userModel.parametrizerVariable)) return undefined;
  // Переменной нет — фреймворк подставляет свой параметризатор. Классов
  // приложения в цепочке не будет, а библиотечный `user` не связывает.
  const value = config.topLevelVars.get(userModel.parametrizerVariable);
  if (value === undefined) return undefined;

  const start = classRefOf(value, config, resourceScan.configFile);
  if (start === undefined) return undefined;
  const chain = resolveChain(start, files);
  return chain === undefined ? undefined : foldParametrizer(chain.classes);
}

/**
 * Свёртка цепочки: производный `_get_user_data` наследует привязки базы
 * **только** если инициализировал словарь вызовом `super()`.
 *
 * Плоское объединение здесь опаснее, чем в словаре имён: метод, собравший
 * словарь с нуля, привязки базы не получает, а мы бы её засчитали — и включили
 * бы WARNING на корне, которого нет.
 */
function foldParametrizer(
  chain: readonly ChainEntry[],
): Map<string, DictionaryBinding> | undefined {
  // Доказательство держится на двух вещах, и гасители ломают обе: что
  // `_get_user_data` — тот самый метод, который мы прочитали, и что `self._user`
  // — атрибут фреймворка. Декоратор и метакласс подменяют класс целиком,
  // `__getattribute__` перехватывает и метод, и атрибут, а `__init__` без
  // `super()` означает, что `self._user` фреймворк вообще не присваивал.
  // Правило то же, что у `diagnosticsSafe` в `userModelChain`, и по той же
  // причине: WARNING — утверждение, и оно требует цепочки, опознанной как
  // простая целиком.
  if (chain.some((entry) => entry.cls.blockers.size > 0)) return undefined;

  let bindings = new Map<string, DictionaryBinding>();
  let broken = false;
  let defined = false;

  for (const entry of chain) {
    // Побеждает последнее определение метода — так работает Python.
    const overrides = entry.cls.methods.filter(
      (method) => method.name === userModel.parametrizerMethod,
    );
    const method = overrides[overrides.length - 1];
    if (method === undefined) continue; // класс метод не переопределяет
    defined = true;

    const use = method.dictionary;
    if (use === undefined) {
      // Правило нарушено: содержимое словаря неизвестно.
      broken = true;
      bindings = new Map();
      continue;
    }
    if (!use.inheritsBase) {
      // Метод собрал словарь с нуля: и привязки базы, и её ненадёжность
      // остались позади.
      bindings = new Map();
      broken = false;
    }
    // При повторе ключа побеждает последняя запись — поведение `dict`.
    for (const binding of use.bindings) bindings.set(binding.key, binding);
  }
  return defined && !broken ? bindings : undefined;
}
