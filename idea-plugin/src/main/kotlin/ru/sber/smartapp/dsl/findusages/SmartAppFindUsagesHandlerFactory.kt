package ru.sber.smartapp.dsl.findusages

import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.psi.PsiElement

/**
 * Подключает поиск использований для ключевых слов, зарегистрированных
 * приложением.
 *
 * Обычный `lang.findUsagesProvider` тут не работает: он объявлен для языка
 * JSON, а цель ссылки — [ru.sber.smartapp.dsl.resources.SmartAppRegistrationElement],
 * элемент вне какого-либо языка. Поэтому направление объявляется фабрикой,
 * которая сама разбирает, что лежит под кареткой
 * ([SmartAppCustomKeywordTargets]).
 */
class SmartAppFindUsagesHandlerFactory : FindUsagesHandlerFactory() {

    override fun canFindUsages(element: PsiElement): Boolean =
        SmartAppCustomKeywordTargets.of(element) != null

    override fun createFindUsagesHandler(element: PsiElement, forHighlightUsages: Boolean): FindUsagesHandler? {
        val target = SmartAppCustomKeywordTargets.of(element) ?: return null
        return SmartAppCustomKeywordUsagesHandler(target)
    }
}
