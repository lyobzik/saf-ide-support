package ru.sber.smartapp.dsl.findusages

import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.psi.ElementDescriptionLocation
import com.intellij.psi.ElementDescriptionProvider
import com.intellij.psi.PsiElement
import com.intellij.usageView.UsageViewLongNameLocation
import com.intellij.usageView.UsageViewTypeLocation
import ru.sber.smartapp.dsl.contract.FieldAccessSpec
import ru.sber.smartapp.dsl.reference.SmartAppFieldRef

/**
 * Человекочитаемые подписи определений SmartApp DSL в окне Find Usages:
 *  - top-level сущность: тип «scenario»/«form»/… и имя «hello_scenario»;
 *  - поле формы: тип «field» и подпись «<form>.<field>» (например «hello_form.name»).
 */
class SmartAppElementDescriptionProvider : ElementDescriptionProvider {

    override fun getElementDescription(
        element: PsiElement,
        location: ElementDescriptionLocation,
    ): String? {
        val property = element as? JsonProperty ?: return null

        // Поле формы: свой формат подписи.
        if (SmartAppFieldRef.isFieldDefinition(property)) {
            return when (location) {
                is UsageViewTypeLocation -> "field"
                is UsageViewLongNameLocation -> formQualifiedName(property) ?: property.name
                else -> null
            }
        }

        // Top-level определение: подпись по kind.
        val kind = SmartAppFindUsagesProvider.kindOf(property) ?: return null
        return when (location) {
            is UsageViewTypeLocation -> kind.name.lowercase()
            is UsageViewLongNameLocation -> property.name
            else -> null
        }
    }

    /** Полное имя поля формы: `"<form>.<field>"`. */
    private fun formQualifiedName(fieldProperty: JsonProperty): String? {
        // field -> fields-object -> JsonProperty("fields") -> form-object -> JsonProperty(form) -> name
        val fieldsObj = fieldProperty.parent as? JsonObject ?: return null
        val fieldsProp = fieldsObj.parent as? JsonProperty ?: return null
        if (fieldsProp.name != FieldAccessSpec.fieldsProperty) return null
        val formObj = fieldsProp.parent as? JsonObject ?: return null
        val formProp = formObj.parent as? JsonProperty ?: return null
        return "${formProp.name}.${fieldProperty.name}"
    }
}
