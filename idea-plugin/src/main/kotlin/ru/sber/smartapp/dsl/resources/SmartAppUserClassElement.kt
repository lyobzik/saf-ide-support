package ru.sber.smartapp.dsl.resources

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.FakePsiElement
import com.intellij.util.IncorrectOperationException
import ru.sber.smartapp.dsl.resources.SmartAppUserModelResolver.UserClassSite

/**
 * Строка `class <Name>` активного класса модели пользователя — **только цель
 * перехода** с корневой переменной.
 *
 * Find Usages для него не заводится: имени класса в тексте DSL нет, там стоит
 * псевдоним из параметризатора, поэтому вхождения `user` вхождениями
 * `CustomUser` не являются. Отдельный тип, а не флаг на
 * [SmartAppUserFieldElement]: у него другое описание и другое поведение в
 * фабрике поиска.
 */
class SmartAppUserClassElement(
    private val file: PsiFile,
    val site: UserClassSite,
) : FakePsiElement() {

    override fun getParent(): PsiElement = file

    override fun getContainingFile(): PsiFile = file

    override fun getName(): String = site.name

    override fun setName(name: String): PsiElement =
        throw IncorrectOperationException(
            "Класс '${site.name}' объявлен в Python-коде приложения; переименование не поддерживается",
        )

    override fun getTextRange(): TextRange = TextRange(site.nameStart, site.nameEnd)

    override fun getTextOffset(): Int = site.nameStart

    override fun getText(): String {
        val text = file.text
        return if (site.nameEnd <= text.length) text.substring(site.nameStart, site.nameEnd) else site.name
    }

    override fun getTextLength(): Int = site.nameEnd - site.nameStart

    override fun canNavigate(): Boolean = file.virtualFile != null

    override fun canNavigateToSource(): Boolean = canNavigate()

    override fun navigate(requestFocus: Boolean) {
        val virtualFile = file.virtualFile ?: return
        OpenFileDescriptor(file.project, virtualFile, site.nameStart).navigate(requestFocus)
    }

    override fun getPresentableText(): String = site.name

    override fun getLocationString(): String = site.file

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        val that = other as? SmartAppUserClassElement ?: return false
        return file == that.file && site == that.site
    }

    override fun hashCode(): Int = 31 * file.hashCode() + site.hashCode()
}
