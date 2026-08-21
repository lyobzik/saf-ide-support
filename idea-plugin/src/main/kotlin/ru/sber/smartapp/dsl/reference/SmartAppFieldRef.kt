package ru.sber.smartapp.dsl.reference

import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.psi.PsiElement
import ru.sber.smartapp.dsl.SmartAppFiles
import ru.sber.smartapp.dsl.SmartAppRefKind
import ru.sber.smartapp.dsl.contract.FieldAccessSpec

/**
 * Идентификатор поля формы: пара (форма, поле). FIELD — отдельное понятие, а не
 * расширение [SmartAppRefKind]: у полей нет своего каталога, они живут внутри
 * top-level определения формы (`forms.<form>.fields.<field>`), и многие контракты
 * (`SmartAppFiles.kindOf`, scope, `isTopLevelDefinition`) к ним не применимы.
 */
data class FormFieldRef(val form: String, val field: String)

/**
 * Определяет целевую форму Jinja-ссылки и опознаёт определения полей для
 * Find Usages.
 *
 * Целевая форма зависит от вида файла:
 * - в **сценарии** её называет top-level свойство `form` того же сценария.
 *   Поиск sibling `form` у непосредственного владельца literal был бы ошибочен:
 *   Jinja обычно стоит во вложенном action/field/question-объекте, а `form` —
 *   в top-level объекте сценария;
 * - в **файле формы** `main_form` — это сама форма, внутри определения которой
 *   стоит literal: отдельного указателя на неё в файле нет.
 */
object SmartAppFieldRef {

    /**
     * Имя целевой формы для Jinja-ссылки в [literal] или `null`, если форма
     * неизвестна. Для сценария имя берётся только из статической строковой
     * `form` без Jinja: динамический выбор формы (`"form": "{{ main_form.name }}"`)
     * не даёт однозначной цели, и поле в нём не резолвится и не помечается как
     * ошибка.
     *
     * [fileKind] — вид файла; по умолчанию определяется по [literal.containingFile].
     * Параметр нужен для автодополнения, которое работает на in-memory копии,
     * теряющей реальный путь (kind в копии не определить).
     */
    fun targetFormOf(
        literal: JsonStringLiteral,
        fileKind: SmartAppRefKind? = SmartAppFiles.kindOf(literal.containingFile),
    ): String? {
        val topLevel = topLevelProperty(literal) ?: return null
        return when (fileKind) {
            SmartAppRefKind.SCENARIO -> formOfScenario(topLevel)
            // Внутри файла формы main_form — это само top-level определение.
            SmartAppRefKind.FORM -> topLevel.name.ifEmpty { null }
            else -> null
        }
    }

    /** Форма, названная свойством `form` top-level объекта сценария. */
    private fun formOfScenario(scenarioProp: JsonProperty): String? {
        val scenarioObj = scenarioProp.value as? JsonObject ?: return null
        val formProp = scenarioObj.findProperty(FieldAccessSpec.formProperty) ?: return null
        val formLiteral = formProp.value as? JsonStringLiteral ?: return null
        val formName = formLiteral.value
        if (formName.isEmpty()) return null
        // Динамическая форма не даёт однозначной цели — пропускаем без ошибки.
        if (SmartAppReferenceContributor.isJinja(formName)) return null
        return formName
    }

    /**
     * `true`, если [property] — определение поля формы: прямой ребёнок объекта
     * `fields`, который принадлежит top-level определению формы в FORM-файле.
     * Используется Find Usages для поля.
     */
    fun isFieldDefinition(property: JsonProperty): Boolean {
        val fieldsObj = property.parent as? JsonObject ?: return false
        val fieldsProp = fieldsObj.parent as? JsonProperty ?: return false
        if (fieldsProp.name != FieldAccessSpec.fieldsProperty) return false
        val formObj = fieldsProp.parent as? JsonObject ?: return false
        val formProp = formObj.parent as? JsonProperty ?: return false
        if (formProp.parent.parent !is JsonFile) return false
        return SmartAppFiles.kindOf(property.containingFile) == SmartAppRefKind.FORM
    }

    /** Поднимается от [start] до top-level свойства-определения файла. */
    private fun topLevelProperty(start: PsiElement): JsonProperty? {
        var current: PsiElement? = start
        // Поднимаемся по родителям, пока не найдём JsonProperty, чей
        // владелец-объект — корневой объект файла.
        while (current != null) {
            val prop = current as? JsonProperty
            if (prop != null) {
                val owner = prop.parent as? JsonObject
                if (owner != null && owner.parent is JsonFile) {
                    return prop
                }
            }
            current = current.parent
        }
        return null
    }
}
