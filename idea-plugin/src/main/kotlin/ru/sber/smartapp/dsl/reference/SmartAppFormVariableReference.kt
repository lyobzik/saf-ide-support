package ru.sber.smartapp.dsl.reference

import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.ResolveResult
import ru.sber.smartapp.dsl.SmartAppRefKind
import ru.sber.smartapp.dsl.SmartAppScopes
import ru.sber.smartapp.dsl.index.SmartAppDefinitionIndex

/**
 * Ссылка от переменной `main_form` внутри Jinja к определению целевой формы.
 *
 * Имя формы в тексте не написано — оно вычислено по контексту
 * ([SmartAppFieldRef.targetFormOf]), поэтому:
 * - переименование формы эту строку **не** переписывает (`main_form` — не имя
 *   формы, а её псевдоним);
 * - неразрешённая форма здесь не помечается: о ней уже сообщает диагностика на
 *   самом значении `form`, второе предупреждение было бы шумом.
 */
class SmartAppFormVariableReference(
    element: JsonStringLiteral,
    rangeInElement: TextRange,
    private val form: String,
) : PsiPolyVariantReferenceBase<JsonStringLiteral>(element, rangeInElement, /* soft = */ true) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val project = element.project
        // Во время индексации индекс недоступен; резолв просто молчит.
        if (DumbService.isDumb(project)) return ResolveResult.EMPTY_ARRAY

        val definitions = SmartAppDefinitionIndex.findDefinitions(
            project, form, listOf(SmartAppRefKind.FORM), SmartAppScopes.forElement(element),
        )
        return definitions.map { PsiElementResolveResult(it) }.toTypedArray()
    }

    /**
     * Переименование формы не должно превращать `main_form` в её новое имя.
     * Сегодня платформа сюда и не доходит (использования ищутся по слову
     * `<имя формы>`, которого в строке нет), поэтому override — страховка на
     * случай другого поиска, а не единственная защита.
     */
    override fun handleElementRename(newElementName: String): PsiElement = element
}
