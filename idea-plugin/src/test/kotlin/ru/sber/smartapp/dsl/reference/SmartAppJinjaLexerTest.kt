package ru.sber.smartapp.dsl.reference

import com.intellij.openapi.util.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
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
 * Unit-тесты детерминированного лексера Jinja без платформы IDEA. Проверяют
 * контракт: парные/непарные разделители, разделение интерполяции и
 * statement-тегов, FILTER_NAME, идентификаторы внутри STRING не токенизируются,
 * пропуск закрывающих разделителей внутри строк.
 *
 * Лексер принимает decoded-текст (JSON escape уже раскрыты); regression для
 * raw-escape проверяется отдельно через [JsonStringLiteralDecoder] в
 * интеграционных тестах.
 */
class SmartAppJinjaLexerTest {

    @Test
    fun noJinjaReturnsSingleText() {
        val tokens = SmartAppJinjaLexer.tokenize("just text")
        assertEquals(listOf(TEXT), tokens.map { it.type })
    }

    @Test
    fun simpleFieldReference() {
        val tokens = SmartAppJinjaLexer.tokenize("{{ main_form.name }}")
        //              012345678901234567890123
        assertEquals(
            listOf(INTERP_OPEN, VAR, DOT, VAR, INTERP_CLOSE),
            tokens.filterNot { it.type == TEXT }.map { it.type },
        )
        // Проверяем диапазон VAR(name).
        val nameVar = tokens.first { it.type == VAR && it.range.startOffset == 13 }
        assertEquals(TextRange(13, 17), nameVar.range)
    }

    @Test
    fun statementTagHasNoInterpTokens() {
        // {% if x %} — STATEMENT_OPEN/CLOSE, не INTERP.
        val types = SmartAppJinjaLexer.tokenize("{% if x %}").map { it.type }.toSet()
        assertTrue("STATEMENT_OPEN присутствует", types.contains(STATEMENT_OPEN))
        assertTrue("STATEMENT_CLOSE присутствует", types.contains(STATEMENT_CLOSE))
        assertFalse("INTERP быть не должно", types.contains(INTERP_OPEN))
        assertFalse("INTERP_CLOSE быть не должно", types.contains(INTERP_CLOSE))
    }

    @Test
    fun unpairedDelimIsText() {
        val tokens = SmartAppJinjaLexer.tokenize("a {{ x")
        assertFalse(tokens.any { it.type == INTERP_OPEN || it.type == INTERP_CLOSE })
        tokens.forEach { assertEquals("непарный разделитель -> только TEXT", TEXT, it.type) }
    }

    @Test
    fun multipleFragments() {
        val tokens = SmartAppJinjaLexer.tokenize("{{ a }} and {{ b }}")
        assertEquals(
            listOf(INTERP_OPEN, VAR, INTERP_CLOSE, INTERP_OPEN, VAR, INTERP_CLOSE),
            tokens.filterNot { it.type == TEXT }.map { it.type },
        )
    }

    @Test
    fun identifiersInsideStringAreNotTokenized() {
        val tokens = SmartAppJinjaLexer.tokenize("""{{ "main_form.name" }}""")
        assertEquals(listOf(INTERP_OPEN, STRING, INTERP_CLOSE), tokens.filterNot { it.type == TEXT }.map { it.type })
    }

    @Test
    fun filterOperatorAndName() {
        // Имя фильтра после | выделяется как FILTER_NAME (не VAR).
        val tokens = SmartAppJinjaLexer.tokenize("""{{ x | default('') }}""")
        val nonText = tokens.filterNot { it.type == TEXT }
        assertEquals(
            listOf(INTERP_OPEN, VAR, FILTER_OP, FILTER_NAME, STRING, INTERP_CLOSE),
            nonText.map { it.type },
        )
        // Диапазон FILTER_NAME точно на "default".
        val filterName = nonText.first { it.type == FILTER_NAME }
        assertEquals("default", """{{ x | default('') }}""".substring(filterName.range.startOffset, filterName.range.endOffset))
    }

    // ---- regression: закрывающий разделитель внутри строки --------------

    @Test
    fun closeDelimInsideSingleQuotedStringIsIgnored() {
        // {{ '}}' }} — }} внутри строки не закрывает интерполяцию.
        val tokens = SmartAppJinjaLexer.tokenize("{{ '}}' }}")
        val types = tokens.filterNot { it.type == TEXT }.map { it.type }
        assertEquals(listOf(INTERP_OPEN, STRING, INTERP_CLOSE), types)
    }

    @Test
    fun closeDelimInsideDoubleQuotedStringIsIgnored() {
        // На decoded-уровне двойные кавычки работают так же, как одинарные.
        val tokens = SmartAppJinjaLexer.tokenize("""{{ "}}" }}""")
        val types = tokens.filterNot { it.type == TEXT }.map { it.type }
        assertEquals(listOf(INTERP_OPEN, STRING, INTERP_CLOSE), types)
    }

    @Test
    fun statementCloseInsideStringIsIgnored() {
        // {% if x == '%}' %} — %} внутри строки не закрывает statement.
        // `if`=VAR, `x`=VAR, `==`=TEXT (не идентификатор), `'%}'`=STRING.
        val tokens = SmartAppJinjaLexer.tokenize("{% if x == '%}' %}")
        val types = tokens.filterNot { it.type == TEXT }.map { it.type }
        assertEquals(listOf(STATEMENT_OPEN, VAR, VAR, STRING, STATEMENT_CLOSE), types)
    }

    @Test
    fun jsonEscapeIsDecodedBeforeLexing() {
        // Raw JSON-представление: '\"' должно раскрываться до '"' до лексинга.
        // Декодер даёт {{ x | default("y") }}; лексер видит корректную строку.
        val decoded = JsonStringLiteralDecoder.decode("""{{ x | default(\"y\") }}""")
        val types = SmartAppJinjaLexer.tokenize(decoded.text).filterNot { it.type == TEXT }.map { it.type }
        assertEquals(listOf(INTERP_OPEN, VAR, FILTER_OP, FILTER_NAME, STRING, INTERP_CLOSE), types)
    }

    // ---- fieldCandidates: только интерполяция ---------------------------

    @Test
    fun fieldCandidatesFromInterpolationAndStatement() {
        // main_form.field даёт кандидата и внутри {{ }}, и внутри {% %}.
        val candidates = SmartAppJinjaLexer.fieldCandidates("{{ main_form.name }} {% set x = main_form.age %}")
        assertEquals(listOf("name", "age"), candidates.map { it.field })
    }

    // ---- вхождения переменной формы --------------------------------------

    @Test
    fun formVariableWithAndWithoutField() {
        val ranges = SmartAppJinjaLexer.formVariableRanges("{{ main_form }} {{ main_form.name }}")
        assertEquals(listOf(TextRange(3, 12), TextRange(19, 28)), ranges)
    }

    @Test
    fun formVariableInsideStatementTag() {
        val ranges = SmartAppJinjaLexer.formVariableRanges("{% if main_form.a %}")
        assertEquals(listOf(TextRange(6, 15)), ranges)
    }

    @Test
    fun formVariableAsNestedFieldIsNotAnOccurrence() {
        assertTrue(SmartAppJinjaLexer.formVariableRanges("{{ variables.main_form.name }}").isEmpty())
    }

    @Test
    fun formVariableOutsideExpressionIsNotAnOccurrence() {
        assertTrue(SmartAppJinjaLexer.formVariableRanges("main_form.name").isEmpty())
    }

    // ---- несколько интерполяций в одном литерале ------------------------

    @Test
    fun fieldCandidateInSecondInterpolation() {
        // Поле стоит во втором фрагменте: обход не должен заканчиваться первым.
        val candidates = SmartAppJinjaLexer.fieldCandidates("{{ x }} и {{ main_form.name }}")
        assertEquals(listOf("name"), candidates.map { it.field })
    }

    @Test
    fun fieldCandidatesFromEveryInterpolation() {
        val candidates =
            SmartAppJinjaLexer.fieldCandidates("{{ main_form.a }}-{{ main_form.b }}-{{ main_form.c }}")
        assertEquals(listOf("a", "b", "c"), candidates.map { it.field })
    }

    @Test
    fun statementTagBetweenInterpolationsDoesNotStopScan() {
        val candidates =
            SmartAppJinjaLexer.fieldCandidates("{% if main_form.a %}{{ main_form.b }}{% endif %}")
        assertEquals(listOf("a", "b"), candidates.map { it.field })
    }

    @Test
    fun fieldCandidatesAllowWhitespaceAroundDot() {
        // {{ main_form . name }} — whitespace между токенами допустим.
        val candidates = SmartAppJinjaLexer.fieldCandidates("{{ main_form . name }}")
        assertEquals(1, candidates.size)
        assertEquals("name", candidates[0].field)
    }

    // ---- regression: мусор между main_form и точкой ----------------------

    @Test
    fun garbageBetweenFormAndDotYieldsNoCandidate() {
        // {{ main_form + . unknown }} — не-whitespace TEXT между main_form и
        // точкой разрывает последовательность: ссылки на unknown не создаётся.
        val candidates = SmartAppJinjaLexer.fieldCandidates("{{ main_form + . unknown }}")
        assertTrue(candidates.isEmpty())
    }

    // ---- regression: цепочки вне scope `main_form.<field>` ---------------

    @Test
    fun mainFormAsNestedFieldYieldsNoCandidate() {
        // {{ variables.main_form.unknown }} — main_form в позиции чужого поля:
        // scope ограничен плоским main_form.<field>, кандидата нет.
        val candidates = SmartAppJinjaLexer.fieldCandidates("{{ variables.main_form.unknown }}")
        assertTrue(candidates.isEmpty())
    }

    @Test
    fun chainYieldsCandidateOnFirstSegment() {
        // {{ main_form.name.extra }} — name резолвится, хвост .extra семантики
        // не получает: схемы значений полей нет, резолвить `extra` не во что.
        val candidates = SmartAppJinjaLexer.fieldCandidates("{{ main_form.name.extra }}")
        assertEquals(listOf("name"), candidates.map { it.field })
    }

    @Test
    fun operandPositionStillYieldsCandidate() {
        // {{ x + main_form.name }} — main_form в позиции операнда: кандидат есть.
        val candidates = SmartAppJinjaLexer.fieldCandidates("{{ x + main_form.name }}")
        assertEquals(1, candidates.size)
        assertEquals("name", candidates[0].field)
    }

    // ---- regression: '\' перед закрывающим разделителем ------------------

    @Test
    fun unterminatedStringWithEscapeBeforeCloseDelimIsText() {
        // {{ 'a\}} — '\' перед }}: разделитель не находится, весь фрагмент
        // остаётся одним TEXT, STRING не перекрывает закрывающий разделитель.
        val tokens = SmartAppJinjaLexer.tokenize("{{ 'a\\}}")
        assertEquals(listOf(TEXT), tokens.map { it.type })
    }

    @Test
    fun tokenRangesAreOrderedAndWithinBoundsOnMalformedInput() {
        // Малформированные входы не должны давать пересекающихся диапазонов
        // или диапазонов за границами текста (риск для highlight).
        val inputs = listOf(
            "{{ 'a\\}}",
            "{{ \"x\\",
            "{{ '",
            "{{ main_form.",
            "{% if",
            "{{ '\\'%}",
            "{{ 'a\\' }}",
            "text {{ x }} {{ 'y\\' }} z",
        )
        for (input in inputs) {
            val tokens = SmartAppJinjaLexer.tokenize(input)
            var prevEnd = 0
            for (t in tokens) {
                assertTrue("start >= prevEnd для '$input': $t", t.range.startOffset >= prevEnd)
                assertTrue("end <= length для '$input': $t", t.range.endOffset <= input.length)
                prevEnd = t.range.endOffset
            }
        }
    }

    // --- rootAccesses: общий примитив под семантику формы и модели пользователя ---

    @Test
    fun testRootAccessGivesRootAndFirstSegment() {
        val accesses = SmartAppJinjaLexer.rootAccesses("{{ user.variables.smart_geo }}")
        assertEquals(1, accesses.size)
        val access = accesses[0]
        assertEquals("user", access.root)
        assertEquals(TextRange(3, 7), access.rootRange)
        assertEquals("variables", access.member)
        assertEquals(TextRange(8, 17), access.memberRange)
    }

    @Test
    fun testRootAccessWithoutDotHasNoMember() {
        val access = SmartAppJinjaLexer.rootAccesses("{{ user }}").single()
        assertEquals("user", access.root)
        assertEquals(null, access.member)
        assertEquals(null, access.memberRange)
    }

    @Test
    fun testRootAccessIsNotLimitedToFormVariable() {
        // Ключевые слова Jinja лексеру неизвестны, поэтому `if` — такой же
        // корень без сегмента; отсеивают их потребители по имени корня.
        val text = "{{ a.x }} {% if b.y %}"
        assertEquals(
            listOf("a.x", "if.null", "b.y"),
            SmartAppJinjaLexer.rootAccesses(text).map { "${it.root}.${it.member}" },
        )
    }

    @Test
    fun testFilterNameIsNotRoot() {
        // `{{ value | user }}` — применение фильтра с таким именем, а не
        // обращение к модели: идентификатор после `|` помечен FILTER_NAME.
        assertEquals(
            listOf("value"),
            SmartAppJinjaLexer.rootAccesses("{{ value | user }}").map { it.root },
        )
    }

    @Test
    fun testRootAccessInsideFilterArgument() {
        assertEquals(
            listOf("value.null", "user.field"),
            SmartAppJinjaLexer.rootAccesses("{{ value | default(user.field) }}")
                .map { "${it.root}.${it.member}" },
        )
    }

    @Test
    fun testRootAccessRespectsLeftBoundary() {
        assertEquals(
            listOf("variables.user"),
            SmartAppJinjaLexer.rootAccesses("{{ variables.user.x }}").map { "${it.root}.${it.member}" },
        )
    }

    // --- грамматика идентификатора внутри самого лексера ---

    private fun memberAfterDot(text: String): Pair<SmartAppJinjaTokenType, String> {
        val tokens = SmartAppJinjaLexer.tokenize(text)
        val dot = tokens.indexOfFirst { it.type == DOT }
        assertTrue("в '$text' нет точки", dot >= 0)
        val next = tokens[dot + 1]
        return next.type to text.substring(next.range.startOffset, next.range.endOffset)
    }

    @Test
    fun testCyrillicNameIsVar() {
        assertEquals(VAR to "я", memberAfterDot("{{ user.я }}"))
    }

    @Test
    fun testDigitContinuesName() {
        assertEquals(VAR to "имя9", memberAfterDot("{{ user.имя9 }}"))
    }

    @Test
    fun testDecimalDigitCannotStartName() {
        assertEquals(TEXT to "٣", memberAfterDot("{{ user.٣ }}"))
    }

    @Test
    fun testOtherNumberCategoryIsNotName() {
        assertEquals(TEXT to "²", memberAfterDot("{{ user.² }}"))
    }

    @Test
    fun testMathSymbolIsNotName() {
        assertEquals(TEXT to "×", memberAfterDot("{{ user.× }}"))
    }

    @Test
    fun testSupplementaryCharacterIsNotName() {
        // Обе UTF-16-единицы суррогатной пары уходят в один TEXT: ограничение
        // BMP и введено ради того, чтобы результат не зависел от способа обхода.
        assertEquals(TEXT to "\uD801\uDC00", memberAfterDot("{{ user.\uD801\uDC00 }}"))
    }
}
