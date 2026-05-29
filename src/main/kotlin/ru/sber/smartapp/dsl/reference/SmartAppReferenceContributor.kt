package ru.sber.smartapp.dsl.reference

import com.intellij.json.psi.JsonStringLiteral
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.util.ProcessingContext
import ru.sber.smartapp.dsl.SmartAppFiles

/**
 * Навешивает [SmartAppReference] на строковые JSON-литералы, стоящие в ссылочной
 * позиции SmartApp (согласно [SmartAppRefRules]). Jinja-шаблонные значения
 * (`{{ ... }}` / `{% ... %}`) полностью пропускаются: они не резолвятся и не
 * помечаются как неразрешённые.
 */
class SmartAppReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(JsonStringLiteral::class.java),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(
                    element: PsiElement,
                    context: ProcessingContext,
                ): Array<PsiReference> {
                    val literal = element as? JsonStringLiteral ?: return PsiReference.EMPTY_ARRAY
                    if (SmartAppFiles.kindOf(literal.containingFile) == null) return PsiReference.EMPTY_ARRAY
                    if (isJinja(literal.value)) return PsiReference.EMPTY_ARRAY
                    if (!SmartAppRefRules.isReference(literal)) return PsiReference.EMPTY_ARRAY
                    return arrayOf(SmartAppReference(literal))
                }
            },
        )
    }

    companion object {
        fun isJinja(text: String): Boolean = text.contains("{{") || text.contains("{%")
    }
}
