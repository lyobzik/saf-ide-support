package ru.sber.smartapp.dsl.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.ProcessingContext
import ru.sber.smartapp.dsl.SmartAppFiles
import ru.sber.smartapp.dsl.SmartAppKeywords
import ru.sber.smartapp.dsl.SmartAppRefKind
import ru.sber.smartapp.dsl.index.SmartAppDefinitionIndex
import ru.sber.smartapp.dsl.reference.SmartAppRefRules

/**
 * Дополняет строковые значения SmartApp DSL:
 *  - в значении `type` -> ключевые слова категории объемлющего контейнера
 *    (чистое чтение ресурса, доступно во время индексации);
 *  - в значении ссылочного ключа (`form`, `scenario`, ...) -> имена сущностей
 *    из индекса определений (пропускается во время индексации).
 *
 * [DumbAware]: все чтения индекса под guard'ом [DumbService.isDumb], поэтому в
 * dumb mode работает только не-индексная ветка ключевых слов `type`.
 */
class SmartAppCompletionContributor : CompletionContributor(), DumbAware {

    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().withParent(JsonStringLiteral::class.java),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet,
                ) {
                    addVariants(parameters, result)
                }
            },
        )
    }

    private fun addVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        // Автодополнение работает на in-memory копии; реальный путь, нужный для
        // определения вида DSL-файла, сохраняется в оригинальном файле.
        val fileKind = SmartAppFiles.kindOf(parameters.originalFile) ?: return

        val literal = parameters.position.parent as? JsonStringLiteral ?: return
        val property = literal.parent as? JsonProperty ?: return
        if (property.value !== literal) return

        if (property.name == "type") {
            addKeywordVariants(property, fileKind, result)
            return
        }

        addNameVariants(literal, fileKind, result)
    }

    private fun addKeywordVariants(
        property: JsonProperty,
        fileKind: SmartAppRefKind,
        result: CompletionResultSet,
    ) {
        val category = categoryForTypeContext(property, fileKind)
        val keywords = category?.let { SmartAppKeywords.all(it) }?.takeIf { it.isNotEmpty() }
            ?: SmartAppKeywords.allKeywords

        for (keyword in keywords) {
            result.addElement(
                PrioritizedLookupElement.withPriority(
                    LookupElementBuilder.create(keyword).withTypeText("type"),
                    KEYWORD_PRIORITY,
                ),
            )
        }
    }

    private fun addNameVariants(
        literal: JsonStringLiteral,
        fileKind: SmartAppRefKind,
        result: CompletionResultSet,
    ) {
        val project = literal.project
        if (DumbService.isDumb(project)) return

        val kinds = SmartAppRefRules.targetKinds(literal, fileKind)
        if (kinds.isEmpty()) return

        val scope = GlobalSearchScope.allScope(project)
        for (kind in kinds) {
            for (name in SmartAppDefinitionIndex.allNames(project, kind, scope)) {
                result.addElement(
                    PrioritizedLookupElement.withPriority(
                        LookupElementBuilder.create(name).withTypeText(kind.name.lowercase()),
                        NAME_PRIORITY,
                    ),
                )
            }
        }
    }

    /**
     * Категория для значения `type` по «лучшему усилию»: смотрим ключ, под
     * которым лежит объемлющий объект; при неудаче — откат к виду файла.
     */
    private fun categoryForTypeContext(property: JsonProperty, fileKind: SmartAppRefKind): String? {
        val owner = property.parent as? JsonObject ?: return null
        val containerKey = containerKeyOf(owner)
        keyToCategory(containerKey)?.let { return it }
        return fileKindCategory(fileKind)
    }

    private fun containerKeyOf(owner: JsonObject): String? {
        return when (val parent = owner.parent) {
            is JsonProperty -> parent.name
            is JsonArray -> (parent.parent as? JsonProperty)?.name
            else -> null
        }
    }

    private fun keyToCategory(key: String?): String? = when (key) {
        "filler" -> "filler"
        "classifier" -> "classifier"
        "action", "actions" -> "action"
        "requirement", "requirements" -> "requirement"
        "fields" -> "field_description"
        else -> null
    }

    private fun fileKindCategory(kind: SmartAppRefKind?): String? = when (kind) {
        SmartAppRefKind.SCENARIO -> "scenario"
        SmartAppRefKind.FORM -> "form_description"
        SmartAppRefKind.ACTION, SmartAppRefKind.BEHAVIOR -> "action"
        SmartAppRefKind.FILLER -> "filler"
        SmartAppRefKind.CLASSIFIER -> "classifier"
        null -> null
    }

    private companion object {
        const val KEYWORD_PRIORITY = 100.0
        const val NAME_PRIORITY = 50.0
    }
}
