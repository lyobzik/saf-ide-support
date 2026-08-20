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
    const val VERSION: Int = 2
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
}

/** Данные для определения категории ключевых слов по контексту свойства `type`. */
object TypeContextSpec {

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
