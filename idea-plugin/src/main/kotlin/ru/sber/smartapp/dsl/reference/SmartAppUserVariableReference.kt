package ru.sber.smartapp.dsl.reference

import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.ResolveResult
import ru.sber.smartapp.dsl.resources.SmartAppUserModel

/**
 * Ссылка с корневой переменной модели пользователя на класс, назначенный
 * `USER`.
 *
 * Цели нет, если активный класс библиотечный: цепочка приложения пуста, идти
 * некуда. Ссылка остаётся мягкой и не подсвечивается — то же поведение, что у
 * `main_form` при неизвестной форме.
 */
class SmartAppUserVariableReference(
    element: JsonStringLiteral,
    rangeInElement: TextRange,
) : PsiPolyVariantReferenceBase<JsonStringLiteral>(element, rangeInElement, /* soft = */ true) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val file = element.containingFile?.originalFile ?: return ResolveResult.EMPTY_ARRAY
        val cls = SmartAppUserModel.userClass(file) ?: return ResolveResult.EMPTY_ARRAY
        return arrayOf(PsiElementResolveResult(cls))
    }

    /**
     * Корневое имя — псевдоним из параметризатора, а не имя класса:
     * переименование класса эту строку переписывать не должно.
     */
    override fun handleElementRename(newElementName: String): PsiElement = element
}
