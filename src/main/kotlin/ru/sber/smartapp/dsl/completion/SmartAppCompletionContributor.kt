package ru.sber.smartapp.dsl.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiFile
import com.intellij.util.ProcessingContext
import ru.sber.smartapp.dsl.SmartAppFiles
import ru.sber.smartapp.dsl.SmartAppKeywords
import ru.sber.smartapp.dsl.SmartAppRefKind
import ru.sber.smartapp.dsl.SmartAppScopes
import ru.sber.smartapp.dsl.SmartAppTypeContext
import ru.sber.smartapp.dsl.index.SmartAppNameIndex
import ru.sber.smartapp.dsl.index.SmartAppFormFieldNameIndex
import ru.sber.smartapp.dsl.reference.JsonStringLiteralDecoder
import ru.sber.smartapp.dsl.reference.SmartAppFieldRef
import ru.sber.smartapp.dsl.reference.SmartAppJinjaLexer
import ru.sber.smartapp.dsl.reference.SmartAppJinjaTokenType
import ru.sber.smartapp.dsl.reference.SmartAppRefRules
import ru.sber.smartapp.dsl.reference.SmartAppReferenceContributor

/**
 * Дополняет строковые значения SmartApp DSL:
 *  - внутри Jinja `{{ main_form.<caret> }}` -> имена полей целевой формы (ранняя
 *    ветка, завершающая обработку, чтобы формы не предлагались как обычные имена);
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
        // Только значения JSON (property value или элемент массива), не ключи.
        if (!SmartAppReferenceContributor.isJsonValue(literal)) return
        val property = literal.parent as? JsonProperty

        // Ранняя Jinja-ветка: каретка внутри `{{ main_form.<caret> }}`. Должна
        // идти первой и завершать обработку, иначе обычная ссылочная ветка ниже
        // предложит формы в позиции, где ожидается имя поля. Jinja может стоять
        // и в элементе массива, поэтому ветка не требует JsonProperty.
        if (SmartAppReferenceContributor.isJinja(literal.value)) {
            addFieldVariants(literal, fileKind, parameters.originalFile, parameters, result)
            return
        }

        if (property != null && property.value === literal && property.name == "type") {
            addKeywordVariants(property, fileKind, result)
            return
        }

        addNameVariants(literal, fileKind, parameters.originalFile, result)
    }

    private fun addKeywordVariants(
        property: JsonProperty,
        fileKind: SmartAppRefKind,
        result: CompletionResultSet,
    ) {
        val category = SmartAppTypeContext.categoryFor(property, fileKind)
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

    /**
     * Имена полей формы для автодополнения внутри `{{ main_form.<caret> }}`.
     * Целевая форма определяется через [SmartAppFieldRef.targetFormOf]; если форма
     * неизвестна (динамический `form`) — варианты не предлагаются.
     *
     * [fileKind]/[originalFile] берутся снаружи, т.к. `targetFormOf`/scope опираются
     * на реальный путь файла, а completion работает на in-memory копии, этот путь
     * теряющей.
     */
    private fun addFieldVariants(
        literal: JsonStringLiteral,
        fileKind: SmartAppRefKind,
        originalFile: PsiFile,
        parameters: CompletionParameters,
        result: CompletionResultSet,
    ) {
        val project = literal.project
        if (DumbService.isDumb(project)) return

        // Позиция каретки в raw-координатах литерала (без кавычек).
        val rawCaret = parameters.offset - literal.textOffset - 1
        if (rawCaret < 0) return
        val raw = SmartAppReferenceContributor.rawText(literal) ?: return
        if (rawCaret > raw.length) return
        val decoded = JsonStringLiteralDecoder.decode(raw)
        val decodedCaret = decodedCaretOf(decoded.decodedToRaw, rawCaret)

        // Контекст каретки определяем тем же лексером, что и подсветка/резолв:
        // completion полей активируется только внутри актуальной открытой
        // интерполяции {{ … }} — не в statement-теге {% %} и не внутри
        // Jinja-строки (например default('{{ main_form.<caret>') — это STRING).
        // Fallback с достроенным "}}": в момент набора интерполяция обычно ещё
        // не закрыта ({{ main_form.<caret>), и лексер отдаёт её как TEXT.
        val interpOpenEnd = interpContextAt(decoded.text, decodedCaret)
            ?: interpContextAt(decoded.text + "}}", decodedCaret)
            ?: return
        // Между `{{` и кареткой должен быть ровно `main_form.` (с допуском пробелов).
        // containsMatchIn: completion подставляет dummy после точки, matches требовал
        // бы пустой хвост.
        val afterInterp = decoded.text.substring(interpOpenEnd, decodedCaret)
        if (!MAIN_FORM_PREFIX.containsMatchIn(afterInterp)) return

        val form = SmartAppFieldRef.targetFormOf(literal, fileKind) ?: return
        val scope = SmartAppScopes.forPsiFile(originalFile)
        // Prefix перед кареткой — содержимое после последней точки в `main_form.`,
        // иначе платформа отфильтрует варианты по всему `main_form.` и они не
        // совпадут с именами полей. Пробел после точки в идентификатор не входит.
        val fieldPrefix = decoded.text.substring(0, decodedCaret).substringAfterLast('.')
            .dropWhile { it.isWhitespace() }
        val fieldResult = result.withPrefixMatcher(fieldPrefix)
        for (field in SmartAppFormFieldNameIndex.allNames(project, form, scope)) {
            fieldResult.addElement(
                PrioritizedLookupElement.withPriority(
                    LookupElementBuilder.create(field).withTypeText("field"),
                    FIELD_PRIORITY,
                ),
            )
        }
    }

    /**
     * Позиция каретки в decoded-координатах: число decoded-символов, чей
     * raw-старт строго левее raw-позиции каретки. Без escape совпадает с
     * raw-позицией; каретка внутри escape-последовательности относится к позиции
     * сразу после раскрытого символа.
     */
    private fun decodedCaretOf(decodedToRaw: IntArray, rawCaret: Int): Int {
        var decodedCaret = 0
        // Последний элемент карты — sentinel (конец текста), символом не является.
        while (decodedCaret < decodedToRaw.size - 1 && decodedToRaw[decodedCaret] < rawCaret) {
            decodedCaret++
        }
        return decodedCaret
    }

    /**
     * Смещение конца открывающего `{{`, если позиция [decodedCaret] находится
     * внутри актуальной открытой интерполяции, иначе `null`. Каретка внутри
     * statement-тега `{% … %}` или Jinja-строки [SmartAppJinjaTokenType.STRING]
     * интерполяцией не считается.
     */
    private fun interpContextAt(decodedText: String, decodedCaret: Int): Int? {
        var inInterp = false
        var inStatement = false
        var interpOpenEnd = -1
        for (token in SmartAppJinjaLexer.tokenize(decodedText)) {
            if (token.range.startOffset >= decodedCaret) break
            when (token.type) {
                SmartAppJinjaTokenType.INTERP_OPEN -> {
                    inInterp = true
                    inStatement = false
                    interpOpenEnd = token.range.endOffset
                }
                SmartAppJinjaTokenType.INTERP_CLOSE -> inInterp = false
                SmartAppJinjaTokenType.STATEMENT_OPEN -> {
                    inStatement = true
                    inInterp = false
                }
                SmartAppJinjaTokenType.STATEMENT_CLOSE -> inStatement = false
                // Каретка строго внутри строки — это не позиция поля формы.
                SmartAppJinjaTokenType.STRING -> if (decodedCaret < token.range.endOffset) return null
                else -> {}
            }
        }
        return if (inInterp && !inStatement) interpOpenEnd else null
    }

    private fun addNameVariants(
        literal: JsonStringLiteral,
        fileKind: SmartAppRefKind,
        originalFile: PsiFile,
        result: CompletionResultSet,
    ) {
        val project = literal.project
        if (DumbService.isDumb(project)) return

        val kinds = SmartAppRefRules.targetKinds(literal, fileKind)
        if (kinds.isEmpty()) return

        // Scope считаем по оригинальному файлу: in-memory копия теряет реальный путь.
        val scope = SmartAppScopes.forPsiFile(originalFile)
        for (kind in kinds) {
            for (name in SmartAppNameIndex.allNames(project, kind, scope)) {
                result.addElement(
                    PrioritizedLookupElement.withPriority(
                        LookupElementBuilder.create(name).withTypeText(kind.name.lowercase()),
                        NAME_PRIORITY,
                    ),
                )
            }
        }
    }

    private companion object {
        const val KEYWORD_PRIORITY = 100.0
        const val NAME_PRIORITY = 50.0
        const val FIELD_PRIORITY = 30.0

        // Префикс между `{{` и кареткой, открывающий completion имени поля:
        // опциональные пробелы, `main_form`, опциональные пробелы, точка. Используем
        // containsMatchIn, а не matches: completion подставляет dummy-идентификатор
        // на место каретки, поэтому после точки ещё есть текст.
        val MAIN_FORM_PREFIX: Regex = Regex("""^\s*main_form\s*\.\s*""")
    }
}
