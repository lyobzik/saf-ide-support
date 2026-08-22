package ru.sber.smartapp.dsl.resources

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.FakePsiElement
import com.intellij.util.IncorrectOperationException
import ru.sber.smartapp.dsl.resources.SmartAppResourceResolver.CustomKeyword

/**
 * Строка регистрации ключевого слова в Python-файле приложения как цель
 * навигации и Find Usages.
 *
 * Настоящего PSI у этой строки может не быть вовсе: без плагина Python `.py` —
 * это plain text, где нет ни литерала, ни выражения. Поэтому цель моделируется
 * fake-элементом: он знает файл и сырой диапазон имени, чего хватает и для
 * перехода, и для описания цели в интерфейсе поиска.
 *
 * Переименование не поддерживается: имя живёт в Python-коде и в значениях
 * `type` всех JSON-файлов приложения, и переписать их согласованно мы не умеем.
 * [setName] обязан существовать — его требует `PsiNamedElement`, — поэтому он
 * бросает [IncorrectOperationException] с объяснением, а не молчит.
 */
class SmartAppRegistrationElement(
    private val file: PsiFile,
    val keyword: CustomKeyword,
) : FakePsiElement() {

    override fun getParent(): PsiElement = file

    override fun getContainingFile(): PsiFile = file

    override fun getName(): String = keyword.name

    override fun setName(name: String): PsiElement =
        throw IncorrectOperationException(
            "Ключевое слово '${keyword.name}' зарегистрировано в Python-коде приложения; " +
                "переименование не поддерживается",
        )

    /** Сырой диапазон имени — без кавычек, как у всех ссылок плагина. */
    override fun getTextRange(): TextRange = TextRange(keyword.nameStart, keyword.nameEnd)

    override fun getTextOffset(): Int = keyword.nameStart

    /**
     * Текст берётся из файла, а не из имени: имя декодировано
     * (`"custom\u0041"` -> `customA`), а `getText` обязан совпадать с
     * [getTextRange] — иначе платформа считает длину по одному, а показывает
     * другое.
     */
    override fun getText(): String {
        val text = file.text
        val end = keyword.nameEnd
        return if (end <= text.length) text.substring(keyword.nameStart, end) else keyword.name
    }

    override fun getTextLength(): Int = keyword.nameEnd - keyword.nameStart

    override fun canNavigate(): Boolean = file.virtualFile != null

    override fun canNavigateToSource(): Boolean = canNavigate()

    override fun navigate(requestFocus: Boolean) {
        val virtualFile = file.virtualFile ?: return
        OpenFileDescriptor(file.project, virtualFile, keyword.nameStart).navigate(requestFocus)
    }

    /** Подпись цели: имя и класс, который за ним стоит. */
    override fun getPresentableText(): String = keyword.name

    override fun getLocationString(): String = keyword.className ?: keyword.file

    /**
     * Элементы создаются заново на каждый резолв, поэтому равенство —
     * структурное: платформа сравнивает цели (например, отсеивая дубликаты в
     * списке использований), и две ссылки на одну регистрацию обязаны совпасть.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        val that = other as? SmartAppRegistrationElement ?: return false
        return file == that.file && keyword == that.keyword
    }

    override fun hashCode(): Int = 31 * file.hashCode() + keyword.hashCode()
}
