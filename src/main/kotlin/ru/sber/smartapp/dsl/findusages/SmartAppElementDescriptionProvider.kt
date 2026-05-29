package ru.sber.smartapp.dsl.findusages

import com.intellij.json.psi.JsonProperty
import com.intellij.psi.ElementDescriptionLocation
import com.intellij.psi.ElementDescriptionProvider
import com.intellij.psi.PsiElement
import com.intellij.usageView.UsageViewLongNameLocation
import com.intellij.usageView.UsageViewTypeLocation

/**
 * Человекочитаемые подписи определений SmartApp DSL в окне Find Usages,
 * например, тип «scenario» и имя «hello_scenario».
 */
class SmartAppElementDescriptionProvider : ElementDescriptionProvider {

    override fun getElementDescription(
        element: PsiElement,
        location: ElementDescriptionLocation,
    ): String? {
        val property = element as? JsonProperty ?: return null
        val kind = SmartAppFindUsagesProvider.kindOf(property) ?: return null

        return when (location) {
            is UsageViewTypeLocation -> kind.name.lowercase()
            is UsageViewLongNameLocation -> property.name
            else -> null
        }
    }
}
