package ru.sber.smartapp.dsl.resources

import ru.sber.smartapp.dsl.contract.JinjaSpec
import ru.sber.smartapp.dsl.contract.ResourceScanSpec
import ru.sber.smartapp.dsl.contract.UserModelSpec
import ru.sber.smartapp.dsl.reference.SmartAppIdentifiers
import ru.sber.smartapp.dsl.resources.SmartAppResourceResolver.AppFiles
import ru.sber.smartapp.dsl.resources.SmartAppResourceResolver.ChainEntry
import ru.sber.smartapp.dsl.resources.SmartAppResourceScanner.DictionaryBinding

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
 * `userModelChain` ([SmartAppUserModelResolver]): та даёт словарь имён, эта —
 * корневое имя и право утверждать. Отказ здесь ничего не отключает: словарь
 * работает под дефолтным именем, молча.
 *
 * Порт 1:1 — `core/userRoot.ts`.
 */
object SmartAppUserRootResolver {

    /**
     * [PROVEN] — привязка доказана разбором: имена настоящие, WARNING разрешён.
     * [DEFAULT] — доказательства нет: работает контрактный дефолт, но молча.
     * [DISPROVED] — есть доказательство обратного: под дефолтным именем лежит
     * чужое значение, и предлагать поля модели под ним нельзя.
     */
    enum class RootState { PROVEN, DEFAULT, DISPROVED }

    /** [names] для [RootState.DISPROVED] пуст: предлагать нечего. */
    data class Root(val state: RootState, val names: List<String>)

    private val defaultRoot = Root(RootState.DEFAULT, listOf(JinjaSpec.userVariableDefault))

    /** Корневое имя (имена) модели пользователя для приложения. */
    fun of(files: AppFiles): Root {
        val bindings = parametrizerBindings(files) ?: return defaultRoot

        // Годный ключ — связанный ровно с `self._user`, кроме имени переменной
        // формы: под ним уже работает своя семантика (план, раздел 0).
        // Ведущее подчёркивание корню не мешает (в отличие от полей): ключ
        // словаря параметров приложение выбирает осознанно, и `{{ _u.x }}` в
        // шаблоне законно.
        val proven = bindings.values
            .filter { it.value == UserModelSpec.userValueExpression }
            .map { it.key }
            .filter { it != JinjaSpec.formVariable && SmartAppIdentifiers.isAddressableName(it) }
        if (proven.isNotEmpty()) return Root(RootState.PROVEN, proven)

        // Доказательство обратного требует именно **распознанного** значения:
        // `data["user"] = build_user()` — это незнание, а не чужой корень, и
        // выключать по нему предложения нельзя.
        val underDefault = bindings[JinjaSpec.userVariableDefault]?.value
        if (underDefault != null && underDefault != UserModelSpec.userValueExpression) {
            return Root(RootState.DISPROVED, emptyList())
        }
        return defaultRoot
    }

    /**
     * Действующие записи словаря параметров или `null`, если доказательства нет
     * вовсе: цепочка непригодна, метод не найден либо правило `<d>` нарушено в
     * методе, участвующем в свёртке.
     */
    private fun parametrizerBindings(files: AppFiles): Map<String, DictionaryBinding>? {
        val configText = files.read(ResourceScanSpec.configFile) ?: return null
        val config = SmartAppResourceScanner.parseModule(configText)

        // Присваивание в ветке делает выбор класса динамическим — как у `RESOURCES`.
        if (UserModelSpec.parametrizerVariable in config.conditionalVars) return null
        // Переменной нет — фреймворк подставляет свой параметризатор. Классов
        // приложения в цепочке не будет, а библиотечный `user` не связывает.
        val value = config.topLevelVars[UserModelSpec.parametrizerVariable] ?: return null

        val start = SmartAppResourceResolver.classRefOf(value, config, ResourceScanSpec.configFile)
            ?: return null
        val chain = SmartAppResourceResolver.resolveChain(start, files) ?: return null
        return foldParametrizer(chain.classes)
    }

    /**
     * Свёртка цепочки: производный `_get_user_data` наследует привязки базы
     * **только** если инициализировал словарь вызовом `super()`.
     *
     * Плоское объединение здесь опаснее, чем в словаре имён: метод, собравший
     * словарь с нуля, привязки базы не получает, а мы бы её засчитали — и
     * включили бы WARNING на корне, которого нет.
     */
    private fun foldParametrizer(chain: List<ChainEntry>): Map<String, DictionaryBinding>? {
        // Доказательство держится на двух вещах, и гасители ломают обе: что
        // `_get_user_data` — тот самый метод, который мы прочитали, и что
        // `self._user` — атрибут фреймворка. Декоратор и метакласс подменяют
        // класс целиком, `__getattribute__` перехватывает и метод, и атрибут, а
        // `__init__` без `super()` означает, что `self._user` фреймворк вообще
        // не присваивал. Правило то же, что у `diagnosticsSafe` в
        // `userModelChain`, и по той же причине: WARNING — утверждение, и оно
        // требует цепочки, опознанной как простая целиком.
        if (chain.any { it.cls.blockers.isNotEmpty() }) return null

        var bindings = LinkedHashMap<String, DictionaryBinding>()
        var broken = false
        var defined = false

        for (entry in chain) {
            // Побеждает последнее определение метода — так работает Python.
            val method = entry.cls.methods
                .lastOrNull { it.name == UserModelSpec.parametrizerMethod }
                ?: continue // класс метод не переопределяет
            defined = true

            val use = method.dictionary
            if (use == null) {
                // Правило нарушено: содержимое словаря неизвестно.
                broken = true
                bindings = LinkedHashMap()
                continue
            }
            if (!use.inheritsBase) {
                // Метод собрал словарь с нуля: и привязки базы, и её
                // ненадёжность остались позади.
                bindings = LinkedHashMap()
                broken = false
            }
            // При повторе ключа побеждает последняя запись — поведение `dict`.
            for (binding in use.bindings) bindings[binding.key] = binding
        }
        return if (defined && !broken) bindings else null
    }
}
