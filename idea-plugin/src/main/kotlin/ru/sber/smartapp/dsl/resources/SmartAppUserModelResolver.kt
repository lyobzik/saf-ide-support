package ru.sber.smartapp.dsl.resources

import ru.sber.smartapp.dsl.contract.ResourceScanSpec
import ru.sber.smartapp.dsl.contract.UserModelSpec
import ru.sber.smartapp.dsl.reference.SmartAppIdentifiers
import ru.sber.smartapp.dsl.resources.SmartAppResourceResolver.AppFiles
import ru.sber.smartapp.dsl.resources.SmartAppResourceResolver.ChainResult
import ru.sber.smartapp.dsl.resources.SmartAppResourceScanner.DeclarationOrigin

/**
 * Словарь модели пользователя приложения: имена, доступные в Jinja как
 * `<root>.<name>`.
 *
 * Устроен как словарь ключевых слов: снимок фреймворка — **пол**, приложение
 * только добавляет. Без пола фича не даёт ничего на типовом приложении:
 * `CustomUser.fields` там возвращает `super().fields + []`.
 *
 * Порт 1:1 — `core/userModel.ts`. Файлы читает вызывающий: сам резолвер в
 * файловую систему не ходит.
 */
object SmartAppUserModelResolver {

    /** Место объявления имени в коде приложения. */
    data class DeclarationSite(
        /** Путь файла относительно корня приложения. */
        val file: String,
        /** Сырой диапазон имени: цель перехода и вхождение. */
        val nameStart: Int,
        val nameEnd: Int,
        val origin: DeclarationOrigin,
    )

    /** Пустой [declarations] — имя пришло из снимка, идти в коде проекта некуда. */
    data class Attribute(val name: String, val declarations: List<DeclarationSite>)

    /** Класс приложения, назначенный `USER`: цель перехода с корневой переменной. */
    data class UserClassSite(
        val name: String,
        val file: String,
        val nameStart: Int,
        val nameEnd: Int,
    )

    data class Info(
        /** Имя -> объявления, по возрастанию имени: состав сравнивается стабильно. */
        val attributes: Map<String, Attribute>,
        /**
         * Можно ли по этому словарю утверждать об ошибке.
         *
         * `false`, если сработал гаситель в коде приложения либо пол неизвестен
         * или помечен небезопасным: словарь тогда заведомо неполон.
         */
        val diagnosticsSafe: Boolean,
        /** `null` — активный класс библиотечный, переходить некуда. */
        val userClass: UserClassSite?,
    )

    /**
     * Словарь приложения или `null`, если его нет вовсе.
     *
     * `null` — это отказ: `USER` присвоен в ветке, цепочка недействительна,
     * либо пол неизвестен и классов приложения тоже нет. Пустой словарь при
     * этом — не отказ, а «нечего предложить».
     */
    fun of(files: AppFiles): Info? {
        val chain = resolveUserChain(files) ?: return null
        val floor = SmartAppUserFields.of(chain.libraryBase)
        // Пол неизвестен и приложение ничего не добавило — предлагать нечего.
        if (floor == null && chain.classes.isEmpty()) return null

        val attributes = LinkedHashMap<String, MutableList<DeclarationSite>>()
        fun add(name: String, site: DeclarationSite?) {
            if (!isOfferable(name)) return
            val sites = attributes.getOrPut(name) { ArrayList() }
            if (site != null) sites.add(site)
        }

        // `fields` сворачиваются, `attributes` объединяются — правила разные,
        // потому что первые создаёт `Model.__init__` из одноимённого списка.
        var fields = LinkedHashMap<String, MutableList<DeclarationSite>>()
        floor?.fields?.forEach { fields[it] = ArrayList() }
        floor?.attributes?.forEach { add(it, null) }

        for (entry in chain.classes) {
            if (entry.cls.fieldsState == SmartAppResourceScanner.FieldsState.WITHOUT_SUPER) {
                fields = LinkedHashMap()
            }
            for (declaration in entry.cls.declarations) {
                val site = DeclarationSite(
                    file = entry.file,
                    nameStart = declaration.nameStart,
                    nameEnd = declaration.nameEnd,
                    origin = declaration.origin,
                )
                if (declaration.origin == DeclarationOrigin.FIELD) {
                    if (!isOfferable(declaration.name)) continue
                    fields.getOrPut(declaration.name) { ArrayList() }.add(site)
                } else {
                    add(declaration.name, site)
                }
            }
        }

        // Свёрнутые поля вливаются последними: имя могло быть объявлено и полем,
        // и `self.x` — тогда у него две цели перехода.
        for ((name, sites) in fields) {
            if (!isOfferable(name)) continue
            attributes.getOrPut(name) { ArrayList() }.addAll(sites)
        }

        val derived = chain.classes.lastOrNull()
        return Info(
            attributes = attributes.toSortedMap().mapValues { (name, sites) ->
                Attribute(name, sites.toList())
            },
            diagnosticsSafe = floor?.diagnosticsSafe == true &&
                chain.classes.all { it.cls.blockers.isEmpty() },
            userClass = derived?.let {
                UserClassSite(it.cls.name, it.file, it.cls.nameStart, it.cls.nameEnd)
            },
        )
    }

    /** Годится ли имя как атрибут модели: адресуемо и без ведущего `_`. */
    private fun isOfferable(name: String): Boolean =
        SmartAppIdentifiers.isAddressableName(name) && !name.startsWith("_")

    /**
     * Цепочка от `USER`.
     *
     * Отсутствие переменной — не отказ: фреймворк подставляет свой класс
     * (`set_default(app_config, "USER", User)`), и работает один пол. А вот
     * присваивание в ветке делает выбор динамическим, и угадывать его нельзя.
     */
    private fun resolveUserChain(files: AppFiles): ChainResult? {
        val configText = files.read(ResourceScanSpec.configFile) ?: return null
        val config = SmartAppResourceScanner.parseModule(configText)

        if (UserModelSpec.configVariable in config.conditionalVars) return null

        val value = config.topLevelVars[UserModelSpec.configVariable]
            // Переменной нет — фреймворк подставляет свой класс: классов
            // приложения в цепочке нет, пол берётся по контрактному имени.
            ?: return ChainResult(emptyList(), UserModelSpec.defaultClass)

        // Тот же `app_config.py`, что у ресурсов: контракт хранит его имя один раз.
        val start = SmartAppResourceResolver.classRefOf(value, config, ResourceScanSpec.configFile)
            ?: return null
        return SmartAppResourceResolver.resolveChain(start, files)
    }
}
