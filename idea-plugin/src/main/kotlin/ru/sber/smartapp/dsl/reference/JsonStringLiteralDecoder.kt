package ru.sber.smartapp.dsl.reference

import com.intellij.json.psi.JsonStringLiteral

/**
 * Декодер JSON-строкового литерала: строит decoded-текст (с раскрытыми escape)
 * и карту смещений decoded→raw, чтобы лексер работал с раскрытым Jinja-кодом,
 * а диапазоны токенов транслировались обратно в координаты исходного JSON.
 *
 * Решает проблему: в JSON-представлении Jinja с двойной строкой выглядит как
 * `{{ x | default(\"y\") }}`; лексер, работающий по raw, воспринял бы `\"`
 * неверно. Декодер сначала раскрывает `\"`→`"`, `\\`→`\`, `\n`→новая строка и т.д.,
 * и лексер видит корректный `{{ x | default("y") }}`.
 */
object JsonStringLiteralDecoder {

    /** Результат декодирования: decoded-текст и карта decoded→raw смещений. */
    data class Decoded(val text: String, val decodedToRaw: IntArray)

    /**
     * Декодирует raw-содержимое литерала (без крайних кавычек). Возвращает
     * decoded-текст и карту, где `decodedToRaw[decodedOffset] = rawOffset`.
     */
    fun decode(raw: String): Decoded {
        val sb = StringBuilder(raw.length)
        // decodedToRaw[i] = raw-смещение, с которого начинается decoded-символ i.
        // Длина на 1 больше decoded-длины, чтобы покрывать endOffset.
        val rawStarts = IntArray(raw.length + 1)
        var i = 0
        val n = raw.length
        while (i < n) {
            val c = raw[i]
            val decodedPos = sb.length
            if (c == '\\' && i + 1 < n) {
                val next = raw[i + 1]
                // Раскрываем типичные escape; неизвестные — оставляем как есть.
                val decoded = when (next) {
                    '"' -> '"'; '\\' -> '\\'; '/' -> '/'
                    'b' -> '\b'; 'f' -> '\u000C'; 'n' -> '\n'
                    'r' -> '\r'; 't' -> '\t'
                    else -> null
                }
                if (decoded != null) {
                    rawStarts[decodedPos] = i
                    sb.append(decoded)
                    i += 2
                    continue
                }
                // \uXXXX — 6 символов исходника → 1 decoded-символ.
                if (next == 'u' && i + 5 < n) {
                    val cp = raw.substring(i + 2, i + 6).toIntOrNull(16)
                    if (cp != null) {
                        rawStarts[decodedPos] = i
                        sb.appendCodePoint(cp)
                        i += 6
                        continue
                    }
                }
            }
            rawStarts[decodedPos] = i
            sb.append(c)
            i++
        }
        // Sentinel: end-смещение последнего decoded-символа.
        rawStarts[sb.length] = n
        return Decoded(sb.toString(), rawStarts)
    }

    /**
     * Raw-содержимое литерала (без крайних JSON-кавычек) или `null`, если литерал
     * слишком короткий/нестандартный. Без декодирования — для caller'ов, которым
     * нужны символы исходника (например, completion по raw до каретки).
     */
    fun rawText(literal: JsonStringLiteral): String? {
        val text = literal.text
        if (text.length < 2) return null
        if (text.first() != '"' || text.last() != '"') return null
        return text.substring(1, text.length - 1)
    }
}
