package ru.sber.smartapp.dsl.resources

import ru.sber.smartapp.dsl.contract.ResourceScanSpec
import ru.sber.smartapp.dsl.contract.SmartAppSpecs

/**
 * Ресурсы приложения: какие ключевые слова регистрирует активный класс.
 *
 * Активный класс — тот, что назначен переменной `RESOURCES` в `app_config.py`;
 * сканировать всякий наследник `SmartAppResources` нельзя, иначе в словарь
 * попадут неиспользуемые и тестовые классы. Цепочка наследования разрешается по
 * правилам Python: регистрации базы учитываются, только если производный метод
 * вызвал `super()`, а при совпадении имени побеждает производный класс.
 *
 * Порт 1:1 — `core/resourceKeywords.ts`. Файлы читает вызывающий: сам резолвер
 * в файловую систему не ходит.
 */
object SmartAppResourceResolver {

    /** Ключевое слово, зарегистрированное приложением. */
    data class CustomKeyword(
        val category: String,
        /** Декодированное имя — в тех же координатах, что значение JSON. */
        val name: String,
        /** Сырой диапазон содержимого литерала в файле регистрации. */
        val nameStart: Int,
        val nameEnd: Int,
        /** Правая часть регистрации, если это идентификатор. */
        val className: String?,
        /** Путь файла регистрации относительно корня приложения. */
        val file: String,
    )

    /** Чтение файла приложения по пути относительно его корня. */
    fun interface ModuleReader {
        fun read(relativePath: String): String?
    }

    private data class ClassRef(val file: String, val name: String)

    private data class ChainEntry(val cls: SmartAppResourceScanner.PyClass, val file: String)

    /**
     * Ключевые слова активного класса ресурсов приложения.
     * Пустой список — «нечего предложить»: это не ошибка и диагностики не даёт.
     */
    fun customKeywords(read: ModuleReader): List<CustomKeyword> {
        val configText = read.read(ResourceScanSpec.configFile) ?: return emptyList()
        val config = SmartAppResourceScanner.parseModule(configText)

        // Присваивание RESOURCES в ветке `if` делает выбор класса динамическим:
        // угадывать ветку нельзя, поэтому слов нет вовсе (план, раздел 1).
        if (ResourceScanSpec.resourcesVariable in config.conditionalVars) return emptyList()
        val value = config.topLevelVars[ResourceScanSpec.resourcesVariable] ?: return emptyList()

        val start = classRefOf(value, config, ResourceScanSpec.configFile) ?: return emptyList()
        val chain = resolveChain(start, read) ?: return emptyList()
        return effectiveKeywords(chain)
    }

    /** Куда указывает точечное значение в модуле [module], разобранном из [moduleFile]. */
    private fun classRefOf(
        value: String,
        module: SmartAppResourceScanner.PyModule,
        moduleFile: String,
    ): ClassRef? {
        module.imports[value]?.let { return ClassRef(modulePath(it.module), it.name) }

        if (value.contains('.')) {
            val alias = value.substringBeforeLast('.')
            val name = value.substringAfterLast('.')
            val imported = module.moduleImports[alias] ?: return null
            return ClassRef(modulePath(imported), name)
        }
        // Класс объявлен в самом файле, который мы уже разобрали.
        return if (module.classes.any { it.name == value }) ClassRef(moduleFile, value) else null
    }

    /** `a.b.c` -> `a/b/c.py`; пакетный вариант пробует [readModule]. */
    private fun modulePath(module: String): String =
        module.replace('.', '/') + ResourceScanSpec.fileExtension

    /**
     * Цепочка классов от базы к производному. `null` — цепочка непригодна, и
     * тогда слов нет вовсе: частичная цепочка дала бы слова, которые нечем
     * подтвердить.
     *
     * Нормальный конец ровно один: база, которую не удалось прочитать внутри
     * приложения, — это библиотечный `SmartAppResources`. Всё остальное —
     * множественное наследование, цикл, исчерпанная глубина и класс, которого
     * нет в прочитанном модуле, — ошибка.
     */
    private fun resolveChain(start: ClassRef, read: ModuleReader): List<ChainEntry>? {
        val chain = ArrayList<ChainEntry>()
        val visited = HashSet<String>()
        var current: ClassRef? = start
        var depth = 0

        while (current != null) {
            if (depth++ >= ResourceScanSpec.maxBaseDepth) return null
            val ref = current
            if (!visited.add("${ref.file}#${ref.name}")) return null // циклический импорт

            val text = readModule(read, ref.file)
                // Модуль вне приложения: это база фреймворка, дальше идти некуда.
                // Но если так оборвался сам активный класс — подтверждать нечего.
                ?: return if (chain.isEmpty()) null else chain.asReversed().toList()
            val module = SmartAppResourceScanner.parseModule(text)
            val cls = module.classes.firstOrNull { it.name == ref.name } ?: return null

            chain.add(ChainEntry(cls, ref.file))
            if (cls.bases.size > 1) return null
            val base = cls.bases.firstOrNull()
            current = if (base == null) null else classRefOf(base, module, ref.file)
        }
        return chain.asReversed().toList() // от базы к производному — в порядке применения
    }

    /** Текст модуля: сначала `a/b/c.py`, затем `a/b/c/__init__.py`. */
    private fun readModule(read: ModuleReader, file: String): String? {
        if (file.isEmpty() || hasExcludedSegment(file)) return null
        read.read(file)?.let { return it }
        if (!file.endsWith(ResourceScanSpec.fileExtension)) return null
        val asPackage = file.dropLast(ResourceScanSpec.fileExtension.length) +
            "/" + ResourceScanSpec.packageInitFile
        return if (hasExcludedSegment(asPackage)) null else read.read(asPackage)
    }

    /** Путь внутри исключённого каталога (venv, site-packages, …) — не код приложения. */
    fun hasExcludedSegment(path: String): Boolean =
        path.split('/').any { it in ResourceScanSpec.excludedDirs }

    /**
     * Свёртка цепочки в действующий словарь: метод производного класса заменяет
     * одноимённый метод базы и наследует его регистрации только через `super()`.
     */
    private fun effectiveKeywords(chain: List<ChainEntry>): List<CustomKeyword> {
        val byMethod = LinkedHashMap<String, LinkedHashMap<Pair<String, String>, CustomKeyword>>()
        for (entry in chain) {
            for (method in entry.cls.methods) {
                if (!method.name.startsWith(ResourceScanSpec.methodPrefix)) continue
                val inherited = if (method.callsSuper) byMethod[method.name] else null
                val merged = LinkedHashMap(inherited ?: emptyMap())
                for (registration in method.registrations) {
                    val keyword = keywordOf(registration, entry.file) ?: continue
                    merged[keyword.category to keyword.name] = keyword
                }
                byMethod[method.name] = merged
            }
        }

        val result = LinkedHashMap<Pair<String, String>, CustomKeyword>()
        for (merged in byMethod.values) result.putAll(merged)
        return result.values.toList()
    }

    private fun keywordOf(
        registration: SmartAppResourceScanner.Registration,
        file: String,
    ): CustomKeyword? {
        val category = SmartAppSpecs.keywordRegistries[registration.registry] ?: return null
        return CustomKeyword(
            category = category,
            name = registration.name,
            nameStart = registration.nameStart,
            nameEnd = registration.nameEnd,
            className = registration.className,
            file = file,
        )
    }
}
