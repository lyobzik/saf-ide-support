package ru.sber.smartapp.dsl

import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonProperty
import com.intellij.psi.ElementDescriptionLocation
import com.intellij.psi.ElementDescriptionProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.usageView.UsageViewLongNameLocation
import com.intellij.usageView.UsageViewTypeLocation
import ru.sber.smartapp.dsl.findusages.SmartAppElementDescriptionProvider
import ru.sber.smartapp.dsl.findusages.SmartAppFindUsagesProvider

/**
 * Тесты Find Usages: проверка провайдеров [SmartAppFindUsagesProvider] и
 * [SmartAppElementDescriptionProvider] напрямую (unit) плюс интеграционный прогон
 * [com.intellij.testFramework.fixtures.CodeInsightTestFixture.findUsages].
 *
 * Фикстуры определения добавляются по корректным путям `static/references/...`,
 * чтобы заработали определение вида файла и индексация.
 */
class SmartAppFindUsagesTest : BasePlatformTestCase() {

    private val provider = SmartAppFindUsagesProvider()
    private val descriptionProvider = SmartAppElementDescriptionProvider()

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "static/references/forms/forms.json",
            """
            {
              "hello_form": {
                "type": "form",
                "fields": { "name": { "type": "question" } }
              },
              "second_form": { "type": "base" }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "static/references/scenarios/other_scenario.json",
            """{ "other_scenario": { "type": "base" } }""",
        )
        myFixture.addFileToProject(
            "static/references/actions/actions.json",
            """{ "my_action": { "type": "sdk_answer" } }""",
        )
        myFixture.addFileToProject(
            "static/references/behaviors/behaviors.json",
            """{ "my_behavior": { "type": "base" } }""",
        )
        myFixture.addFileToProject(
            "static/references/field_fillers/field_fillers.json",
            """{ "my_filler": { "type": "approve" } }""",
        )
        myFixture.addFileToProject(
            "static/references/classifiers/classifiers.json",
            """{ "my_classifier": { "type": "external" } }""",
        )
    }

    // ---- F1: canFindUsagesFor на top-level определении -----------------

    fun testFindUsagesProviderCanFindUsagesForTopLevelDefinition() {
        val prop = findTopLevelProperty("static/references/forms/forms.json", "hello_form")
        assertTrue("top-level form definition must allow Find Usages", provider.canFindUsagesFor(prop))
    }

    // ---- F2: canFindUsagesFor для полей формы и произвольных вложенных ----

    fun testFindUsagesProviderAcceptsFormField() {
        // Поле формы (внутри `fields`) — теперь first-class определение для
        // Find Usages: на него навешены SmartAppFieldReference из Jinja.
        val field = findProperty("static/references/forms/forms.json", "name")
        assertTrue("form field must allow Find Usages", provider.canFindUsagesFor(field))
        assertEquals("field", provider.getType(field))
    }

    fun testFindUsagesProviderRejectsArbitraryNestedProperty() {
        // Произвольное вложенное свойство (не top-level и не поле формы) —
        // отклоняется, его обрабатывает встроенный JSON-провайдер. `type` здесь —
        // свойство внутри поля формы `name`.
        val nested = findProperty("static/references/forms/forms.json", "type")
        assertFalse("arbitrary nested property must NOT allow Find Usages", provider.canFindUsagesFor(nested))
    }

    // ---- F3: canFindUsagesFor отклоняет non-DSL файл -------------------

    fun testFindUsagesProviderRejectsNonDslFile() {
        val file = myFixture.addFileToProject(
            "other/place/foo.json",
            """{ "x": { "type": "form" } }""",
        )
        val prop = findPropertyIn(file, "x")
        assertFalse("property outside static/references must NOT allow Find Usages", provider.canFindUsagesFor(prop))
    }

    // ---- F4: getType → lowercase kind по каждому SmartAppRefKind -------

    fun testGetTypeReturnsLowercasedKind() {
        // Каждый вид сущности должен давать свой lowercase-тип; это
        // цементирует соответствие enum -> подпись в окне Find Usages.
        assertEquals("scenario", typeOf("scenarios/other_scenario.json", "other_scenario"))
        assertEquals("form", typeOf("forms/forms.json", "hello_form"))
        assertEquals("action", typeOf("actions/actions.json", "my_action"))
        assertEquals("behavior", typeOf("behaviors/behaviors.json", "my_behavior"))
        assertEquals("filler", typeOf("field_fillers/field_fillers.json", "my_filler"))
        assertEquals("classifier", typeOf("classifiers/classifiers.json", "my_classifier"))
    }

    // ---- F5: getDescriptiveName/getNodeText → имя свойства -------------

    fun testGetDescriptiveNameAndNodeTextReturnPropertyName() {
        val prop = findTopLevelProperty("static/references/forms/forms.json", "hello_form")
        assertEquals("hello_form", provider.getDescriptiveName(prop))
        assertEquals("hello_form", provider.getNodeText(prop, useFullName = false))
    }

    // ---- F6: getType fallback "entity" на non-DSL ----------------------

    fun testGetTypeFallsBackToEntityForNonDsl() {
        val file = myFixture.addFileToProject(
            "other/place/foo.json",
            """{ "x": { "type": "form" } }""",
        )
        val prop = findPropertyIn(file, "x")
        assertEquals("entity", provider.getType(prop))
    }

    // ---- F7: ElementDescription TypeLocation → kind --------------------

    fun testElementDescriptionTypeLocation() {
        val prop = findTopLevelProperty("static/references/forms/forms.json", "hello_form")
        assertEquals("form", descriptionProvider.getElementDescription(prop, UsageViewTypeLocation.INSTANCE))
    }

    // ---- F8: ElementDescription LongNameLocation → имя -----------------

    fun testElementDescriptionLongNameLocation() {
        val prop = findTopLevelProperty("static/references/forms/forms.json", "hello_form")
        assertEquals(
            "hello_form",
            descriptionProvider.getElementDescription(prop, UsageViewLongNameLocation.INSTANCE),
        )
    }

    // ---- F9: ElementDescription null на non-DSL/не-JsonProperty --------

    fun testElementDescriptionReturnsNullForNonDsl() {
        val file = myFixture.addFileToProject(
            "other/place/foo.json",
            """{ "x": { "type": "form" } }""",
        )
        val prop = findPropertyIn(file, "x")
        assertNull(descriptionProvider.getElementDescription(prop, UsageViewTypeLocation.INSTANCE))
        // На произвольном PsiElement (не JsonProperty) — тоже null.
        val someElement: PsiElement = file
        assertNull(descriptionProvider.getElementDescription(someElement, UsageViewTypeLocation.INSTANCE))
    }

    // ---- F10: ElementDescription null на неизвестной location ----------

    fun testElementDescriptionReturnsNullForUnknownLocation() {
        val prop = findTopLevelProperty("static/references/forms/forms.json", "hello_form")
        // Произвольная location, не TypeLocation/LongNameLocation: провайдер
        // должен попасть в ветку `else` и вернуть null.
        val unknownLocation = object : ElementDescriptionLocation() {
            override fun getDefaultProvider(): ElementDescriptionProvider? = null
        }
        assertNull(
            "unsupported location must fall through to null",
            descriptionProvider.getElementDescription(prop, unknownLocation),
        )
    }

    // ---- F11: интеграция — findUsages находит ссылку в сценарии --------

    fun testFindUsagesIntegrationFindsReferenceInScenario() {
        myFixture.addFileToProject(
            "static/references/scenarios/consumer.json",
            """{ "consumer": { "type": "form_filling", "form": "hello_form" } }""",
        )
        val helloFormProp = findTopLevelProperty("static/references/forms/forms.json", "hello_form")
        val usages = myFixture.findUsages(helloFormProp)
        assertTrue(
            "expected a usage of 'hello_form' in the consumer scenario, got: $usages",
            usages.any { it.element?.containingFile?.name == "consumer.json" },
        )
    }

    // ---- вспомогательные методы -----------------------------------------

    private fun findFile(path: String): PsiFile {
        val vFile = myFixture.findFileInTempDir(path)
        return myFixture.psiManager.findFile(vFile)!!
    }

    private fun typeOf(path: String, name: String): String {
        val prop = findTopLevelProperty("static/references/$path", name)
        return provider.getType(prop)
    }

    private fun findTopLevelProperty(path: String, name: String): JsonProperty {
        val file = findFile(path)
        return PsiTreeUtil.findChildrenOfType(file, JsonProperty::class.java)
            .firstOrNull { it.name == name && it.parent.parent is JsonFile }
            ?: error("no top-level property '$name' in $path")
    }

    private fun findProperty(path: String, name: String): JsonProperty =
        findPropertyIn(findFile(path), name)

    private fun findPropertyIn(file: PsiElement, name: String): JsonProperty =
        PsiTreeUtil.findChildrenOfType(file, JsonProperty::class.java)
            .firstOrNull { it.name == name }
            ?: error("no property '$name' in ${(file as? PsiFile)?.name}")
}
