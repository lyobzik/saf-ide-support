package ru.sber.smartapp.dsl.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
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
import ru.sber.smartapp.dsl.contract.JinjaSpec
import ru.sber.smartapp.dsl.contract.TypeContextSpec
import ru.sber.smartapp.dsl.index.SmartAppNameIndex
import ru.sber.smartapp.dsl.index.SmartAppFormFieldNameIndex
import ru.sber.smartapp.dsl.reference.JsonStringLiteralDecoder
import ru.sber.smartapp.dsl.reference.SmartAppFieldRef
import ru.sber.smartapp.dsl.resources.SmartAppCustomKeywords
import ru.sber.smartapp.dsl.reference.SmartAppFileRefRules
import ru.sber.smartapp.dsl.reference.SmartAppJinjaLexer
import ru.sber.smartapp.dsl.reference.SmartAppJinjaTokenType
import ru.sber.smartapp.dsl.reference.SmartAppRefRules
import ru.sber.smartapp.dsl.reference.SmartAppTemplateFiles
import ru.sber.smartapp.dsl.reference.SmartAppReferenceContributor

/**
 * Дополняет строковые значения SmartApp DSL:
 *  - внутри Jinja `{{ main_form.<caret> }}` -> имена полей целевой формы (ранняя
 *    ветка, завершающая обработку, чтобы формы не предлагались как обычные имена);
 *  - в значении `type` -> ключевые слова категории объемлющего контейнера
 *    (чистое чтение ресурса, доступно во время индексации);
 *  - в значении ссылочного ключа (`form`, `scenario`, ...) -> имена сущностей
 *    из индекса определений (пропускается во время индексации);
 *  - в значении файловой ссылки (`"file"` при `type: unified_template`) -> пути
 *    файлов каталога шаблонов набора (обход VFS, индекс не нужен).
 *
 * [DumbAware]: все чтения индекса под guard'ом [DumbService.isDumb], поэтому в
 * dumb mode работают не-индексные ветки — ключевые слова `type` и файлы
 * шаблонов.
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
            addJinjaVariants(literal, fileKind, parameters.originalFile, parameters, result)
            return
        }

        if (property != null && property.value === literal &&
            property.name == TypeContextSpec.typeProperty
        ) {
            addKeywordVariants(property, fileKind, parameters.originalFile, result)
            return
        }

        // Файловая ссылка (`"file"` при `type: unified_template`): цель — файл в
        // каталоге набора, а не top-level ключ, поэтому и варианты берутся из
        // VFS, а не из индекса.
        if (SmartAppFileRefRules.isFileReference(literal)) {
            addFileVariants(literal, parameters, result)
            return
        }

        addNameVariants(literal, fileKind, parameters.originalFile, result)
    }

    private fun addKeywordVariants(
        property: JsonProperty,
        fileKind: SmartAppRefKind,
        originalFile: PsiFile,
        result: CompletionResultSet,
    ) {
        val category = SmartAppTypeContext.categoryFor(property, fileKind)
        val keywords = category?.let { SmartAppKeywords.all(it) }?.takeIf { it.isNotEmpty() }
            ?: SmartAppKeywords.allKeywords

        for (keyword in keywords) {
            result.addElement(
                marked(
                    LookupElementBuilder.create(keyword).withTypeText("type"),
                    SmartAppCompletionKind.KEYWORD,
                    KEYWORD_PRIORITY,
                ),
            )
        }

        // Слова приложения: подпись — класс, который за ними стоит. Из значения
        // type его не видно, а это единственное, чем кастомное слово отличается
        // от фреймворкового. Файл берём оригинальный: у копии completion нет пути.
        for (custom in SmartAppCustomKeywords.of(originalFile)) {
            if (category != null && custom.category != category) continue
            if (custom.name in keywords) continue
            result.addElement(
                marked(
                    LookupElementBuilder.create(custom.name).withTypeText(custom.className ?: "type"),
                    SmartAppCompletionKind.KEYWORD,
                    KEYWORD_PRIORITY,
                ),
            )
        }
    }

    /**
     * Автодополнение внутри выражения Jinja:
     *  - после `main_form.<caret>` — имена полей целевой формы;
     *  - на месте самого идентификатора (`{% if mai<caret> %}`) — переменная формы.
     *
     * Целевая форма определяется через [SmartAppFieldRef.targetFormOf]; если форма
     * неизвестна (динамический `form`) — вариантов нет ни там, ни там: имя без
     * известной формы — вариант без семантики.
     *
     * [fileKind]/[originalFile] берутся снаружи, т.к. `targetFormOf`/scope опираются
     * на реальный путь файла, а completion работает на in-memory копии, этот путь
     * теряющей.
     */
    private fun addJinjaVariants(
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
        // completion полей активируется внутри актуального открытого выражения
        // ({{ … }} или {% … %}), но не внутри Jinja-строки (например
        // default('{{ main_form.<caret>') — это STRING).
        // Fallback с достроенным разделителем: в момент набора выражение обычно
        // ещё не закрыто, и лексер отдаёт его как TEXT.
        val exprOpenEnd = exprContextAt(decoded.text, decodedCaret)
            ?: exprContextAt(decoded.text + "}}", decodedCaret)
            ?: exprContextAt(decoded.text + "%}", decodedCaret)
            ?: return
        // Непосредственно перед кареткой должно стоять `main_form.` (с допуском
        // пробелов) и далее — только частичный идентификатор поля: цепочки
        // `main_form.x.<caret>` резолва не имеют (см. границы fieldCandidates),
        // и completion там предлагал бы заведомо битые варианты. Проверяется
        // именно хвост, а не всё выражение: в statement-теге слева от обращения
        // стоит ещё и `set x = `.
        val afterOpen = decoded.text.substring(exprOpenEnd, decodedCaret)
        val fields = MAIN_FORM_TAIL.containsMatchIn(afterOpen)
        if (!fields && !VARIABLE_TAIL.containsMatchIn(afterOpen)) return

        val form = SmartAppFieldRef.targetFormOf(literal, fileKind) ?: return
        val scope = SmartAppScopes.forPsiFile(originalFile)

        if (!fields) {
            // Каретка стоит на самом идентификаторе: предлагаем переменную формы.
            // Подпись — имя целевой формы: из текста её не видно, а именно она
            // решает, какие поля будут дальше.
            val prefix = IDENTIFIER_TAIL.find(decoded.text.substring(0, decodedCaret))?.value ?: ""
            result.withPrefixMatcher(prefix).addElement(
                marked(
                    LookupElementBuilder.create(JinjaSpec.formVariable)
                        .withTypeText(form)
                        .withInsertHandler(REPLACE_IDENTIFIER_TAIL),
                    SmartAppCompletionKind.VARIABLE,
                    FIELD_PRIORITY,
                ),
            )
            return
        }
        // Prefix перед кареткой — содержимое после последней точки в `main_form.`,
        // иначе платформа отфильтрует варианты по всему `main_form.` и они не
        // совпадут с именами полей. Пробел после точки в идентификатор не входит.
        val fieldPrefix = decoded.text.substring(0, decodedCaret).substringAfterLast('.')
            .dropWhile { it.isWhitespace() }
        val fieldResult = result.withPrefixMatcher(fieldPrefix)
        for (field in SmartAppFormFieldNameIndex.allNames(project, form, scope)) {
            // Поля с не-identifier именами (точки, двоеточия, дефисы) лексер
            // резолвить не способен — в completion не предлагаем (контракт v1;
            // синтаксис доступа к таким полям — отдельное проектирование).
            if (!isLexerIdentifier(field)) continue
            fieldResult.addElement(
                marked(
                    LookupElementBuilder.create(field)
                        .withTypeText("field")
                        .withInsertHandler(REPLACE_IDENTIFIER_TAIL),
                    SmartAppCompletionKind.FIELD,
                    FIELD_PRIORITY,
                ),
            )
        }
    }

    /**
     * Пути файлов каталогов файловой ссылки — относительно каталога поиска,
     * вместе с расширением: ровно то, что принимает резолв.
     *
     * Индекс здесь не участвует, поэтому dumb-guard'а нет: во время индексации
     * ветка отдаёт полный список, как и ветка ключевых слов `type`. Расширение,
     * наоборот, молчит до конца первичного сканирования — там источником служит
     * реестр, который в этот момент действительно неполон.
     */
    private fun addFileVariants(
        literal: JsonStringLiteral,
        parameters: CompletionParameters,
        result: CompletionResultSet,
    ) {
        // Корень набора берём у оригинального файла: in-memory копия теряет путь.
        val root = SmartAppFiles.referencesRoot(parameters.originalFile.virtualFile) ?: return
        // Префикс задаём сами: платформенный матчер режет путь по `/`, и вложенные
        // файлы переставали бы совпадать с набранным началом пути.
        val prefix = pathPrefixBeforeCaret(literal, parameters) ?: return
        val fileResult = result.withPrefixMatcher(prefix)

        for (path in SmartAppTemplateFiles.pathsIn(root, SmartAppFileRefRules.searchDirs(literal))) {
            if (!SmartAppFileRefRules.isOfferablePath(path)) continue
            fileResult.addElement(
                marked(
                    LookupElementBuilder.create(path).withTypeText("file"),
                    SmartAppCompletionKind.FILE,
                    FILE_PRIORITY,
                ),
            )
        }
    }

    /**
     * Часть значения до каретки в decoded-координатах: сравнивать префикс с
     * меткой нужно в той же системе координат, в какой резолвится сам путь.
     */
    private fun pathPrefixBeforeCaret(
        literal: JsonStringLiteral,
        parameters: CompletionParameters,
    ): String? {
        val rawCaret = parameters.offset - literal.textOffset - 1
        if (rawCaret < 0) return null
        val raw = SmartAppReferenceContributor.rawText(literal) ?: return null
        if (rawCaret > raw.length) return null
        val decoded = JsonStringLiteralDecoder.decode(raw)
        return decoded.text.substring(0, decodedCaretOf(decoded.decodedToRaw, rawCaret))
    }

    /**
     * `true`, если [name] — идентификатор в терминах лексера
     * (`isJavaIdentifierStart`/`isJavaIdentifierPart`): только такие имена полей
     * резолвятся из `{{ main_form.<field> }}`.
     */
    private fun isLexerIdentifier(name: String): Boolean =
        name.isNotEmpty() && name.first().isJavaIdentifierStart() &&
            name.asSequence().drop(1).all { it.isJavaIdentifierPart() }

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
     * Смещение конца открывающего разделителя (`{{` или `{%`), если позиция
     * [decodedCaret] находится внутри актуального открытого выражения, иначе
     * `null`. Каретка внутри Jinja-строки [SmartAppJinjaTokenType.STRING]
     * позицией поля не считается.
     */
    private fun exprContextAt(decodedText: String, decodedCaret: Int): Int? {
        var inExpr = false
        var exprOpenEnd = -1
        for (token in SmartAppJinjaLexer.tokenize(decodedText)) {
            if (token.range.startOffset >= decodedCaret) break
            when (token.type) {
                SmartAppJinjaTokenType.INTERP_OPEN, SmartAppJinjaTokenType.STATEMENT_OPEN -> {
                    inExpr = true
                    exprOpenEnd = token.range.endOffset
                }
                SmartAppJinjaTokenType.INTERP_CLOSE, SmartAppJinjaTokenType.STATEMENT_CLOSE ->
                    inExpr = false
                // Каретка строго внутри строки — это не позиция поля формы.
                SmartAppJinjaTokenType.STRING -> if (decodedCaret < token.range.endOffset) return null
                else -> {}
            }
        }
        return if (inExpr) exprOpenEnd else null
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
                    marked(
                        LookupElementBuilder.create(name).withTypeText(kind.name.lowercase()),
                        SmartAppCompletionKind.NAME,
                        NAME_PRIORITY,
                    ),
                )
            }
        }
    }

    /**
     * Вариант с приоритетом и видом. Вид — часть контракта поведения (`kind` в
     * корпусе): без него обе реализации могут предложить одинаковые метки,
     * означающие разное, и общий прогон этого не заметит.
     */
    private fun marked(
        builder: LookupElementBuilder,
        kind: SmartAppCompletionKind,
        priority: Double,
    ): LookupElement {
        val element = PrioritizedLookupElement.withPriority(builder, priority)
        element.putUserData(SmartAppCompletionKind.KEY, kind)
        return element
    }

    private companion object {
        const val KEYWORD_PRIORITY = 100.0
        const val NAME_PRIORITY = 50.0
        const val FIELD_PRIORITY = 30.0
        const val FILE_PRIORITY = 40.0

        // Хвост выражения перед кареткой, открывающий completion имени поля:
        // переменная формы, опциональные пробелы, точка, опциональный частичный
        // идентификатор (первый символ — identifierStart, как у VAR лексера) и
        // конец. Проверяется именно хвост: слева в выражении стоит ещё и `if `,
        // `set x = `, `not ` и т.п.
        //
        // Слева от переменной допустимо: начало выражения; любой символ, не
        // входящий в идентификатор и не точка (`(`, `,`, `=`); пробел, перед
        // которым нет точки. Исключение — `|`: после него Jinja ждёт имя
        // фильтра, а не значение. Отвергаются `variables.main_form.` и
        // `variables. main_form.` (чужое поле) и `xmain_form.` (другое имя).
        // Пробел без этого разбора отвергать нельзя: `{% if main_form.<caret> %}`
        // — обычнейшая позиция в бою, и именно она не работала.
        //
        // Хвост `x.` (цепочка) и `2` (не-идентификатор) completion не открывают.
        val MAIN_FORM_TAIL: Regex = Regex(
            """(?:^|[^\p{javaJavaIdentifierPart}.\s|]|(?<![.\s|])\s)\s*""" +
                Regex.escape(JinjaSpec.formVariable) +
                """\s*\.\s*(?:\p{javaJavaIdentifierStart}\p{javaJavaIdentifierPart}*)?$""",
        )

        // Хвост, открывающий completion самой переменной формы: начатый (возможно
        // пустой) идентификатор, перед которым нет точки. Левая граница — та же,
        // что у поля: `variables.mai` и `variables. mai` — чужой объект, а
        // `x | mai` — позиция имени фильтра.
        val VARIABLE_TAIL: Regex = Regex(
            """(?:^|[^\p{javaJavaIdentifierPart}.\s|]|(?<![.\s|])\s)\s*""" +
                """(?:\p{javaJavaIdentifierStart}\p{javaJavaIdentifierPart}*)?$""",
        )

        /** Набранное начало идентификатора непосредственно перед кареткой. */
        val IDENTIFIER_TAIL: Regex = Regex("""\p{javaJavaIdentifierPart}*$""")

        /**
         * Вставка заменяет слово целиком: хвост идентификатора справа от каретки
         * удаляется. Каретка посреди слова — обычное дело (`main_|form`), и без
         * этого получилось бы `main_formform`. В расширении ту же роль играет
         * диапазон замены варианта, так что поведение совпадает.
         */
        val REPLACE_IDENTIFIER_TAIL = InsertHandler<LookupElement> { context, _ ->
            val document = context.document
            val text = document.charsSequence
            var end = context.tailOffset
            while (end < document.textLength && text[end].isJavaIdentifierPart()) end++
            if (end > context.tailOffset) document.deleteString(context.tailOffset, end)
        }
    }
}
