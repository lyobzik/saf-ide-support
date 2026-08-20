package ru.sber.smartapp.dsl.rename

import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import ru.sber.smartapp.dsl.SmartAppFiles
import ru.sber.smartapp.dsl.SmartAppScopes
import ru.sber.smartapp.dsl.reference.SmartAppFieldRef
import ru.sber.smartapp.dsl.reference.SmartAppFieldReference
import ru.sber.smartapp.dsl.reference.SmartAppReference

/**
 * Ограничивает переименование определений SmartApp DSL их собственным набором
 * `static/references` и только ссылками плагина.
 *
 * Без этого процессора переименование утекает: JSON-плагин платформы связывает
 * **одноимённые ключи** разных файлов (`JsonPropertyNameReference`), и штатный
 * рефакторинг переписывает заодно одноимённые определения в чужих наборах — в
 * монорепозитории это порча соседнего приложения. Резолв и диагностика уже
 * изолированы через [SmartAppScopes]; здесь та же изоляция доводится до
 * рефакторинга.
 */
class SmartAppRenameProcessor : RenamePsiElementProcessor() {

    override fun canProcessElement(element: PsiElement): Boolean {
        val property = element as? JsonProperty ?: return false
        if (SmartAppFiles.kindOf(property.containingFile) == null) return false
        return isTopLevelDefinition(property) || SmartAppFieldRef.isFieldDefinition(property)
    }

    /**
     * Ссылки, которые обновит рефакторинг: только ссылки плагина и только в
     * наборе `references` самого определения.
     */
    override fun findReferences(
        element: PsiElement,
        searchScope: SearchScope,
        searchInCommentsAndStrings: Boolean,
    ): Collection<PsiReference> {
        val scope = SmartAppScopes.forElement(element)
        return ReferencesSearch.search(element, scope)
            .findAll()
            .filter { it is SmartAppReference || it is SmartAppFieldReference }
    }

    /** Определение сущности — свойство корневого объекта DSL-файла. */
    private fun isTopLevelDefinition(property: JsonProperty): Boolean {
        val owner = property.parent as? JsonObject ?: return false
        return owner.parent is JsonFile
    }
}
