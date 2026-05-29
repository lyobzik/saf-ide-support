package ru.sber.smartapp.dsl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Простые юнит-тесты загрузчика сгенерированного словаря ключевых слов. */
class SmartAppKeywordsTest {

    @Test
    fun loadsCategories() {
        assertTrue("form_filling" in SmartAppKeywords.all("scenario"))
        assertTrue("external" in SmartAppKeywords.all("filler"))
        assertTrue("run_scenario" in SmartAppKeywords.all("action"))
        assertTrue("question" in SmartAppKeywords.all("field_description"))
    }

    @Test
    fun isKeywordRespectsCategory() {
        assertTrue(SmartAppKeywords.isKeyword("scenario", "form_filling"))
        assertFalse(SmartAppKeywords.isKeyword("scenario", "approve"))
    }

    @Test
    fun isAnyKeywordUnionsCategories() {
        assertTrue(SmartAppKeywords.isAnyKeyword("approve"))
        assertTrue(SmartAppKeywords.isAnyKeyword("form_filling"))
        assertFalse(SmartAppKeywords.isAnyKeyword("definitely_not_a_keyword"))
    }

    @Test
    fun unknownCategoryIsEmpty() {
        assertEquals(emptySet<String>(), SmartAppKeywords.all("no_such_category"))
    }
}
