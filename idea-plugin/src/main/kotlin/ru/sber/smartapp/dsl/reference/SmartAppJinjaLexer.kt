package ru.sber.smartapp.dsl.reference

import com.intellij.openapi.util.TextRange
import ru.sber.smartapp.dsl.contract.JinjaSpec
import ru.sber.smartapp.dsl.reference.SmartAppJinjaTokenType.DOT
import ru.sber.smartapp.dsl.reference.SmartAppJinjaTokenType.FILTER_NAME
import ru.sber.smartapp.dsl.reference.SmartAppJinjaTokenType.FILTER_OP
import ru.sber.smartapp.dsl.reference.SmartAppJinjaTokenType.INTERP_CLOSE
import ru.sber.smartapp.dsl.reference.SmartAppJinjaTokenType.INTERP_OPEN
import ru.sber.smartapp.dsl.reference.SmartAppJinjaTokenType.STATEMENT_CLOSE
import ru.sber.smartapp.dsl.reference.SmartAppJinjaTokenType.STATEMENT_OPEN
import ru.sber.smartapp.dsl.reference.SmartAppJinjaTokenType.STRING
import ru.sber.smartapp.dsl.reference.SmartAppJinjaTokenType.TEXT
import ru.sber.smartapp.dsl.reference.SmartAppJinjaTokenType.VAR

/**
 * Тип токена Jinja-фрагмента внутри строкового JSON-литерала. Все смещения
 * токена (см. [SmartAppJinjaToken.range]) заданы относительно **decoded**-текста
 * литерала (JSON-строка с раскрытыми escape, без крайних кавычек) — того, что
 * пользователь фактически видит как Jinja.
 */
enum class SmartAppJinjaTokenType {
    /** `{{` — открывающий разделитель интерполяции (семантического выражения). */
    INTERP_OPEN,

    /** `}}` — закрывающий разделитель интерполяции. */
    INTERP_CLOSE,

    /** `{%` — открывающий разделитель statement-тега. */
    STATEMENT_OPEN,

    /** `%}` — закрывающий разделитель statement-тега. */
    STATEMENT_CLOSE,

    /** Идентификатор-переменная (`main_form`, имя поля). */
    VAR,

    /** Одиночная точка — доступ к полю объекта. */
    DOT,

    /** `|` — оператор применения фильтра. */
    FILTER_OP,

    /** Имя фильтра (идентификатор сразу после [FILTER_OP]). */
    FILTER_NAME,

    /** Строковый литерал внутри Jinja (`"…"` / `'…'`). */
    STRING,

    /** Любой прочий текст (внутри выражения и снаружи). */
    TEXT,
}

/** Токен с типом и диапазоном относительно decoded-текста литерала. */
data class SmartAppJinjaToken(val type: SmartAppJinjaTokenType, val range: TextRange)

/**
 * Кандидат на семантическую ссылку `main_form.<field>` внутри выражения Jinja.
 * [fieldRange] — диапазон имени поля в decoded-координатах литерала.
 */
data class SmartAppFieldCandidate(val form: String, val field: String, val fieldRange: TextRange)

/**
 * Детерминированный лексер Jinja-фрагментов внутри строкового JSON-литерала.
 *
 * **Не полноценный Jinja-парсер**, а токенизатор для двух целей:
 * 1. Найти семантические ссылки `main_form.<id>` внутри выражений — как в
 *    интерполяциях `{{ … }}`, так и в statement-тегах `{% … %}`.
 * 2. Разметить токены для подсветки.
 *
 * Контракт:
 * - разделители учитываются только парные, вне строковых литералов Jinja
 *   (`{{ '}}' }}` корректно — `}}` внутри строки не закрывает выражение);
 * - JSON escape (`\"`, `\\`, …) раскрываются до токенизации тел, поэтому Jinja с
 *   двойными строками в JSON-представлении
 *   `"{{ x | default(\"y\") }}"` разбирается верно;
 * - несколько фрагментов обрабатываются независимо;
 * - внутри выражения идентификаторы внутри [SmartAppJinjaTokenType.STRING]
 *   **не** токенизируются (`{{ "main_form.name" }}` → один STRING);
 * - разделители `{% … %}` и `{{ … }}` различаются типом токена, но семантика
 *   полей работает в обоих;
 * - после `|` идентификатор помечается [SmartAppJinjaTokenType.FILTER_NAME].
 *
 * Все смещения — относительно [decodedText], переданного в [tokenize]. Перевод в
 * absolute-диапазоны документа — ответственность вызывающего через карту
 * decoded→raw (см. [JsonStringLiteralDecoder]).
 */
object SmartAppJinjaLexer {

    /**
     * Токенизирует [decodedText] (содержимое литерала без крайних кавычек, с
     * раскрытыми JSON escape). Возвращает упорядоченный список токенов.
     */
    fun tokenize(decodedText: String): List<SmartAppJinjaToken> {
        val tokens = ArrayList<SmartAppJinjaToken>()
        var i = 0
        val n = decodedText.length
        while (i < n) {
            val open = findOpenDelim(decodedText, i) ?: run {
                if (i < n) tokens.add(token(TEXT, i, n))
                return tokens
            }
            if (open.start > i) tokens.add(token(TEXT, i, open.start))
            val close = findCloseDelim(decodedText, open.end, open.kind)
            if (close == null) {
                // Непарный разделитель: весь остаток — TEXT, ошибкой не отмечаем.
                tokens.add(token(TEXT, open.start, n))
                return tokens
            }
            tokens.add(token(open.openType, open.start, open.end))
            tokenizeExpr(decodedText, open.end, close.start, tokens)
            tokens.add(token(open.closeType, close.start, close.end))
            i = close.end
        }
        return tokens
    }

    /**
     * Находит семантические кандидаты `main_form.<id>` внутри выражений Jinja —
     * и интерполяций `{{ … }}`, и statement-тегов `{% … %}`: в бою обращение к
     * полю одинаково часто стоит в `{% if main_form.x %}`.
     *
     * Допускает whitespace между `main_form`, `.` и именем поля. Кандидатом
     * становится **первый** сегмент: `main_form.a.b` даёт `a` (хвост цепочки
     * семантики не получает — у значений полей нет схемы). Левая граница
     * сохраняется: `main_form` в позиции чужого поля (`variables.main_form.x`)
     * кандидата не даёт. Кандидаты возвращаются в порядке встречи.
     */
    fun fieldCandidates(decodedText: String): List<SmartAppFieldCandidate> {
        val tokens = tokenize(decodedText)
        val result = ArrayList<SmartAppFieldCandidate>()
        var i = 0
        while (i < tokens.size) {
            val type = tokens[i].type
            if (type != INTERP_OPEN && type != STATEMENT_OPEN) {
                i++
                continue
            }
            i = collectFromExpr(decodedText, tokens, i, result)
        }
        return result
    }

    /**
     * Диапазоны вхождений переменной формы (`main_form`) внутри выражений Jinja.
     *
     * Левая граница та же, что у [fieldCandidates]: `variables.main_form` — это
     * обращение к чужому полю, вхождением переменной формы оно не считается.
     * В отличие от кандидатов, следующая за переменной точка не обязательна:
     * `{{ main_form }}` тоже указывает на форму.
     */
    fun formVariableRanges(decodedText: String): List<TextRange> {
        val tokens = tokenize(decodedText)
        val result = ArrayList<TextRange>()
        for (i in tokens.indices) {
            val t = tokens[i]
            if (t.type != VAR) continue
            val text = decodedText.substring(t.range.startOffset, t.range.endOffset)
            if (text != JinjaSpec.formVariable) continue
            val prev = neighborSkippingWhitespace(decodedText, tokens, i - 1, -1)
            if (prev != null && prev.type == DOT) continue
            result.add(t.range)
        }
        return result
    }

    /** Токенизирует тело выражения — диапазон (from, until) без разделителей. */
    private fun tokenizeExpr(text: String, from: Int, until: Int, out: ArrayList<SmartAppJinjaToken>) {
        var i = from
        var afterFilter = false
        while (i < until) {
            val c = text[i]
            when {
                c.isWhitespace() -> {
                    val start = i
                    while (i < until && text[i].isWhitespace()) i++
                    out.add(token(TEXT, start, i))
                    // afterFilter намеренно не сбрасываем: имя фильтра может
                    // стоять через whitespace после `|`.
                }
                c == '.' -> {
                    out.add(token(DOT, i, i + 1))
                    i++
                    afterFilter = false
                }
                c == '|' -> {
                    out.add(token(FILTER_OP, i, i + 1))
                    i++
                    afterFilter = true
                }
                c == '"' || c == '\'' -> {
                    val start = i
                    i++
                    while (i < until) {
                        val q = text[i]
                        if (q == '\\') {
                            // Ограничиваем переход, чтобы `\` у самой границы
                            // выражения не увёл i за until и не перекрыл
                            // закрывающий разделитель.
                            i = minOf(i + 2, until)
                            continue
                        }
                        if (q == c) { i++; break }
                        i++
                    }
                    out.add(token(STRING, start, i))
                    afterFilter = false
                }
                c.isJavaIdentifierStart() -> {
                    val start = i
                    i++
                    while (i < until && text[i].isJavaIdentifierPart()) i++
                    out.add(token(if (afterFilter) FILTER_NAME else VAR, start, i))
                    afterFilter = false
                }
                else -> {
                    // Символ не матчит ни одну ветку выше — while гарантированно
                    // сделает хотя бы одну итерацию, пустого диапазона не будет.
                    val start = i
                    while (i < until && !text[i].isWhitespace() &&
                        text[i] != '.' && text[i] != '|' &&
                        text[i] != '"' && text[i] != '\'' &&
                        !text[i].isJavaIdentifierStart()
                    ) {
                        i++
                    }
                    out.add(token(TEXT, start, i))
                    afterFilter = false
                }
            }
        }
    }

    /**
     * Собирает кандидатов из одного выражения, начатого токеном [openIdx]
     * (`{{` или `{%`). Возвращает индекс, с которого продолжать обход: одно
     * выражение может содержать несколько вхождений `main_form.<field>`, а
     * литерал — несколько выражений.
     */
    private fun collectFromExpr(
        text: String,
        tokens: List<SmartAppJinjaToken>,
        openIdx: Int,
        result: MutableList<SmartAppFieldCandidate>,
    ): Int {
        var j = openIdx + 1
        while (j < tokens.size) {
            val t = tokens[j]
            if (t.type == INTERP_CLOSE || t.type == STATEMENT_CLOSE) return j + 1
            // Вложенных выражений быть не может, но на малформированном входе
            // открывающий разделитель встретиться способен — отдаём его наружу.
            if (t.type == INTERP_OPEN || t.type == STATEMENT_OPEN) return j
            if (t.type == VAR &&
                text.substring(t.range.startOffset, t.range.endOffset) == JinjaSpec.formVariable
            ) {
                // Левая граница: main_form в позиции чужого поля
                // (variables.main_form.x) — не наша семантика, пропускаем.
                val prev = neighborSkippingWhitespace(text, tokens, j - 1, -1)
                if (prev != null && prev.type == DOT) {
                    j++
                    continue
                }
                val dot = nextSkippingWhitespace(text, tokens, j + 1)
                val field = if (dot != null && dot.type == DOT)
                    nextSkippingWhitespace(text, tokens, tokens.indexOf(dot) + 1) else null
                if (field != null && field.type == VAR) {
                    val fieldIdx = tokens.indexOf(field)
                    val fieldName = text.substring(field.range.startOffset, field.range.endOffset)
                    result.add(SmartAppFieldCandidate(JinjaSpec.formVariable, fieldName, field.range))
                    j = fieldIdx + 1
                    continue
                }
            }
            j++
        }
        return j
    }

    /**
     * Следующий токен, пропуская только TEXT, состоящий исключительно из
     * whitespace. Прочий TEXT (например `+` или иной мусор между `main_form` и
     * `.`) останавливает поиск — чтобы `{{ main_form + . unknown }}` не стал
     * ложной ссылкой на `unknown`.
     */
    private fun nextSkippingWhitespace(
        text: String,
        tokens: List<SmartAppJinjaToken>,
        fromIdx: Int,
    ): SmartAppJinjaToken? {
        var k = fromIdx
        while (k < tokens.size) {
            val t = tokens[k]
            if (t.type != TEXT) return t
            val slice = text.substring(t.range.startOffset, t.range.endOffset)
            if (!slice.all { it.isWhitespace() }) return null
            k++
        }
        return null
    }

    /**
     * Соседний токен в направлении [step] (+1/-1), пропуская только TEXT из
     * whitespace. В отличие от [nextSkippingWhitespace], не-whitespace TEXT —
     * полноценный сосед: для проверки границ триплета важен именно DOT.
     */
    private fun neighborSkippingWhitespace(
        text: String,
        tokens: List<SmartAppJinjaToken>,
        fromIdx: Int,
        step: Int,
    ): SmartAppJinjaToken? {
        var k = fromIdx
        while (k in tokens.indices) {
            val t = tokens[k]
            if (t.type != TEXT) return t
            val slice = text.substring(t.range.startOffset, t.range.endOffset)
            if (!slice.all { it.isWhitespace() }) return t
            k += step
        }
        return null
    }

    private enum class DelimKind { INTERP, STATEMENT }

    private data class OpenDelim(val start: Int, val end: Int, val kind: DelimKind) {
        val openType: SmartAppJinjaTokenType get() =
            if (kind == DelimKind.INTERP) INTERP_OPEN else STATEMENT_OPEN
        val closeType: SmartAppJinjaTokenType get() =
            if (kind == DelimKind.INTERP) INTERP_CLOSE else STATEMENT_CLOSE
    }

    private data class CloseDelim(val start: Int, val end: Int)

    /** Находит `{{` или `{%`, начиная с [from]; иначе `null`. */
    private fun findOpenDelim(text: String, from: Int): OpenDelim? {
        val n = text.length
        var i = from
        while (i < n - 1) {
            if (text[i] == '{') {
                if (text[i + 1] == '{') return OpenDelim(i, i + 2, DelimKind.INTERP)
                if (text[i + 1] == '%') return OpenDelim(i, i + 2, DelimKind.STATEMENT)
            }
            i++
        }
        return null
    }

    /**
     * Находит парный `}}`/`%}` начиная с [from], **пропуская строковые литералы**
     * Jinja (одинарные и двойные кавычки с учётом `\`-escape). Иначе `}}`/`%}`
     * внутри строки преждевременно закрыли бы выражение.
     */
    private fun findCloseDelim(text: String, from: Int, kind: DelimKind): CloseDelim? {
        val first = if (kind == DelimKind.INTERP) '}' else '%'
        val n = text.length
        var i = from
        while (i < n - 1) {
            val c = text[i]
            if (c == '"' || c == '\'') {
                // Пропускаем строковый литерал Jinja целиком.
                i++
                while (i < n) {
                    if (text[i] == '\\') { i += 2; continue }
                    if (text[i] == c) { i++; break }
                    i++
                }
                continue
            }
            if (c == first && text[i + 1] == '}') {
                return CloseDelim(i, i + 2)
            }
            i++
        }
        return null
    }

    private fun token(type: SmartAppJinjaTokenType, start: Int, end: Int): SmartAppJinjaToken =
        SmartAppJinjaToken(type, TextRange(start, end))
}
