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
 * Семантика `{{ main_form.<field> }}`: целевая форма — это форма, на которую
 * ссылается **top-level свойство `form` того же сценария**, в котором встретился
 * literal. Поиск sibling `form` у непосредственного владельца literal был бы
 * ошибочен: Jinja обычно стоит во вложенном action/field/question-объекте, а
 * `form` находится в top-level объекте сценария.
 */
object SmartAppFieldRef {

    /**
     * Имя целевой формы для Jinja-ссылки в [literal] или `null`, если форма
     * неизвестна. Возвращает имя только для статической строковой `form` без
     * Jinja: динамический выбор формы (`"form": "{{ main_form.name }}"`) не даёт
     * однозначной цели, и поле в нём не резолвится и не помечается как ошибка.
     *
     * [fileKind] — вид файла; по умолчанию определяется по [literal.containingFile].
     * Параметр нужен для автодополнения, которое работает на in-memory копии,
     * теряющей реальный путь (kind в копии не определить).
     */
    fun targetFormOf(
        literal: JsonStringLiteral,
        fileKind: SmartAppRefKind? = SmartAppFiles.kindOf(literal.containingFile),
    ): String? {
        val scenarioProp = topLevelScenarioProperty(literal, fileKind) ?: return null
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

    /**
     * Поднимается от [start] до top-level свойства-определения сценария, если
     * файл — сценарий ([fileKind]). Иначе `null`.
     */
    private fun topLevelScenarioProperty(start: PsiElement, fileKind: SmartAppRefKind?): JsonProperty? {
        if (fileKind != SmartAppRefKind.SCENARIO) return null
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
