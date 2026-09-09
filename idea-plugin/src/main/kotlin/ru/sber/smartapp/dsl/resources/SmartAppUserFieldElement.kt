package ru.sber.smartapp.dsl.resources

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.FakePsiElement
import com.intellij.util.IncorrectOperationException
import ru.sber.smartapp.dsl.resources.SmartAppUserModelResolver.DeclarationSite

/**
 * Строка объявления атрибута модели пользователя в Python-файле приложения как
 * цель навигации и Find Usages.
 *
 * Устроена так же и по тем же причинам, что [SmartAppRegistrationElement]:
 * настоящего PSI у строки может не быть вовсе (без плагина Python `.py` — это
 * plain text), поэтому цель моделируется fake-элементом с файлом и сырым
 * диапазоном имени.
 *
 * Переименование не поддерживается: имя живёт в Python-коде и в Jinja-выражениях
 * всех DSL-файлов приложения, и переписать их согласованно мы не умеем.
 */
class SmartAppUserFieldElement(
    private val file: PsiFile,
    val attributeName: String,
    val site: DeclarationSite,
) : FakePsiElement() {

    override fun getParent(): PsiElement = file

    override fun getContainingFile(): PsiFile = file

    override fun getName(): String = attributeName

    override fun setName(name: String): PsiElement =
        throw IncorrectOperationException(
            "Атрибут '$attributeName' объявлен в Python-коде приложения; " +
                "переименование не поддерживается",
        )

    /** Сырой диапазон имени — без кавычек, как у всех ссылок плагина. */
    override fun getTextRange(): TextRange = TextRange(site.nameStart, site.nameEnd)

    override fun getTextOffset(): Int = site.nameStart

    /**
     * Текст берётся из файла, а не из имени: имя декодировано, а `getText`
     * обязан совпадать с [getTextRange] — иначе платформа считает длину по
     * одному, а показывает другое.
     */
    override fun getText(): String {
        val text = file.text
        return if (site.nameEnd <= text.length) text.substring(site.nameStart, site.nameEnd) else attributeName
    }

    override fun getTextLength(): Int = site.nameEnd - site.nameStart

    override fun canNavigate(): Boolean = file.virtualFile != null

    override fun canNavigateToSource(): Boolean = canNavigate()

    override fun navigate(requestFocus: Boolean) {
        val virtualFile = file.virtualFile ?: return
        OpenFileDescriptor(file.project, virtualFile, site.nameStart).navigate(requestFocus)
    }

    override fun getPresentableText(): String = attributeName

    /** Подпись цели: откуда имя взялось — поле, `self.x`, `def` или уровень класса. */
    override fun getLocationString(): String = "${site.file} (${site.origin.name.lowercase()})"

    /**
     * Элементы создаются заново на каждый резолв, поэтому равенство —
     * структурное: платформа сравнивает цели, и две ссылки на одно объявление
     * обязаны совпасть.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        val that = other as? SmartAppUserFieldElement ?: return false
        return file == that.file && attributeName == that.attributeName && site == that.site
    }

    override fun hashCode(): Int = 31 * (31 * file.hashCode() + attributeName.hashCode()) + site.hashCode()
}
