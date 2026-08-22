package ru.sber.smartapp.dsl.reference

import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.ResolveResult
import ru.sber.smartapp.dsl.SmartAppFiles
import ru.sber.smartapp.dsl.SmartAppTypeContext
import ru.sber.smartapp.dsl.contract.TypeContextSpec
import ru.sber.smartapp.dsl.resources.SmartAppCustomKeywords
import ru.sber.smartapp.dsl.resources.SmartAppRegistrationElement

/**
 * Ссылка от значения `type` к строке, где приложение зарегистрировало это
 * ключевое слово в своём Python-коде.
 *
 * Мягкая (`soft`) и поли-вариантная. Мягкая — потому что подавляющее
 * большинство значений `type` называет слова фреймворка, исходников которого в
 * проекте нет: «неразрешённая ссылка» здесь не ошибка. Поли-вариантная — по
 * тем же причинам, что и у [SmartAppReference]: в позиции с нераспознанной
 * категорией целями становятся все регистрации приложения с этим именем.
 *
 * Индекс не читается вовсе, поэтому dumb-guard не нужен: словарь приложения
 * собирается по VFS и доступен во время индексации.
 */
class SmartAppCustomKeywordReference(
    element: JsonStringLiteral,
) : PsiPolyVariantReferenceBase<JsonStringLiteral>(element, /* soft = */ true) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        registrationsAt(element).map { PsiElementResolveResult(it) }.toTypedArray()

    /**
     * Переименование ключевого слова не поддерживается ([SmartAppRegistrationElement]
     * отказывает в `setName`), поэтому строку значения тоже никто не переписывает.
     */
    override fun handleElementRename(newElementName: String): PsiElement = element

    companion object {

        /** `true`, если [literal] — значение свойства `type` в DSL-файле. */
        fun isTypeValue(literal: JsonStringLiteral): Boolean {
            val property = literal.parent as? JsonProperty ?: return false
            return property.name == TypeContextSpec.typeProperty && property.value === literal
        }

        /**
         * Регистрации приложения, на которые ведёт значение `type` [literal].
         *
         * Категория позиции сужает выбор: слово одной категории не должно вести
         * в регистрацию другой. Если категория не распознана — целями становятся
         * все одноимённые регистрации приложения, ровно как подсветка в этом
         * случае откатывается к словарю целиком.
         */
        fun registrationsAt(literal: JsonStringLiteral): List<SmartAppRegistrationElement> {
            val property = literal.parent as? JsonProperty ?: return emptyList()
            if (!isTypeValue(literal)) return emptyList()

            val file = literal.containingFile?.originalFile ?: return emptyList()
            val fileKind = SmartAppFiles.kindOf(file) ?: return emptyList()
            val name = literal.value
            if (name.isEmpty()) return emptyList()

            val category = SmartAppTypeContext.categoryFor(property, fileKind)
            return SmartAppCustomKeywords.registrations(file).filter {
                it.keyword.name == name && (category == null || it.keyword.category == category)
            }
        }
    }
}
