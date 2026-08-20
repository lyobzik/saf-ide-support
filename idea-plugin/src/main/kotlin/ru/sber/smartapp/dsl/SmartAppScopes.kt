package ru.sber.smartapp.dsl

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopesCore

/**
 * Области поиска для межфайлового резолва SmartApp DSL. Резолв и автодополнение
 * ограничиваются каталогом `references`, которому принадлежит исходный файл,
 * чтобы в монорепозитории с несколькими наборами `static/references` ссылки не
 * утекали в чужой набор. Если корень `references` не найден — откат к проекту.
 */
object SmartAppScopes {

    fun forFile(project: Project, file: VirtualFile?): GlobalSearchScope {
        val root = SmartAppFiles.referencesRoot(file)
            ?: return GlobalSearchScope.projectScope(project)
        return GlobalSearchScopesCore.directoryScope(project, root, true)
    }

    fun forElement(element: PsiElement): GlobalSearchScope =
        forFile(element.project, element.containingFile?.virtualFile)

    fun forPsiFile(psiFile: PsiFile): GlobalSearchScope =
        forFile(psiFile.project, psiFile.virtualFile)
}
