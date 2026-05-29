package ru.sber.smartapp.dsl.reference

import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import ru.sber.smartapp.dsl.SmartAppFiles
import ru.sber.smartapp.dsl.SmartAppRefKind

/**
 * Замороженная таблица правил кросс-ссылок. Каждое правило сопоставляет точное
 * PSI-условие (имя свойства, значение `type` у объекта-владельца и вид файла) с
 * видом(ами) сущности, на которые ссылается строковое значение.
 *
 * Централизация правил держит в согласии контрибьютор ссылок, аннотатор
 * неразрешённых ссылок и автодополнение.
 */
object SmartAppRefRules {

    /**
     * Возвращает целевые виды для [literal], если он стоит в ссылочной позиции,
     * иначе — пустой список. Jinja-шаблонные значения отсекаются вызывающим до
     * резолва; этот метод не анализирует содержимое текста.
     */
    fun targetKinds(
        literal: JsonStringLiteral,
        fileKind: SmartAppRefKind? = SmartAppFiles.kindOf(literal.containingFile),
    ): List<SmartAppRefKind> {
        val property = literal.parent as? JsonProperty ?: return emptyList()
        // Литерал должен быть *значением* свойства, а не его ключом.
        if (property.value !== literal) return emptyList()

        val owner = property.parent as? JsonObject ?: return emptyList()

        return when (property.name) {
            "form" ->
                if (fileKind == SmartAppRefKind.SCENARIO) listOf(SmartAppRefKind.FORM) else emptyList()

            "scenario" ->
                if (ownerType(owner) == "run_scenario") listOf(SmartAppRefKind.SCENARIO) else emptyList()

            "scenario_description" ->
                listOf(SmartAppRefKind.SCENARIO)

            "filler" ->
                if (ownerType(owner) == "external") listOf(SmartAppRefKind.FILLER) else emptyList()

            "classifier" ->
                if (ownerType(owner) == "external") listOf(SmartAppRefKind.CLASSIFIER) else emptyList()

            "action" ->
                if (ownerType(owner) == "external")
                    listOf(SmartAppRefKind.ACTION, SmartAppRefKind.BEHAVIOR)
                else emptyList()

            else -> emptyList()
        }
    }

    /** True, если [literal] стоит в любой ссылочной позиции. */
    fun isReference(literal: JsonStringLiteral): Boolean = targetKinds(literal).isNotEmpty()

    private fun ownerType(owner: JsonObject): String? {
        val typeValue = owner.findProperty("type")?.value as? JsonStringLiteral
        return typeValue?.value
    }
}
