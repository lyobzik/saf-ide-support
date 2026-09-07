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

    /**
     * Доступ к файлам приложения. Кроме чтения нужен и предикат существования:
     * по нему отличается «модуль вне приложения» (библиотечная база — нормальный
     * конец цепочки) от «модуль приложения, которого нет» (обрыв, слова не
     * подтверждены).
     */
    interface AppFiles {
        fun read(relativePath: String): String?

        /** Есть ли в приложении файл или каталог по этому пути. */
        fun exists(path: String): Boolean
    }

    /** Класс, на который указывает значение: путь модуля внутри приложения и имя. */
    data class ClassRef(val file: String, val name: String)

    data class ChainEntry(val cls: SmartAppResourceScanner.PyClass, val file: String)

    /**
     * Разрешённая цепочка наследования.
     *
     * [classes] — классы **приложения** от базы к производному; пустой список
     * значит, что активный класс библиотечный и приложение к нему ничего не
     * добавило. Для ресурсов это «добавить нечего», для модели пользователя —
     * основной случай (`USER = User`, либо `USER` не задан вовсе).
     *
     * [libraryBase] — точечное имя первой базы, которую не удалось прочитать
     * внутри приложения: `scenarios.user.user_model.User`. `null` значит, что
     * цепочка кончилась классом без базы. Ресурсам это имя не нужно, модели
     * пользователя по нему выбирается пол из снимка фреймворка.
     */
    data class ChainResult(val classes: List<ChainEntry>, val libraryBase: String?)

    /**
     * Ключевые слова активного класса ресурсов приложения.
     * Пустой список — «нечего предложить»: это не ошибка и диагностики не даёт.
     */
    fun customKeywords(files: AppFiles): List<CustomKeyword> {
        val configText = files.read(ResourceScanSpec.configFile) ?: return emptyList()
        val config = SmartAppResourceScanner.parseModule(configText)

        // Присваивание RESOURCES в ветке `if` делает выбор класса динамическим:
        // угадывать ветку нельзя, поэтому слов нет вовсе (план, раздел 1).
        if (ResourceScanSpec.resourcesVariable in config.conditionalVars) return emptyList()
        val value = config.topLevelVars[ResourceScanSpec.resourcesVariable] ?: return emptyList()

        val start = classRefOf(value, config, ResourceScanSpec.configFile) ?: return emptyList()
        // Ресурсам нужны только классы приложения: слова фреймворка приходят из
        // снимка `keywords.json`, а не из цепочки, поэтому `libraryBase` здесь
        // не смотрится, а пустой список классов означает «добавить нечего».
        val chain = resolveChain(start, files) ?: return emptyList()
        return effectiveKeywords(chain.classes)
    }

    /** Куда указывает точечное значение в модуле [module], разобранном из [moduleFile]. */
    fun classRefOf(
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
     * Разрешает цепочку наследования от [start]. `null` — цепочка
     * **недействительна**, то есть построить её не удалось: множественное
     * наследование, цикл, исчерпанная глубина, класс, которого нет в
     * прочитанном модуле, пропавший модуль приложения и база, которую не
     * удалось сопоставить ни с импортом, ни с классом рядом. Частичная цепочка
     * дала бы имена, которые нечем подтвердить.
     *
     * Нормальных концов два: база вне приложения (библиотечный класс — её имя
     * уезжает в [ChainResult.libraryBase]) и класс без базы (`libraryBase` =
     * `null`). Пустой список классов при непустом `libraryBase` — валидный
     * результат: активный класс библиотечный.
     */
    fun resolveChain(start: ClassRef, files: AppFiles): ChainResult? {
        val chain = ArrayList<ChainEntry>()
        val visited = HashSet<String>()
        var current: ClassRef? = start
        var depth = 0

        while (current != null) {
            if (depth++ >= ResourceScanSpec.maxBaseDepth) return null
            val ref = current
            if (!visited.add("${ref.file}#${ref.name}")) return null // циклический импорт

            val text = readModule(files, ref.file)
            if (text == null) {
                // Модуль не прочитан. Если его корневой пакет есть в приложении,
                // значит это наш модуль, которого не хватает: подтвердить цепочку
                // нечем. Если пакета нет — это библиотека, и цепочка закончилась.
                val expectedInApp = files.exists(rootPackageOf(ref.file))
                if (expectedInApp) return null
                // Пустая цепочка здесь — не отказ: значит, активный класс сам
                // библиотечный. Ресурсы отобразят это в «добавить нечего»,
                // модели пользователя этого хватает, чтобы выбрать пол.
                return ChainResult(chain.asReversed().toList(), dottedNameOf(ref))
            }
            val module = SmartAppResourceScanner.parseModule(text)
            val cls = module.classes.firstOrNull { it.name == ref.name } ?: return null

            chain.add(ChainEntry(cls, ref.file))
            if (cls.bases.size > 1) return null
            val base = cls.bases.firstOrNull() ?: break // класс без базы — конец цепочки
            // База, которую не удалось даже сопоставить с модулем (нет импорта,
            // нет класса рядом), — это не библиотечная база, а неизвестность:
            // подтвердить цепочку нечем.
            current = classRefOf(base, module, ref.file) ?: return null
        }
        // Класс без базы: цепочка кончилась, библиотечного пола у неё нет.
        return ChainResult(chain.asReversed().toList(), libraryBase = null)
    }

    /**
     * Точечное имя класса: `scenarios/user/user_model.py` + `User` ->
     * `scenarios.user.user_model.User`. Пакетная форма отдельного случая не
     * требует: [ClassRef.file] всегда хранит `.py`-вариант, а `__init__.py`
     * подставляет уже [readModule].
     */
    private fun dottedNameOf(ref: ClassRef): String {
        val module = ref.file.removeSuffix(ResourceScanSpec.fileExtension).replace('/', '.')
        return if (module.isEmpty()) ref.name else "$module.${ref.name}"
    }

    /** Текст модуля: сначала `a/b/c.py`, затем `a/b/c/__init__.py`. */
    private fun readModule(files: AppFiles, file: String): String? {
        if (file.isEmpty() || hasExcludedSegment(file)) return null
        files.read(file)?.let { return it }
        if (!file.endsWith(ResourceScanSpec.fileExtension)) return null
        val asPackage = file.dropLast(ResourceScanSpec.fileExtension.length) +
            "/" + ResourceScanSpec.packageInitFile
        return if (hasExcludedSegment(asPackage)) null else files.read(asPackage)
    }

    /** Корневой пакет пути модуля: `app/resources/x.py` -> `app`. */
    private fun rootPackageOf(file: String): String = file.substringBefore('/')

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
