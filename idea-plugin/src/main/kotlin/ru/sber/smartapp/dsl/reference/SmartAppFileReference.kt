package ru.sber.smartapp.dsl.reference

import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReferenceBase

/**
 * Ссылка от значения `file` к файлу шаблона. В отличие от [SmartAppReference]
 * целью служит `PsiFile` целиком, а не top-level ключ, поэтому и резолв идёт по
 * файловой системе, а не по индексу определений.
 *
 * Диапазон — содержимое литерала без кавычек: подсвечивается и переименовывается
 * именно имя файла.
 */
class SmartAppFileReference(
    element: JsonStringLiteral,
) : PsiReferenceBase<JsonStringLiteral>(element, contentRange(element), /* soft = */ true) {

    override fun resolve(): PsiElement? {
        val file = SmartAppFileRefRules.resolve(element) ?: return null
        return element.manager.findFile(file)
    }

    private companion object {
        /** Диапазон содержимого литерала в координатах элемента (+1 за кавычку). */
        fun contentRange(literal: JsonStringLiteral): TextRange {
            val length = literal.textLength
            // Незакрытая строка: кавычка только слева.
            val end = if (length >= 2) length - 1 else length
            return TextRange(1, maxOf(1, end))
        }
    }
}
