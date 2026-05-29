package ru.sber.smartapp.dsl.findusages

import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.psi.PsiElement
import ru.sber.smartapp.dsl.SmartAppFiles
import ru.sber.smartapp.dsl.SmartAppRefKind

/**
 * Включает Find Usages для определений сущностей SmartApp DSL (верхнеуровневый
 * [JsonProperty] в reference-файле) и задаёт их подписи вида/имени. Элементы, не
 * являющиеся определениями, отклоняются — их обрабатывает встроенный
 * JSON-провайдер.
 */
class SmartAppFindUsagesProvider : FindUsagesProvider {

    override fun getWordsScanner(): WordsScanner? = null

    override fun canFindUsagesFor(element: PsiElement): Boolean =
        element is JsonProperty && isDslDefinition(element)

    override fun getHelpId(element: PsiElement): String? = null

    override fun getType(element: PsiElement): String {
        val kind = (element as? JsonProperty)?.let { kindOf(it) }
        return kind?.name?.lowercase() ?: "entity"
    }

    override fun getDescriptiveName(element: PsiElement): String =
        (element as? JsonProperty)?.name ?: ""

    override fun getNodeText(element: PsiElement, useFullName: Boolean): String =
        (element as? JsonProperty)?.name ?: ""

    companion object {
        fun isDslDefinition(property: JsonProperty): Boolean {
            if (SmartAppFiles.kindOf(property.containingFile) == null) return false
            val owner = property.parent as? JsonObject ?: return false
            return owner.parent is JsonFile
        }

        fun kindOf(property: JsonProperty): SmartAppRefKind? =
            if (isDslDefinition(property)) SmartAppFiles.kindOf(property.containingFile) else null
    }
}
