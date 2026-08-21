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
import ru.sber.smartapp.dsl.contract.SmartAppSpecs
import ru.sber.smartapp.dsl.contract.TypeContextSpec
import ru.sber.smartapp.dsl.index.SmartAppDefinitionIndex
import ru.sber.smartapp.dsl.index.SmartAppFormFieldIndex
import ru.sber.smartapp.dsl.reference.JsonStringLiteralDecoder
import ru.sber.smartapp.dsl.reference.SmartAppFieldRef
import ru.sber.smartapp.dsl.reference.SmartAppFileRefRules
import ru.sber.smartapp.dsl.reference.SmartAppJinjaLexer
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
        // Только значения JSON (property value или элемент массива), не ключи.
        if (!SmartAppReferenceContributor.isJsonValue(literal)) return
        val property = literal.parent as? JsonProperty

        // Подсветка Jinja-разметки и (для известной формы) неразрешённых полей.
        // Запускается до проверки свойства: Jinja может стоять и в элементе массива.
        if (SmartAppReferenceContributor.isJinja(literal.value)) {
            annotateJinja(literal, holder)
            return
        }

        // Подсветка ключевого слова в значении type (чисто PSI, безопасно в dumb mode).
        if (property != null && property.name == TypeContextSpec.typeProperty && property.value === literal &&
            isKeywordInContext(property, fileKind, literal.value)
        ) {
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(literal.textRange)
                .textAttributes(SmartAppTextAttributes.KEYWORD)
                .create()
            return
        }

        // Ссылка на файл шаблона резолвится по файловой системе, а не по
        // индексу, поэтому проверяется до индекс-зависимой ветки и без guard'а.
        if (SmartAppFileRefRules.isFileReference(literal)) {
            annotateUnresolvedTemplateFile(literal, holder)
            return
        }

        annotateUnresolvedReference(literal, holder)
    }

    /**
     * WARNING «Не удаётся разрешить файл шаблона…», если значение `file` не
     * указывает на существующий файл. Значения с Jinja и выходящие за пределы
     * каталога поиска молчат: чем они являются, из контракта не следует.
     */
    private fun annotateUnresolvedTemplateFile(literal: JsonStringLiteral, holder: AnnotationHolder) {
        val name = literal.value
        if (name.isEmpty()) return
        // Пустой список кандидатов — значение динамическое или выходит за
        // пределы каталога: цель не определена, а не «файл не найден».
        if (SmartAppFileRefRules.candidatePaths(literal).isEmpty()) return
        if (SmartAppFileRefRules.resolve(literal) != null) return
        holder.newAnnotation(HighlightSeverity.WARNING, "Не удаётся разрешить файл шаблона '$name'")
            .range(literal.textRange)
            .create()
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
        val raw = JsonStringLiteralDecoder.rawText(literal) ?: return
        val decoded = JsonStringLiteralDecoder.decode(raw)
        val baseOffset = literal.textOffset + 1 // +1 за открывающую JSON-кавычку
        // Токены в decoded-координатах; переводим каждый диапазон обратно в raw,
        // затем в absolute через +1 за кавычку.
        for (token in SmartAppJinjaLexer.tokenize(decoded.text)) {
            val rawStart = decoded.decodedToRaw[token.range.startOffset]
            val rawEnd = decoded.decodedToRaw[token.range.endOffset]
            val absRange = TextRange(baseOffset + rawStart, baseOffset + rawEnd)
            val attr = when (token.type) {
                SmartAppJinjaTokenType.INTERP_OPEN, SmartAppJinjaTokenType.INTERP_CLOSE,
                SmartAppJinjaTokenType.STATEMENT_OPEN, SmartAppJinjaTokenType.STATEMENT_CLOSE,
                -> SmartAppTextAttributes.JINJA_DELIM

                SmartAppJinjaTokenType.VAR -> SmartAppTextAttributes.JINJA_VAR
                SmartAppJinjaTokenType.DOT -> SmartAppTextAttributes.JINJA_OP
                SmartAppJinjaTokenType.FILTER_OP, SmartAppJinjaTokenType.FILTER_NAME ->
                    SmartAppTextAttributes.JINJA_FILTER

                SmartAppJinjaTokenType.STRING -> SmartAppTextAttributes.JINJA_STRING
                SmartAppJinjaTokenType.TEXT -> continue // plain text не подсвечиваем
            }
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(absRange)
                .textAttributes(attr)
                .create()
        }
        annotateUnresolvedJinjaField(literal, decoded, holder)
    }

    /**
     * WARNING «Не удаётся разрешить поле…» для `main_form.<id>` в любом
     * выражении Jinja (интерполяция или statement-тег), если форма известна, но
     * поля с этим именем в ней нет. Динамический `form` молчит: однозначной
     * цели нет, ложного WARNING быть не должно.
     */
    private fun annotateUnresolvedJinjaField(
        literal: JsonStringLiteral,
        decoded: JsonStringLiteralDecoder.Decoded,
        holder: AnnotationHolder,
    ) {
        val project = literal.project
        // Зависит от индекса: пропускаем во время индексации; daemon перезапустится после.
        if (DumbService.isDumb(project)) return
        val form = SmartAppFieldRef.targetFormOf(literal) ?: return
        val baseOffset = literal.textOffset + 1

        // fieldCandidates возвращает вхождения из всех выражений Jinja —
        // и интерполяций, и statement-тегов — в decoded-координатах.
        for (candidate in SmartAppJinjaLexer.fieldCandidates(decoded.text)) {
            val fieldName = candidate.field
            val found = SmartAppFormFieldIndex.findFields(
                project, form, fieldName, SmartAppScopes.forElement(literal),
            )
            if (found.isEmpty()) {
                val rawStart = decoded.decodedToRaw[candidate.fieldRange.startOffset]
                val rawEnd = decoded.decodedToRaw[candidate.fieldRange.endOffset]
                val absRange = TextRange(baseOffset + rawStart, baseOffset + rawEnd)
                holder.newAnnotation(
                    HighlightSeverity.WARNING,
                    "Не удаётся разрешить поле '$fieldName' формы '$form'",
                ).range(absRange).create()
            }
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
        // Список структурных ключей — данные контракта (SmartAppSpecs), тот же
        // набор уезжает в rules.json для VS Code-расширения.
        val STRUCTURAL_KEYS = SmartAppSpecs.structuralKeys
    }
}
