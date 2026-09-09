package ru.sber.smartapp.dsl.contract

import ru.sber.smartapp.dsl.SmartAppRefKind

/**
 * Единый источник правды для **данных** SmartApp DSL: имён видов и каталогов,
 * сегментов пути, таблицы ссылочных правил, ключей контекста `type`, структурных
 * ключей, ключей доступа к полям формы и имени Jinja-переменной формы.
 *
 * Эти же таблицы используются рантаймом плагина и экспортируются в
 * `shared/rules/rules.json` таском `exportRules`, откуда их читает
 * VS Code-расширение. Дублировать данные в экспортёре нельзя: расхождение
 * рантайма и снимка — ровно та ошибка, ради которой контракт и заводится.
 *
 * Граница «контракт vs код»: сюда попадает всё, что описывает **DSL** (имена
 * сущностей, каталогов, ключей). Синтаксис и алгоритмы (разделители Jinja,
 * правила JSON-escape, порядок подъёма по дереву) остаются кодом обеих сторон.
 */
object SmartAppContract {

    /**
     * Версия формата контракта. Увеличивается при **любом несовместимом**
     * изменении структуры или семантики данных: новое обязательное поле,
     * удаление/переименование поля, изменение смысла существующего.
     *
     * Синхронно с бампом обновляются `shared/rules/rules.schema.json`
     * (там версия описана как `const`) и `EXPECTED_CONTRACT_VERSION` в
     * TS-расширении — иначе проверки падают.
     */
    const val VERSION: Int = 6
}

/** Вид сущности и подкаталог `static/references/<dirName>/`, в котором он живёт. */
data class KindSpec(val kind: SmartAppRefKind, val dirName: String)

/**
 * Правило ссылочной позиции: значение свойства [property] ссылается на
 * определения видов [targets], если выполнены условия.
 *
 * [ownerTypes] — допустимые значения `type` у объекта-владельца свойства
 * (`null` — владелец не важен); [fileKind] — вид файла, в котором правило
 * действует (`null` — любой).
 */
data class RuleSpec(
    val property: String,
    val ownerTypes: Set<String>?,
    val fileKind: SmartAppRefKind?,
    val targets: List<SmartAppRefKind>,
)

/**
 * Правило ссылки **на файл**: значение свойства [property] называет файл,
 * который лежит в одном из каталогов [searchDirs] внутри набора `references`.
 *
 * Отдельный вид правила, а не [RuleSpec]: там цель — top-level ключ JSON,
 * найденный по индексу определений, здесь — файл целиком, найденный по пути.
 *
 * [ownerTypes] — допустимые значения `type` у объекта-владельца (`null` —
 * владелец не важен). [searchDirs] просматриваются по порядку, побеждает первый
 * каталог, в котором файл нашёлся.
 */
data class FileRefSpec(
    val property: String,
    val ownerTypes: Set<String>?,
    val searchDirs: List<String>,
)

/** Раскладка DSL-файлов на диске. */
object PathSpec {
    /** Подряд идущие сегменты пути, после которых идёт каталог вида. */
    val rootSegments: List<String> = listOf("static", "references")

    val fileExtension: String = ".json"

    val extensionIgnoreCase: Boolean = true
}

/** Ключи, через которые описывается доступ к полям формы. */
object FieldAccessSpec {
    /** Свойство сценария, указывающее целевую форму. */
    val formProperty: String = "form"

    /** Свойство формы, содержащее объект с описаниями полей. */
    val fieldsProperty: String = "fields"
}

/** Имена, значимые внутри Jinja-интерполяций. */
object JinjaSpec {
    /** Переменная, через которую доступны поля целевой формы. */
    val formVariable: String = "main_form"

    /**
     * Имя, под которым доступна модель пользователя, — **значение по
     * умолчанию**, а не гарантия. Фреймворк `user` в параметры шаблона не
     * кладёт: их собирает `Parametrizer._get_user_data`, и `user` появляется
     * только когда приложение дописывает его в своём параметризаторе. Пока
     * привязка не доказана разбором (см. [UserModelSpec]), под этим именем
     * работают предложения — автодополнение и переход, — но не утверждения:
     * WARNING остаётся выключенным.
     */
    val userVariableDefault: String = "user"
}

/**
 * Правила чтения модели пользователя приложения: какие имена доступны в Jinja
 * как `user.<name>`.
 *
 * Устроено симметрично [ResourceScanSpec]: активный класс назначает
 * `app_config.py`, атрибуты собираются по цепочке наследования, пол приходит из
 * снимка фреймворка. Здесь только имена и раскладка (данные); разбор Python —
 * алгоритм, он живёт в коде обеих реализаций.
 */
object UserModelSpec {

    /** Переменная `app_config.py`, назначающая класс модели пользователя. */
    const val configVariable: String = "USER"

    /**
     * Класс, который подставляет сам фреймворк, если `USER` не задан
     * (`smart_kit/configs/__init__.py`: `set_default(app_config, "USER", User)`).
     * Отсутствие `USER` — не «ничего не известно», а «работает один пол».
     */
    const val defaultClass: String = "scenarios.user.user_model.User"

    /** Свойство класса, возвращающее список полей модели. */
    const val fieldsProperty: String = "fields"

    /**
     * Вызов, первый позиционный аргумент которого — имя атрибута.
     * Ровно эти имена `Model.__init__` раздаёт через `setattr`.
     */
    const val fieldFactory: String = "Field"

    /** Переменная `app_config.py`, назначающая параметризатор. */
    const val parametrizerVariable: String = "PARAMETRIZER"

    /** Класс параметризатора по умолчанию — тот же `set_default`. */
    const val parametrizerDefaultClass: String = "scenarios.user.parametrizer.Parametrizer"

    /** Метод параметризатора, собирающий словарь параметров шаблона. */
    const val parametrizerMethod: String = "_get_user_data"

    /**
     * Единственное значение, признаваемое доказательством привязки корневого
     * имени к модели пользователя. Множества здесь быть не должно: `self._user`
     * — атрибут самого фреймворка (`BasicParametrizer.__init__`), а любое
     * второе имя уже догадка о коде приложения.
     */
    const val userValueExpression: String = "self._user"

    /**
     * Гасители диагностики — текстовые. Ищутся по **маскированному** тексту:
     * упоминание в docstring или комментарии гасителем не является.
     */
    val blockerTokens: Set<String> = linkedSetOf(
        "__getattr__",
        "__getattribute__",
        "__setattr__",
        "__delattr__",
        "__slots__",
        "__dict__",
        "setattr(",
        "vars(",
        "globals(",
        "locals(",
    )

    /**
     * Гасители диагностики — структурные. Отдельный список, потому что одним
     * множеством строк они не выражаются: «dunder, кроме `__init__`» и
     * «декоратор на самом классе» — не токены. Контракт фиксирует **состав и
     * обязательность** проверок, реализация каждой остаётся кодом обеих сторон
     * — тот же приём, что у `keywordRegistries`.
     */
    val blockerConstructs: Set<String> = linkedSetOf(
        // определение dunder-метода, кроме __init__
        "dunderDefExceptInit",
        // декоратор на самом классе
        "classDecorator",
        // metaclass= в заголовке класса
        "metaclassInBases",
        // класс С БАЗОЙ, чей __init__ не вызывает super().__init__().
        // Без оговорки про базу конструкт срабатывал бы на корневом классе
        // (core.model.model.Model базы не имеет), а обоснование «базовые
        // self.* не создаются» к безбазовому классу неприменимо.
        "initWithoutSuper",
        // fields в форме, которую сканер не разобрал
        "unparsedFieldsProperty",
        // определение метода внутри условного блока класса: переопределение
        // есть, но действует ли оно — неизвестно
        "conditionalDef",
    )
}

/** Данные для определения категории ключевых слов по контексту свойства `type`. */
/**
 * Правила чтения ресурсов приложения: словарь ключевых слов — свойство не
 * фреймворка, а конкретного навыка. Приложение наследует `SmartAppResources`,
 * дописывает пары «имя из JSON → Python-класс» в методах `init_*`, а активный
 * класс назначает `app_config.py`.
 *
 * Происхождение правила: публичных исходников `smart_kit` в окружении нет,
 * но docstring класса ресурсов в эталонном приложении говорит прямо —
 * «Для использования данных ресурсов присвойте переменной RESOURCES в
 * app_config этот класс как значение».
 *
 * Здесь только имена и раскладка (данные). Разбор Python — алгоритм, он живёт
 * в коде обеих реализаций.
 */
object ResourceScanSpec {

    /** Файл в корне приложения, назначающий активный класс ресурсов. */
    const val configFile: String = "app_config.py"

    /** Переменная в [configFile], хранящая активный класс. */
    const val resourcesVariable: String = "RESOURCES"

    /** Префикс методов класса ресурсов, в которых происходит регистрация. */
    const val methodPrefix: String = "init_"

    const val fileExtension: String = ".py"

    /** Файл пакета: `a.b.c` разрешается в `a/b/c.py`, иначе в `a/b/c/__init__.py`. */
    const val packageInitFile: String = "__init__.py"

    /** Предел длины цепочки наследования — страховка от циклического импорта. */
    const val maxBaseDepth: Int = 8

    /**
     * Сегменты пути, внутрь которых сканер не заходит ни при обходе, ни при
     * разрешении импортов. Виртуальное окружение внутри проекта — не код
     * приложения, а его зависимости.
     */
    val excludedDirs: Set<String> = linkedSetOf(
        "venv",
        ".venv",
        "site-packages",
        "__pycache__",
        "node_modules",
        "build",
        "dist",
    )
}

object TypeContextSpec {

    /**
     * Свойство, значение которого несёт «ключевое слово» DSL. По нему же
     * определяется тип объекта-владельца в ссылочных правилах, поэтому строкой
     * в коде оно стоять не должно.
     */
    val typeProperty: String = "type"

    /**
     * Ключи-контейнеры action-объектов: их `type` принадлежит категории `action`,
     * даже если объект вложен в описание поля (`fields`).
     */
    val actionKeys: Set<String> = linkedSetOf(
        "action", "actions",
        "on_filled_actions", "success_action", "fail_action", "timeout_action",
    )

    /** Категория для любого ключа из [actionKeys]. */
    val actionCategory: String = "action"

    /**
     * Категория по структурному ключу для всех остальных распознаваемых ключей.
     * Раньше это соответствие было `when`-веткой в двух реализациях сразу —
     * теперь оно данные, как и сами имена ключей.
     */
    val keyCategories: Map<String, String> = linkedMapOf(
        "filler" to "filler",
        "classifier" to "classifier",
        "requirement" to "requirement",
        "requirements" to "requirement",
        FieldAccessSpec.fieldsProperty to "field_description",
    )

    /**
     * Переопределение категории для ключей, встреченных внутри описания поля:
     * `requirement` в `fields` — это `field_requirement`, а не requirement
     * уровня сценария.
     */
    val insideFieldsCategories: Map<String, String> = linkedMapOf(
        "requirement" to "field_requirement",
        "requirements" to "field_requirement",
    )

    /** Все структурные ключи, по которым определяется категория. */
    val contextKeys: Set<String> = actionKeys + keyCategories.keys

    /** Категория по виду файла, когда структурный контекст не распознан. */
    val fileKindCategory: Map<SmartAppRefKind, String> = linkedMapOf(
        SmartAppRefKind.SCENARIO to "scenario",
        SmartAppRefKind.FORM to "form_description",
        SmartAppRefKind.ACTION to "action",
        SmartAppRefKind.BEHAVIOR to "action",
        SmartAppRefKind.FILLER to "filler",
        SmartAppRefKind.CLASSIFIER to "classifier",
    )
}

/**
 * Таблицы контракта, собранные в одном месте: их читает рантайм и сериализует
 * экспортёр.
 */
object SmartAppSpecs {

    val kinds: List<KindSpec> = SmartAppRefKind.entries.map { KindSpec(it, it.dirName) }

    /**
     * Замороженная таблица ссылочных правил. Порядок значим только для
     * читаемости снимка: на одно свойство приходится ровно одно правило.
     */
    val refRules: List<RuleSpec> = listOf(
        RuleSpec(
            property = "form",
            ownerTypes = null,
            fileKind = SmartAppRefKind.SCENARIO,
            targets = listOf(SmartAppRefKind.FORM),
        ),
        RuleSpec(
            property = "scenario",
            ownerTypes = setOf("run_scenario"),
            fileKind = null,
            targets = listOf(SmartAppRefKind.SCENARIO),
        ),
        RuleSpec(
            property = "scenario_description",
            ownerTypes = null,
            fileKind = null,
            targets = listOf(SmartAppRefKind.SCENARIO),
        ),
        RuleSpec(
            property = "filler",
            ownerTypes = setOf("external"),
            fileKind = null,
            targets = listOf(SmartAppRefKind.FILLER),
        ),
        RuleSpec(
            // Внешний classifier по имени встречается и в external-обёртке, и в
            // requirement'ах/filler'ах с type == classifier(_meta).
            property = "classifier",
            ownerTypes = setOf("external", "classifier", "classifier_meta"),
            fileKind = null,
            targets = listOf(SmartAppRefKind.CLASSIFIER),
        ),
        RuleSpec(
            property = "action",
            ownerTypes = setOf("external"),
            fileKind = null,
            targets = listOf(SmartAppRefKind.ACTION, SmartAppRefKind.BEHAVIOR),
        ),
        RuleSpec(
            // Behavior-ориентированные actions ссылаются на behavior по имени.
            property = "behavior",
            ownerTypes = setOf("process_behavior", "save_behavior"),
            fileKind = null,
            targets = listOf(SmartAppRefKind.BEHAVIOR),
        ),
    )

    /**
     * Замороженная таблица файловых ссылок.
     *
     * Происхождение правила: в публичных исходниках `smart_app_framework` ключа
     * `file` нет вовсе (проверено grep'ом по всем `*.py` репозитория
     * sberdevices/smart_app_framework — ни `"file"`, ни `FileSystemLoader`).
     * Правило выведено из раскладки эталонного приложения: значение
     * `"experience_items_template.jinja2"` при `"type": "unified_template"`
     * указывает на файл `static/references/templates/experience_items_template.jinja2`.
     * Значение содержит имя файла **вместе с расширением** — расширение
     * контрактом не подставляется.
     */
    val fileRefRules: List<FileRefSpec> = listOf(
        FileRefSpec(
            property = "file",
            ownerTypes = setOf("unified_template"),
            searchDirs = listOf("templates"),
        ),
    )

    /**
     * Реестры фреймворка и категории ключевых слов, которые они наполняют.
     *
     * Одна таблица на три потребителя: генератор словаря
     * (`tools/generate_keywords.py` читает её из снимка `rules.json`), рантайм
     * плагина и ядро расширения — они по ней узнают, какая категория у
     * регистрации в ресурсах приложения. Раньше таблица жила только в
     * генераторе, и рантайму её взять было неоткуда.
     */
    val keywordRegistries: Map<String, String> = linkedMapOf(
        "actions" to "action",
        "requirements" to "requirement",
        "field_filler_description" to "filler",
        "field_requirements" to "field_requirement",
        "scenarios" to "scenario",
        "form_descriptions" to "form_description",
        "field_descriptions" to "field_description",
        "classifiers" to "classifier",
        "operators" to "operator",
        "comparators" to "comparator",
        "answer_items" to "sdk_item",
    )

    /** Структурные ключи DSL, подсвечиваемые как поля. */
    val structuralKeys: Set<String> = linkedSetOf(
        "form",
        "filler",
        "classifier",
        "action",
        "behavior",
        "scenario",
        "scenario_description",
        "actions",
        "requirement",
        "fields",
        "questions",
        "on_filled_actions",
    )
}
