package ru.sber.smartapp.dsl.reference

import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.ResolveResult
import ru.sber.smartapp.dsl.resources.SmartAppUserModel

/**
 * Ссылка от `<корень>.<имя>` внутри Jinja к строкам, где приложение объявило
 * этот атрибут модели пользователя.
 *
 * Мягкая (`soft`) и поли-вариантная. Мягкая — потому что имя может прийти из
 * снимка фреймворка, исходников которого в проекте нет: «неразрешённая ссылка»
 * здесь не ошибка, о настоящих ошибках сообщает аннотатор по своему гейту.
 * Поли-вариантная — одно имя бывает объявлено дважды разными формами
 * (`Field("x", …)` в `fields` и `self.x = …` в `__init__`), и обе строки —
 * действующие цели.
 *
 * Индекс не читается вовсе, поэтому dumb-guard не нужен: словарь модели
 * собирается по VFS и доступен во время индексации.
 */
class SmartAppUserFieldReference(
    element: JsonStringLiteral,
    rangeInElement: TextRange,
    private val attributeName: String,
) : PsiPolyVariantReferenceBase<JsonStringLiteral>(element, rangeInElement, /* soft = */ true) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val file = element.containingFile?.originalFile ?: return ResolveResult.EMPTY_ARRAY
        return SmartAppUserModel.declarations(file, attributeName)
            .map { PsiElementResolveResult(it) }
            .toTypedArray()
    }

    /**
     * Переименование не поддерживается: [SmartAppUserFieldElement] отказывает в
     * `setName`, поэтому строку значения тоже никто не переписывает.
     */
    override fun handleElementRename(newElementName: String): PsiElement = element
}
