package ru.sber.smartapp.dsl.reference

import com.intellij.openapi.util.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import ru.sber.smartapp.dsl.reference.SmartAppJinjaTokenType.DELIM_CLOSE
import ru.sber.smartapp.dsl.reference.SmartAppJinjaTokenType.DELIM_OPEN
import ru.sber.smartapp.dsl.reference.SmartAppJinjaTokenType.DOT
import ru.sber.smartapp.dsl.reference.SmartAppJinjaTokenType.FILTER_OP
import ru.sber.smartapp.dsl.reference.SmartAppJinjaTokenType.STRING
import ru.sber.smartapp.dsl.reference.SmartAppJinjaTokenType.TEXT
import ru.sber.smartapp.dsl.reference.SmartAppJinjaTokenType.VAR

/**
 * Unit-тесты детерминированного лексера Jinja-фрагментов без платформы IDEA.
 * Проверяют контракт (парные/непарные разделители, несколько фрагментов,
 * идентификаторы внутри STRING не токенизируются) — основа для резолва и
 * подсветки в последующих шагах.
 */
class SmartAppJinjaLexerTest {

    @Test
    fun noJinjaReturnsSingleText() {
        // Без Jinja весь литерал — один TEXT (без выражений нет семантики).
        val tokens = SmartAppJinjaLexer.tokenize("just text")
        assertEquals(listOf(TEXT), tokens.map { it.type })
    }

    @Test
    fun simpleFieldReference() {
        val tokens = SmartAppJinjaLexer.tokenize("{{ main_form.name }}")
        //                    012345678901234567890123
        assertEquals(
            listOf(
                SmartAppJinjaToken(DELIM_OPEN, TextRange(0, 2)),
                SmartAppJinjaToken(VAR, TextRange(3, 12)),       // main_form
                SmartAppJinjaToken(DOT, TextRange(12, 13)),
                SmartAppJinjaToken(VAR, TextRange(13, 17)),      // name
                SmartAppJinjaToken(DELIM_CLOSE, TextRange(18, 20)),
            ),
            tokens.filterNot { it.type == TEXT },
        )
    }

    @Test
    fun statementTagHasNoSemanticTokens() {
        // {% if x %} — токенизируется, но caller не создаёт ссылок из VAR.
        val tokens = SmartAppJinjaLexer.tokenize("{% if x %}")
        assertEquals(listOf(DELIM_OPEN, VAR, VAR, DELIM_CLOSE), tokens.filterNot { it.type == TEXT }.map { it.type })
    }

    @Test
    fun unpairedDelimIsText() {
        // Нет закрывающего — нет DELIM_OPEN/CLOSE, только TEXT-фрагменты
        // (разделитель не начинает выражение и входит в TEXT).
        val tokens = SmartAppJinjaLexer.tokenize("a {{ x")
        assertFalse(tokens.any { it.type == DELIM_OPEN || it.type == DELIM_CLOSE })
        tokens.forEach { assertEquals("непарный разделитель -> только TEXT", TEXT, it.type) }
    }

    @Test
    fun multipleFragments() {
        val tokens = SmartAppJinjaLexer.tokenize("{{ a }} and {{ b }}")
        // Два независимых выражения; между ними TEXT. Проверяем структуру
        // ключевых токенов, игнорируя whitespace-TEXT.
        assertEquals(
            listOf(DELIM_OPEN, VAR, DELIM_CLOSE, DELIM_OPEN, VAR, DELIM_CLOSE),
            tokens.filterNot { it.type == TEXT }.map { it.type },
        )
    }

    @Test
    fun identifiersInsideStringAreNotTokenized() {
        // "main_form.name" внутри строкового литерала Jinja — один STRING.
        val tokens = SmartAppJinjaLexer.tokenize("""{{ "main_form.name" }}""")
        assertEquals(listOf(DELIM_OPEN, STRING, DELIM_CLOSE), tokens.filterNot { it.type == TEXT }.map { it.type })
    }

    @Test
    fun filterOperatorAndArgs() {
        val tokens = SmartAppJinjaLexer.tokenize("""{{ x | default("") }}""")
        val nonText = tokens.filterNot { it.type == TEXT }.map { it.type }
        assertEquals(listOf(DELIM_OPEN, VAR, FILTER_OP, VAR, STRING, DELIM_CLOSE), nonText)
    }
}
