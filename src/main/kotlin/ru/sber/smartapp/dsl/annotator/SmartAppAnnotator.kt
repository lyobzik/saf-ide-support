package ru.sber.smartapp.dsl.annotator

import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import ru.sber.smartapp.dsl.SmartAppFiles
import ru.sber.smartapp.dsl.SmartAppKeywords
import ru.sber.smartapp.dsl.SmartAppRefKind
import ru.sber.smartapp.dsl.SmartAppScopes
import ru.sber.smartapp.dsl.SmartAppTypeContext
import ru.sber.smartapp.dsl.index.SmartAppDefinitionIndex
import ru.sber.smartapp.dsl.index.SmartAppFormFieldIndex
import ru.sber.smartapp.dsl.reference.SmartAppFieldRef
import ru.sber.smartapp.dsl.reference.SmartAppJinjaLexer
import ru.sber.smartapp.dsl.reference.SmartAppJinjaToken
import ru.sber.smartapp.dsl.reference.SmartAppJinjaTokenType
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

        // Подсветка Jinja-разметки и (для известной формы) неразрешённых полей.
        if (SmartAppReferenceContributor.isJinja(literal.value)) {
            annotateJinja(literal, holder)
            return
        }

        annotateUnresolvedReference(literal, holder)
    }

    /**
     * Подсветка токенов Jinja-выражения (`{{ }}`, переменные, `.`, фильтры,
     * строки) и WARNING для неразрешённого поля формы в `{{ main_form.<id> }}`.
     *
     * WARNING ставится только при известной форме ([SmartAppFieldRef.targetFormOf]):
     * динамический выбор формы (`"form": "{{ main_form.name }}"`) формы не даёт, и
     * поле в нём не помечается ошибкой. Сама подсветка работает по чистому PSI
     * лексера и доступна в dumb mode; индексное чтение для WARNING — под guard'ом.
     */
    private fun annotateJinja(literal: JsonStringLiteral, holder: AnnotationHolder) {
        val raw = SmartAppReferenceContributor.rawText(literal) ?: return
        val baseOffset = literal.textOffset + 1 // +1 за открывающую JSON-кавычку
        val tokens = SmartAppJinjaLexer.tokenize(raw)
        for (token in tokens) {
            val absRange = TextRange(baseOffset + token.range.startOffset, baseOffset + token.range.endOffset)
            val attr = when (token.type) {
                SmartAppJinjaTokenType.DELIM_OPEN, SmartAppJinjaTokenType.DELIM_CLOSE -> SmartAppTextAttributes.JINJA_DELIM
                SmartAppJinjaTokenType.VAR -> SmartAppTextAttributes.JINJA_VAR
                SmartAppJinjaTokenType.DOT -> SmartAppTextAttributes.JINJA_OP
                SmartAppJinjaTokenType.FILTER_OP -> SmartAppTextAttributes.JINJA_FILTER
                SmartAppJinjaTokenType.STRING -> SmartAppTextAttributes.JINJA_STRING
                SmartAppJinjaTokenType.TEXT -> continue // plain text не подсвечиваем
            }
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(absRange)
                .textAttributes(attr)
                .create()
        }
        annotateUnresolvedJinjaField(literal, tokens, raw, holder)
    }

    /**
     * WARNING «Не удаётся разрешить поле…» для `{{ main_form.<id> }}`, если форма
     * известна, но поля с этим именем в ней нет. Если форма неизвестна — молчит.
     */
    private fun annotateUnresolvedJinjaField(
        literal: JsonStringLiteral,
        tokens: List<SmartAppJinjaToken>,
        raw: String,
        holder: AnnotationHolder,
    ) {
        val project = literal.project
        // Зависит от индекса: пропускаем во время индексации; daemon перезапустится после.
        if (DumbService.isDumb(project)) return
        val form = SmartAppFieldRef.targetFormOf(literal) ?: return
        val baseOffset = literal.textOffset + 1

        var insideExpr = false
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            when (t.type) {
                SmartAppJinjaTokenType.DELIM_OPEN -> insideExpr = true
                SmartAppJinjaTokenType.DELIM_CLOSE -> insideExpr = false
                SmartAppJinjaTokenType.VAR -> {
                    if (insideExpr && raw.substring(t.range.startOffset, t.range.endOffset) == "main_form") {
                        val dot = tokens.getOrNull(i + 1)
                        val field = tokens.getOrNull(i + 2)
                        if (dot != null && field != null &&
                            dot.type == SmartAppJinjaTokenType.DOT && field.type == SmartAppJinjaTokenType.VAR
                        ) {
                            val fieldName = raw.substring(field.range.startOffset, field.range.endOffset)
                            val found = SmartAppFormFieldIndex.findFields(
                                project, form, fieldName, SmartAppScopes.forElement(literal),
                            )
                            if (found.isEmpty()) {
                                val absRange = TextRange(
                                    baseOffset + field.range.startOffset,
                                    baseOffset + field.range.endOffset,
                                )
                                holder.newAnnotation(
                                    HighlightSeverity.WARNING,
                                    "Не удаётся разрешить поле '$fieldName' формы '$form'",
                                ).range(absRange).create()
                            }
                            i += 2
                        }
                    }
                }
                else -> Unit
            }
            i++
        }
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
