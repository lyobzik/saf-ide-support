package ru.sber.smartapp.dsl.reference

import com.intellij.openapi.util.TextRange
import ru.sber.smartapp.dsl.reference.SmartAppJinjaTokenType.DELIM_CLOSE
import ru.sber.smartapp.dsl.reference.SmartAppJinjaTokenType.DELIM_OPEN
import ru.sber.smartapp.dsl.reference.SmartAppJinjaTokenType.DOT
import ru.sber.smartapp.dsl.reference.SmartAppJinjaTokenType.FILTER_OP
import ru.sber.smartapp.dsl.reference.SmartAppJinjaTokenType.STRING
import ru.sber.smartapp.dsl.reference.SmartAppJinjaTokenType.TEXT
import ru.sber.smartapp.dsl.reference.SmartAppJinjaTokenType.VAR

/**
 * Токен Jinja-фрагмента внутри строкового литерала. Смещения [range] заданы
 * относительно **raw-текста** литерала (без JSON-кавычек) — см.
 * [SmartAppJinjaLexer].
 */
data class SmartAppJinjaToken(val type: SmartAppJinjaTokenType, val range: TextRange)

enum class SmartAppJinjaTokenType {
    /** `{{` или `{%` — открывающий разделитель выражения. */
    DELIM_OPEN,

    /** `}}` или `%}` — закрывающий разделитель выражения. */
    DELIM_CLOSE,

    /** Идентификатор-переменная (`main_form`, имя поля, имя фильтра, прочие). */
    VAR,

    /** Одиночная точка — доступ к полю объекта. */
    DOT,

    /** `|` — оператор применения фильтра. */
    FILTER_OP,

    /** Строковый литерал внутри Jinja (`"…"` / `'…'` с учётом escape). */
    STRING,

    /** Любой прочий текст (внутри `{{ }}` и снаружи выражений). */
    TEXT,
}

/**
 * Детерминированный лексер Jinja-фрагментов внутри строкового JSON-литерала.
 *
 * **Не полноценный Jinja-парсер**, а токенизатор для двух целей:
 * 1. Найти семантические ссылки `main_form.<id>` для резолва/completion.
 * 2. Разметить токены для подсветки (разделители, переменные, фильтры, строки).
 *
 * Контракт (см. план `2026-07-25-form-fields-and-jinja.md`, шаг 3):
 * - учитываются только **парные** `{{ … }}` и `{% … %}`; непарный разделитель до
 *   конца литерала не образует выражения — остаток целиком `TEXT`, ошибкой не
 *   становится;
 * - несколько фрагментов в одном литерале обрабатываются независимо;
 * - внутри выражения идентификаторы внутри [STRING] **не** токенизируются
 *   (`{{ "main_form.name" }}` → один `STRING`, а не `VAR`);
 * - `{% … %}` подсвечивается, но **не** порождает семантических ссылок.
 *
 * Все смещения [SmartAppJinjaToken.range] — относительно [rawText], переданного в
 * [tokenize]: raw-текст литерала без JSON-кавычек. Перевод в absolute-диапазоны
 * документа — ответственность вызывающего (см. комментарий к [tokenize]).
 */
object SmartAppJinjaLexer {

    /**
     * Токенизирует [rawText] (содержимое строкового литерала без крайних кавычек).
     * Возвращает упорядоченный список токенов; пустой, если Jinja-фрагментов нет.
     *
     * Перевод raw-диапазона токена в absolute: `literal.textOffset + 1 + rawStart`
     * ( +1 за открывающую JSON-кавычку).
     */
    fun tokenize(rawText: String): List<SmartAppJinjaToken> {
        val tokens = ArrayList<SmartAppJinjaToken>()
        var i = 0
        val n = rawText.length
        while (i < n) {
            val open = findOpenDelim(rawText, i) ?: run {
                // До конца литерала больше нет открывающих разделителей —
                // оставшийся хвост один TEXT-токен.
                if (i < n) tokens.add(token(TEXT, i, n))
                return tokens
            }
            // Текст перед выражением.
            if (open.start > i) tokens.add(token(TEXT, i, open.start))
            val close = findCloseDelim(rawText, open.end, open.type)
            if (close == null) {
                // Непарный разделитель: весь остаток — TEXT, ошибкой не отмечаем.
                tokens.add(token(TEXT, open.start, n))
                return tokens
            }
            tokens.add(token(DELIM_OPEN, open.start, open.end))
            tokenizeExpr(rawText, open.end, close.start, tokens)
            tokens.add(token(DELIM_CLOSE, close.start, close.end))
            i = close.end
        }
        return tokens
    }

    /**
     * Токенизирует внутренность `{{ … }}` / `{% … %}` — диапазон `(from, until)`,
     * не включая разделители. Идентификаторы внутри [STRING] не выделяются.
     */
    private fun tokenizeExpr(text: String, from: Int, until: Int, out: ArrayList<SmartAppJinjaToken>) {
        var i = from
        while (i < until) {
            val c = text[i]
            when {
                c.isWhitespace() -> {
                    val start = i
                    while (i < until && text[i].isWhitespace()) i++
                    out.add(token(TEXT, start, i))
                }
                c == '.' -> {
                    out.add(token(DOT, i, i + 1))
                    i++
                }
                c == '|' -> {
                    out.add(token(FILTER_OP, i, i + 1))
                    i++
                }
                c == '"' || c == '\'' -> {
                    val start = i
                    i++
                    while (i < until) {
                        val q = text[i]
                        if (q == '\\') { i += 2; continue }
                        if (q == c) { i++; break }
                        i++
                    }
                    out.add(token(STRING, start, i))
                }
                c.isJavaIdentifierStart() -> {
                    val start = i
                    i++
                    while (i < until && text[i].isJavaIdentifierPart()) i++
                    out.add(token(VAR, start, i))
                }
                else -> {
                    val start = i
                    while (i < until && !text[i].isWhitespace() &&
                        text[i] != '.' && text[i] != '|' &&
                        text[i] != '"' && text[i] != '\'' &&
                        !text[i].isJavaIdentifierStart()
                    ) {
                        i++
                    }
                    if (i == start) i++ // гарантия прогресса на непонятном символе
                    out.add(token(TEXT, start, i))
                }
            }
        }
    }

    private data class OpenDelim(val start: Int, val end: Int, val type: Char /* '{' или '%' */)

    private data class CloseDelim(val start: Int, val end: Int)

    private fun findOpenDelim(text: String, from: Int): OpenDelim? {
        val n = text.length
        var i = from
        while (i < n - 1) {
            if (text[i] == '{' && text[i + 1] == '{') return OpenDelim(i, i + 2, '{')
            if (text[i] == '{' && text[i + 1] == '%') return OpenDelim(i, i + 2, '%')
            i++
        }
        return null
    }

    private fun findCloseDelim(text: String, from: Int, openType: Char): CloseDelim? {
        // Парный закрыватель: `{{` → `}}`, `{%` → `%}`.
        val first = if (openType == '{') '}' else '%'
        val n = text.length
        var i = from
        while (i < n - 1) {
            if (text[i] == first && text[i + 1] == '}') {
                return CloseDelim(i, i + 2)
            }
            i++
        }
        return null
    }

    private fun token(type: SmartAppJinjaTokenType, start: Int, end: Int): SmartAppJinjaToken =
        SmartAppJinjaToken(type, TextRange(start, end))
}
