package ru.sber.smartapp.dsl.reference

import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.ResolveResult
import com.intellij.psi.search.GlobalSearchScope
import ru.sber.smartapp.dsl.index.SmartAppDefinitionIndex

/**
 * Ссылка от строкового значения SmartApp DSL к определению(ям) именуемой
 * сущности. Поли-вариантная, потому что некоторые позиции (external `action`)
 * могут резолвиться в action либо behavior, а имя может быть определено
 * несколько раз.
 */
class SmartAppReference(
    element: JsonStringLiteral,
) : PsiPolyVariantReferenceBase<JsonStringLiteral>(element) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val project = element.project
        // Во время индексации индекс недоступен; резолв просто ничего не возвращает.
        if (DumbService.isDumb(project)) return ResolveResult.EMPTY_ARRAY

        val kinds = SmartAppRefRules.targetKinds(element)
        if (kinds.isEmpty()) return ResolveResult.EMPTY_ARRAY

        val name = element.value
        if (name.isEmpty()) return ResolveResult.EMPTY_ARRAY

        val definitions = SmartAppDefinitionIndex.findDefinitions(
            project, name, kinds, GlobalSearchScope.allScope(project),
        )
        if (definitions.isEmpty()) return ResolveResult.EMPTY_ARRAY

        return definitions.map { PsiElementResolveResult(it) }.toTypedArray()
    }

    override fun handleElementRename(newElementName: String): PsiElement {
        // Переименование ссылаемого значения переписывает содержимое строки.
        return super.handleElementRename(newElementName)
    }
}
