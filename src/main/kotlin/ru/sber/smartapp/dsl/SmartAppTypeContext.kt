package ru.sber.smartapp.dsl

import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty

/**
 * Определяет категорию ключевых слов для значения свойства `type` по контексту
 * в PSI-дереве. Единый источник правды для автодополнения значений `type` и для
 * контекстной подсветки (аннотатор), чтобы, например, тип filler'а не
 * подсвечивался как валидный тип сценария.
 *
 * Категория ищется подъёмом по объемлющим объектам до первого распознаваемого
 * структурного ключа; `requirement` внутри описания поля (`fields`) трактуется
 * как `field_requirement`, а не как scenario/action-level `requirement`.
 */
object SmartAppTypeContext {

    // Ключи-контейнеры action-объектов: их type принадлежит категории action,
    // даже если они вложены в описание поля (fields).
    private val ACTION_KEYS = setOf(
        "action", "actions",
        "on_filled_actions", "success_action", "fail_action", "timeout_action",
    )

    private val CONTEXT_KEYS = ACTION_KEYS + setOf(
        "filler", "classifier",
        "requirement", "requirements", "fields",
    )

    /**
     * Категория словаря для значения `type` свойства [typeProperty] в файле вида
     * [fileKind], либо `null`, если контекст не распознан.
     */
    fun categoryFor(typeProperty: JsonProperty, fileKind: SmartAppRefKind?): String? {
        val owner = typeProperty.parent as? JsonObject ?: return fileKindCategory(fileKind)
        val keys = enclosingKeys(owner)
        val insideFields = "fields" in keys

        for (key in keys) {
            when {
                key == "filler" -> return "filler"
                key == "classifier" -> return "classifier"
                key in ACTION_KEYS -> return "action"
                key == "requirement" || key == "requirements" ->
                    return if (insideFields) "field_requirement" else "requirement"
                key == "fields" -> return "field_description"
            }
        }
        return fileKindCategory(fileKind)
    }

    /** Категория по виду файла, когда структурный контекст не распознан. */
    fun fileKindCategory(kind: SmartAppRefKind?): String? = when (kind) {
        SmartAppRefKind.SCENARIO -> "scenario"
        SmartAppRefKind.FORM -> "form_description"
        SmartAppRefKind.ACTION, SmartAppRefKind.BEHAVIOR -> "action"
        SmartAppRefKind.FILLER -> "filler"
        SmartAppRefKind.CLASSIFIER -> "classifier"
        null -> null
    }

    /**
     * Имена распознаваемых структурных ключей, под которыми лежит [start] и его
     * объемлющие объекты, от ближнего к дальнему.
     */
    private fun enclosingKeys(start: JsonObject): List<String> {
        val keys = ArrayList<String>()
        var obj: JsonObject? = start
        var guard = 0
        while (obj != null && guard++ < 100) {
            val holder = obj.parent
            val keyName = when (holder) {
                is JsonProperty -> holder.name
                is JsonArray -> (holder.parent as? JsonProperty)?.name
                else -> null
            }
            if (keyName != null && keyName in CONTEXT_KEYS) keys.add(keyName)

            val holderProperty = when (holder) {
                is JsonProperty -> holder
                is JsonArray -> holder.parent as? JsonProperty
                else -> null
            } ?: break
            obj = holderProperty.parent as? JsonObject
        }
        return keys
    }
}
