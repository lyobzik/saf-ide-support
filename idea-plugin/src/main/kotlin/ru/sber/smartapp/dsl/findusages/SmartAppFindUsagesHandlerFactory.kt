package ru.sber.smartapp.dsl.findusages

import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.psi.PsiElement

/**
 * Подключает поиск использований для элементов, объявленных в Python-коде
 * приложения: ключевых слов и атрибутов модели пользователя.
 *
 * Обычный `lang.findUsagesProvider` тут не работает: он объявлен для языка
 * JSON, а цели — [ru.sber.smartapp.dsl.resources.SmartAppRegistrationElement] и
 * [ru.sber.smartapp.dsl.resources.SmartAppUserFieldElement], элементы вне
 * какого-либо языка. Поэтому направление объявляется фабрикой, которая сама
 * разбирает, что лежит под кареткой.
 *
 * Диспетчеризация — по списку разборщиков цели, а не цепочкой `if`: класс
 * модели пользователя ([ru.sber.smartapp.dsl.resources.SmartAppUserClassElement])
 * в списке отсутствует намеренно, и на нём фабрика обязана отвечать `false` —
 * имени класса в тексте DSL нет, вхождений у него не бывает (план, раздел 9).
 */
class SmartAppFindUsagesHandlerFactory : FindUsagesHandlerFactory() {

    override fun canFindUsages(element: PsiElement): Boolean = handlerFor(element) != null

    override fun createFindUsagesHandler(
        element: PsiElement,
        forHighlightUsages: Boolean,
    ): FindUsagesHandler? = handlerFor(element)

    private fun handlerFor(element: PsiElement): FindUsagesHandler? {
        SmartAppCustomKeywordTargets.of(element)?.let { return SmartAppCustomKeywordUsagesHandler(it) }
        SmartAppUserFieldTargets.of(element)?.let { return SmartAppUserFieldUsagesHandler(it) }
        return null
    }
}
