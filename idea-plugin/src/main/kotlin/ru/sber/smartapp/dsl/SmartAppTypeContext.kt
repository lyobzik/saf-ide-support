package ru.sber.smartapp.dsl

import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import ru.sber.smartapp.dsl.contract.FieldAccessSpec
import ru.sber.smartapp.dsl.contract.TypeContextSpec

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

    // Наборы ключей и соответствие «вид файла -> категория» — данные контракта
    // (TypeContextSpec), здесь остаётся только алгоритм подъёма по PSI.
    private val ACTION_KEYS = TypeContextSpec.actionKeys

    private val CONTEXT_KEYS = TypeContextSpec.contextKeys

    /**
     * Категория словаря для значения `type` свойства [typeProperty] в файле вида
     * [fileKind], либо `null`, если контекст не распознан.
     */
    fun categoryFor(typeProperty: JsonProperty, fileKind: SmartAppRefKind?): String? {
        val owner = typeProperty.parent as? JsonObject ?: return fileKindCategory(fileKind)
        val keys = enclosingKeys(owner)
        val insideFields = FieldAccessSpec.fieldsProperty in keys

        for (key in keys) {
            if (key in ACTION_KEYS) return TypeContextSpec.actionCategory
            if (insideFields) {
                TypeContextSpec.insideFieldsCategories[key]?.let { return it }
            }
            TypeContextSpec.keyCategories[key]?.let { return it }
        }
        return fileKindCategory(fileKind)
    }

    /** Категория по виду файла, когда структурный контекст не распознан. */
    fun fileKindCategory(kind: SmartAppRefKind?): String? =
        kind?.let { TypeContextSpec.fileKindCategory[it] }

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
