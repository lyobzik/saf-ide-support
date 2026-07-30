package ru.sber.smartapp.dsl.findusages

import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.psi.PsiElement
import ru.sber.smartapp.dsl.SmartAppFiles
import ru.sber.smartapp.dsl.SmartAppRefKind
import ru.sber.smartapp.dsl.reference.SmartAppFieldRef

/**
 * Включает Find Usages для определений сущностей SmartApp DSL (верхнеуровневый
 * [JsonProperty] в reference-файле) и для определений полей формы
 * (`forms.<form>.fields.<field>`) — последние нужны для поиска использований
 * имён полей внутри Jinja `{{ main_form.<field> }}`. Элементы, не являющиеся
 * ни тем, ни другим, отклоняются — их обрабатывает встроенный JSON-провайдер.
 */
class SmartAppFindUsagesProvider : FindUsagesProvider {

    override fun getWordsScanner(): WordsScanner? = null

    override fun canFindUsagesFor(element: PsiElement): Boolean =
        element is JsonProperty && (isDslDefinition(element) || SmartAppFieldRef.isFieldDefinition(element))

    override fun getHelpId(element: PsiElement): String? = null

    override fun getType(element: PsiElement): String {
        val prop = element as? JsonProperty ?: return "entity"
        return when {
            isDslDefinition(prop) -> kindOf(prop)?.name?.lowercase() ?: "entity"
            SmartAppFieldRef.isFieldDefinition(prop) -> "field"
            else -> "entity"
        }
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

        /**
         * Вид top-level определения (для подписей Find Usages), либо `null` для
         * прочих элементов (включая поля формы — у них своя подпись «field»).
         */
        fun kindOf(property: JsonProperty): SmartAppRefKind? =
            if (isDslDefinition(property)) SmartAppFiles.kindOf(property.containingFile) else null
    }
}
