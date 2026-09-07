package ru.sber.smartapp.dsl.reference

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Грамматика идентификатора на общей таблице входов.
 *
 * Таблица — один файл `shared/predicates/attribute-names.json`, и её же читают
 * тест ядра расширения и тест генератора снимка. Копий три стороны не держат
 * намеренно: три копии расходятся быстрее двух, а расхождение здесь означало
 * бы разный состав словаря и разный список автодополнения в двух IDE.
 */
class SmartAppIdentifiersTest {

    private data class NameCase(
        val input: String,
        val addressable: Boolean,
        val field: Boolean,
        val why: String,
    )

    private val cases: List<NameCase> by lazy {
        val shared = System.getProperty("smartapp.shared")
            ?: error("system property 'smartapp.shared' is not set")
        val file = File(shared, "predicates/attribute-names.json")
        assertTrue("общая таблица имён не найдена: $file", file.isFile)
        JsonParser.parseString(file.readText()).asJsonObject
            .getAsJsonArray("names")
            .map { it.asJsonObject }
            .map {
                NameCase(
                    input = it["input"].asString,
                    addressable = it["addressable"].asBoolean,
                    field = it["field"].asBoolean,
                    why = it["why"].asString,
                )
            }
    }

    @Test
    fun testTableCoversBothSidesOfPredicate() {
        assertTrue("таблица подозрительно мала", cases.size > 10)
        assertTrue(cases.any { it.addressable })
        assertTrue(cases.any { !it.addressable })
        // Ведущее подчёркивание — единственный вход, где addressable и field
        // расходятся; без него колонка field не проверяла бы ничего.
        assertTrue(cases.any { it.addressable && !it.field })
    }

    @Test
    fun testAddressabilityMatchesSharedTable() {
        for (case in cases) {
            assertEquals(
                "адресуемость '${case.input}' — ${case.why}",
                case.addressable,
                SmartAppIdentifiers.isAddressableName(case.input),
            )
        }
    }

    @Test
    fun testFieldEligibilityMatchesSharedTable() {
        for (case in cases) {
            val asField = SmartAppIdentifiers.isAddressableName(case.input) &&
                !case.input.startsWith("_")
            assertEquals("годность как имени поля '${case.input}'", case.field, asField)
        }
    }

    @Test
    fun testLetterAndUnderscoreStartIdentifier() {
        assertTrue(SmartAppIdentifiers.isIdentifierStart('a'))
        assertTrue(SmartAppIdentifiers.isIdentifierStart('я'))
        assertTrue(SmartAppIdentifiers.isIdentifierStart('_'))
    }

    @Test
    fun testDecimalDigitContinuesButDoesNotStart() {
        assertFalse(SmartAppIdentifiers.isIdentifierStart('9'))
        assertTrue(SmartAppIdentifiers.isIdentifierPart('9'))
        // U+0663 — арабская десятичная, категория Nd: правила те же, что у ASCII.
        assertFalse(SmartAppIdentifiers.isIdentifierStart('٣'))
        assertTrue(SmartAppIdentifiers.isIdentifierPart('٣'))
    }

    @Test
    fun testNonLetterSymbolsAreNotIdentifiers() {
        // × ÷ — Sm, ² — No, ¡ — Po, $ — Sc. Первые три принимало приближение
        // диапазоном [À-￿] в порте расширения, последний — Character.isJavaIdentifierStart.
        for (c in listOf('×', '÷', '²', '¡', '$')) {
            assertFalse("'$c' не должен быть началом идентификатора", SmartAppIdentifiers.isIdentifierStart(c))
            assertFalse("'$c' не должен быть частью идентификатора", SmartAppIdentifiers.isIdentifierPart(c))
        }
    }

    @Test
    fun testLoneSurrogateIsNotIdentifier() {
        assertFalse(SmartAppIdentifiers.isIdentifierStart('\uD800'))
        assertFalse(SmartAppIdentifiers.isIdentifierPart('\uD800'))
    }
}
