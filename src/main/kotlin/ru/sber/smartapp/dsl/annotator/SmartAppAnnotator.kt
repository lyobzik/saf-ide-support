package ru.sber.smartapp.dsl.annotator

import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import ru.sber.smartapp.dsl.SmartAppFiles
import ru.sber.smartapp.dsl.SmartAppKeywords
import ru.sber.smartapp.dsl.SmartAppRefKind
import ru.sber.smartapp.dsl.SmartAppScopes
import ru.sber.smartapp.dsl.SmartAppTypeContext
import ru.sber.smartapp.dsl.index.SmartAppDefinitionIndex
import ru.sber.smartapp.dsl.reference.SmartAppReferenceContributor
import ru.sber.smartapp.dsl.reference.SmartAppRefRules
import ru.sber.smartapp.dsl.highlight.SmartAppTextAttributes

/**
 * Подсвечивает ключевые слова и структурные ключи SmartApp DSL и помечает
 * неразрешённые кросс-ссылки предупреждениями.
 *
 * [DumbAware]: подсветка ключевых слов/структурных ключей — чисто по PSI и
 * работает даже во время индексации. Ветка неразрешённых ссылок читает
 * file-based индекс, поэтому она под guard'ом [DumbService.isDumb] и
 * перезапускается платформой по завершении индексации.
 */
class SmartAppAnnotator : Annotator, DumbAware {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val fileKind = SmartAppFiles.kindOf(element.containingFile) ?: return

        when (element) {
            is JsonProperty -> annotateStructuralKey(element, holder)
            is JsonStringLiteral -> annotateStringValue(element, fileKind, holder)
        }
    }

    private fun annotateStructuralKey(property: JsonProperty, holder: AnnotationHolder) {
        if (property.name in STRUCTURAL_KEYS) {
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(property.nameElement.textRange)
                .textAttributes(SmartAppTextAttributes.FIELD)
                .create()
        }
    }

    private fun annotateStringValue(
        literal: JsonStringLiteral,
        fileKind: SmartAppRefKind,
        holder: AnnotationHolder,
    ) {
        val property = literal.parent as? JsonProperty ?: return
        if (property.value !== literal) return

        // Подсветка ключевого слова в значении type (чисто PSI, безопасно в dumb mode).
        if (property.name == "type" && isKeywordInContext(property, fileKind, literal.value)) {
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(literal.textRange)
                .textAttributes(SmartAppTextAttributes.KEYWORD)
                .create()
            return
        }

        annotateUnresolvedReference(literal, holder)
    }

    /**
     * Контекстная проверка ключевого слова: если категория контейнера
     * распознана — слово должно принадлежать именно ей (тип filler'а не должен
     * подсвечиваться в позиции типа сценария); иначе — мягкий откат к
     * принадлежности любой категории.
     */
    private fun isKeywordInContext(
        property: JsonProperty,
        fileKind: SmartAppRefKind,
        value: String,
    ): Boolean {
        val category = SmartAppTypeContext.categoryFor(property, fileKind)
        return if (category != null) SmartAppKeywords.isKeyword(category, value)
        else SmartAppKeywords.isAnyKeyword(value)
    }

    private fun annotateUnresolvedReference(literal: JsonStringLiteral, holder: AnnotationHolder) {
        if (SmartAppReferenceContributor.isJinja(literal.value)) return
        val kinds = SmartAppRefRules.targetKinds(literal)
        if (kinds.isEmpty()) return

        val project = literal.project
        // Зависит от индекса: пропускаем во время индексации; daemon перезапустится после.
        if (DumbService.isDumb(project)) return

        val name = literal.value
        if (name.isEmpty()) return

        val definitions = SmartAppDefinitionIndex.findDefinitions(
            project, name, kinds, SmartAppScopes.forElement(literal),
        )
        if (definitions.isNotEmpty()) return

        val kindLabel = kinds.joinToString("/") { it.name.lowercase() }
        holder.newAnnotation(HighlightSeverity.WARNING, "Не удаётся разрешить $kindLabel '$name'")
            .range(literal.textRange)
            .create()
    }

    private companion object {
        val STRUCTURAL_KEYS = setOf(
            "form",
            "filler",
            "classifier",
            "action",
            "behavior",
            "scenario",
            "scenario_description",
            "actions",
            "requirement",
            "fields",
            "questions",
            "on_filled_actions",
        )
    }
}
