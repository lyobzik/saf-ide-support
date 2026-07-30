package ru.sber.smartapp.dsl.reference

import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.ResolveResult
import ru.sber.smartapp.dsl.SmartAppScopes
import ru.sber.smartapp.dsl.index.SmartAppFormFieldIndex

/**
 * Ссылка от Jinja-фрагмента `{{ main_form.<field> }}` к определению(ям) поля формы.
 * Поли-вариантная, как [SmartAppReference]: дубли имени поля в одной форме дают
 * несколько результатов.
 *
 * **Диапазон ссылки — только на имя поля** (а не на весь `main_form.<field>`):
 * это даёт корректные Go to Definition, Find Usages и будущий rename поля.
 * Диапазон задан в координатах элемента (raw-offset имени поля внутри строкового
 * литерала + 1 за открывающую JSON-кавычку).
 */
class SmartAppFieldReference(
    element: JsonStringLiteral,
    fieldRangeInElement: TextRange,
    val ref: FormFieldRef,
) : PsiPolyVariantReferenceBase<JsonStringLiteral>(element, fieldRangeInElement) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val project = element.project
        // Во время индексации индекс недоступен; резолв просто ничего не возвращает.
        if (DumbService.isDumb(project)) return ResolveResult.EMPTY_ARRAY

        val fields = SmartAppFormFieldIndex.findFields(
            project, ref.form, ref.field, SmartAppScopes.forElement(element),
        )
        if (fields.isEmpty()) return ResolveResult.EMPTY_ARRAY
        return fields.map { PsiElementResolveResult(it) }.toTypedArray()
    }
}
