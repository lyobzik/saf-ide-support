package ru.sber.smartapp.dsl.reference

import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import ru.sber.smartapp.dsl.SmartAppFiles
import ru.sber.smartapp.dsl.SmartAppRefKind
import ru.sber.smartapp.dsl.contract.SmartAppSpecs

/**
 * Применяет замороженную таблицу кросс-ссылок [SmartAppSpecs.refRules] к PSI:
 * сопоставляет имя свойства, значение `type` у объекта-владельца и вид файла с
 * видом(ами) сущности, на которые ссылается строковое значение.
 *
 * Сами правила — данные контракта, а не код: их же сериализует `exportRules` в
 * `shared/rules/rules.json`, откуда их читает VS Code-расширение. Здесь остаётся
 * только алгоритм сопоставления.
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

        val rule = SmartAppSpecs.refRules.firstOrNull { it.property == property.name }
            ?: return emptyList()
        if (rule.fileKind != null && rule.fileKind != fileKind) return emptyList()
        if (rule.ownerTypes != null && ownerType(owner) !in rule.ownerTypes) return emptyList()
        return rule.targets
    }

    /** True, если [literal] стоит в любой ссылочной позиции. */
    fun isReference(literal: JsonStringLiteral): Boolean = targetKinds(literal).isNotEmpty()

    private fun ownerType(owner: JsonObject): String? {
        val typeValue = owner.findProperty("type")?.value as? JsonStringLiteral
        return typeValue?.value
    }
}
