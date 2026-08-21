package ru.sber.smartapp.dsl.completion

import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import ru.sber.smartapp.dsl.reference.SmartAppFileRefRules

/**
 * Автодополнение имён файлов шаблонов: значение `"file"` при
 * `"type": "unified_template"`.
 *
 * Изоляцию наборов и совпадение с расширением проверяет корпус
 * (`shared/fixtures/template-file-completion`); здесь — то, что видно только в
 * платформе: вложенный путь в lookup'е, чужой владелец и dumb mode.
 */
class SmartAppFileCompletionTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("static/references/templates/items.jinja2", "{{ x }}")
        myFixture.addFileToProject("static/references/templates/other.jinja2", "{{ x }}")
        myFixture.addFileToProject("static/references/templates/nested/deep.jinja2", "{{ x }}")
    }

    fun testOffersTemplateFilesIncludingNestedPath() {
        val items = completeFileValue("unified_template")
        assertTrue("expected template names, got $items", items.contains("items.jinja2"))
        assertTrue(items.contains("other.jinja2"))
        assertTrue("вложенный путь предлагается целиком, got $items", items.contains("nested/deep.jinja2"))
        // Каталог целью не является.
        assertFalse(items.contains("nested"))
    }

    fun testOtherOwnerTypeGetsNoTemplateNames() {
        val items = completeFileValue("string")
        assertFalse(
            "ключ file вне unified_template — не файловая ссылка, got $items",
            items.contains("items.jinja2"),
        )
    }

    /**
     * Ветка не читает индекс, поэтому во время индексации список полон — в
     * отличие от расширения, где источником служит реестр, неполный до конца
     * первичного сканирования. Асимметрия осознанная, поэтому и закреплена.
     */
    fun testTemplateFilesAreOfferedInDumbMode() {
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            val items = completeFileValue("unified_template", "dumb_form")
            assertTrue("dumb mode: $items", items.contains("items.jinja2"))
            assertTrue(items.contains("nested/deep.jinja2"))
        }
    }

    fun testOfferablePathPredicate() {
        // Та же таблица входов, что в тесте ядра расширения: предикат обязан
        // совпадать посимвольно, иначе один редактор предложит имя, а другой нет.
        assertTrue(SmartAppFileRefRules.isOfferablePath("items.jinja2"))
        assertTrue(SmartAppFileRefRules.isOfferablePath("nested/deep.jinja2"))
        assertTrue(
            "не-ASCII экранирования не требует",
            SmartAppFileRefRules.isOfferablePath("привет.jinja2"),
        )
        assertFalse(SmartAppFileRefRules.isOfferablePath("q\".jinja2"))
        assertFalse(SmartAppFileRefRules.isOfferablePath("back\\slash.jinja2"))
        assertFalse(SmartAppFileRefRules.isOfferablePath("bell\u0007.jinja2"))
    }

    private fun completeFileValue(ownerType: String, formName: String = "hello_form"): List<String> {
        val content = """
            { "$formName": { "fields": { "greeting": { "items": {
              "type": "$ownerType", "file": "<caret>" } } } } }
        """.trimIndent()
        val caret = content.indexOf("<caret>")
        val file = myFixture.addFileToProject(
            "static/references/forms/$formName.json",
            content.replace("<caret>", ""),
        )
        myFixture.openFileInEditor(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(caret)
        myFixture.completeBasic()
        return myFixture.lookupElementStrings ?: emptyList()
    }
}
