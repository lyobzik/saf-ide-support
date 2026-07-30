package ru.sber.smartapp.dsl

import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.usageView.UsageViewLongNameLocation
import com.intellij.usageView.UsageViewTypeLocation
import ru.sber.smartapp.dsl.findusages.SmartAppElementDescriptionProvider
import ru.sber.smartapp.dsl.highlight.SmartAppTextAttributes
import ru.sber.smartapp.dsl.reference.SmartAppFieldReference

/**
 * Интеграционные тесты поддержки полей форм и Jinja-навигации/подсветки.
 *
 * Фикстуры: форма `hello_form` с полями `name` и `age`; сценарий `s` ссылается на
 * неё через статический `form`. В сценарии используются Jinja-значения
 * `{{ main_form.<field> }}`.
 */
class SmartAppFormFieldTest : BasePlatformTestCase() {

    private val descriptionProvider = SmartAppElementDescriptionProvider()

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "static/references/forms/forms.json",
            """
            {
              "hello_form": {
                "type": "form",
                "fields": {
                  "name": { "type": "question" },
                  "age": { "type": "question" },
                  "age": { "type": "integration" }
                }
              }
            }
            """.trimIndent(),
        )
    }

    // ---- резолв ---------------------------------------------------------

    fun testResolvesFormFieldInScenario() {
        val scn = addScenario("s1", """"greeting": "{{ main_form.name }}"""")
        val ref = fieldReference(scn, "greeting", "{{ main_form.name }}")
        assertEquals("main_form.name резолвится в поле формы", 1, ref.multiResolve(false).size)
        val resolved = ref.multiResolve(false)[0].element as JsonProperty
        assertEquals("name", resolved.name)
    }

    fun testResolvesJinjaInsideNestedActionObject() {
        // Jinja внутри вложенного объекта сценария: targetFormOf поднимается к
        // top-level свойству `form` сценария.
        val scn = addScenario(
            "s2",
            """"actions": [ { "type": "sdk_answer", "message": "{{ main_form.name }}" } ]""",
        )
        val ref = fieldReference(scn, "message", "{{ main_form.name }}")
        assertEquals(1, ref.multiResolve(false).size)
    }

    fun testDynamicFormHasNoReference() {
        // `"form": "{{ main_form.name }}"` — динамический выбор формы.
        // targetFormOf возвращает null: ссылки на поле нет, WARNING нет.
        myFixture.addFileToProject(
            "static/references/scenarios/dyn.json",
            """{ "dyn": { "type": "form_filling", "form": "{{ main_form.name }}" } }""",
        )
        val file = findFile("static/references/scenarios/dyn.json")
        val literal = findLiteral(file, "form", "{{ main_form.name }}")
        val refs = literal.references.filterIsInstance<SmartAppFieldReference>()
        assertTrue("динамическая форма не даёт ссылки на поле", refs.isEmpty())
    }

    fun testDuplicateFieldMultiResolves() {
        // Поле `age` определено дважды в hello_form — multiResolve size == 2.
        val scn = addScenario("s3", """"g": "{{ main_form.age }}"""")
        val ref = fieldReference(scn, "g", "{{ main_form.age }}")
        assertEquals("дубли поля формы дают multi-resolve size 2", 2, ref.multiResolve(false).size)
    }

    fun testFieldReferenceRangeCoversOnlyFieldName() {
        val scn = addScenario("s4", """"g": "{{ main_form.name }}"""")
        val ref = fieldReference(scn, "g", "{{ main_form.name }}")
        // Диапазон ссылки — только на "name", не на весь "main_form.name".
        val literal = findLiteral(scn, "g", "{{ main_form.name }}")
        val absoluteRange = ref.rangeInElement.shiftRight(literal.textOffset)
        assertEquals("name", literal.containingFile.text.substring(absoluteRange.startOffset, absoluteRange.endOffset))
    }

    // ---- negative: нерелевантные Jinja-конструкции ---------------------

    fun testNoReferenceForVariablesDotField() {
        // `{{ x.y }}` — не main_form, ссылки на поле нет.
        val scn = addScenario("s5", """"g": "{{ x.y }}"""")
        val literal = findLiteral(scn, "g", "{{ x.y }}")
        assertTrue(literal.references.filterIsInstance<SmartAppFieldReference>().isEmpty())
    }

    fun testNoReferenceForStatementTag() {
        // `{% if x %}` — statement, ссылок на поле не создаёт.
        val scn = addScenario("s7", """"g": "{% if x %}hi{% endif %}"""")
        val literal = findLiteral(scn, "g", "{% if x %}hi{% endif %}")
        assertTrue(literal.references.filterIsInstance<SmartAppFieldReference>().isEmpty())
    }

    // ---- автодополнение -------------------------------------------------

    fun testFieldCompletionInsideJinja() {
        val items = completeAt(
            "static/references/scenarios/comp.json",
            """{ "comp": { "type": "form_filling", "form": "hello_form", "g": "{{ main_form.<caret> }}" } }""",
        )
        assertTrue("expected field names, got: $items", items.contains("name"))
        assertTrue("expected age, got: $items", items.contains("age"))
    }

    fun testNoFieldCompletionWithoutForm() {
        // Сценарий без `form` — targetFormOf null, completion полей пуст.
        val items = completeAt(
            "static/references/scenarios/noform.json",
            """{ "noform": { "type": "form_filling", "g": "{{ main_form.<caret> }}" } }""",
        )
        assertFalse(items.contains("name"))
    }

    // ---- unresolved WARNING --------------------------------------------

    fun testUnresolvedFieldIsWarning() {
        val scn = addScenario("s8", """"g": "{{ main_form.unknown }}"""")
        myFixture.openFileInEditor(scn.virtualFile)
        val warnings = myFixture.doHighlighting(HighlightSeverity.WARNING)
        assertTrue(
            "expected unresolved-field warning",
            warnings.any { it.description?.contains("Не удаётся разрешить поле") == true },
        )
    }

    fun testNoWarningForDynamicForm() {
        myFixture.addFileToProject(
            "static/references/scenarios/dyn2.json",
            """{ "dyn2": { "type": "form_filling", "form": "{{ main_form.name }}" } }""",
        )
        val file = findFile("static/references/scenarios/dyn2.json")
        myFixture.openFileInEditor(file.virtualFile)
        val warnings = myFixture.doHighlighting(HighlightSeverity.WARNING)
        assertFalse(
            "динамическая форма не даёт WARNING о поле",
            warnings.any { it.description?.contains("Не удаётся разрешить поле") == true },
        )
    }

    // ---- подсветка Jinja-разметки --------------------------------------

    fun testJinjaAttributesAreApplied() {
        // Одинарные кавычки внутри Jinja: двойные сломали бы JSON-строку.
        val scn = addScenario("s9", """"g": "{{ main_form.name | default('x') }}"""")
        myFixture.openFileInEditor(scn.virtualFile)
        val infos = myFixture.doHighlighting()
        val attrs = infos.mapNotNull { it.forcedTextAttributesKey }.toSet()
        assertTrue("JINJA_DELIM: $attrs", attrs.contains(SmartAppTextAttributes.JINJA_DELIM))
        assertTrue("JINJA_VAR: $attrs", attrs.contains(SmartAppTextAttributes.JINJA_VAR))
        assertTrue("JINJA_OP: $attrs", attrs.contains(SmartAppTextAttributes.JINJA_OP))
        assertTrue("JINJA_FILTER: $attrs", attrs.contains(SmartAppTextAttributes.JINJA_FILTER))
        assertTrue("JINJA_STRING: $attrs", attrs.contains(SmartAppTextAttributes.JINJA_STRING))
    }

    // ---- dumb mode ------------------------------------------------------

    fun testDumbModeResolveSilent() {
        val scn = addScenario("s10", """"g": "{{ main_form.name }}"""")
        val ref = fieldReference(scn, "g", "{{ main_form.name }}")
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            assertEquals("резолв поля тих в dumb mode", 0, ref.multiResolve(false).size)
        }
    }

    // ---- scope-изоляция -------------------------------------------------

    fun testScopeIsolatesFieldSets() {
        myFixture.addFileToProject(
            "projA/static/references/forms/f.json",
            """{ "f": { "type": "form", "fields": { "x": { "type": "question" } } } }""",
        )
        myFixture.addFileToProject(
            "projB/static/references/forms/f.json",
            """{ "f": { "type": "form", "fields": { "y": { "type": "question" } } } }""",
        )
        myFixture.addFileToProject(
            "projA/static/references/scenarios/a.json",
            """{ "a": { "type": "form_filling", "form": "f", "g": "{{ main_form.x }}" } }""",
        )
        val file = findFile("projA/static/references/scenarios/a.json")
        val ref = fieldReference(file, "g", "{{ main_form.x }}")
        assertEquals("scope ограничен projA", 1, ref.multiResolve(false).size)
    }

    // ---- битый FORM-файл ------------------------------------------------

    fun testBrokenFormFileDoesNotCorruptValidForm() {
        myFixture.addFileToProject("static/references/forms/broken.json", """{ "fields": """)
        // Валидная форма hello_form по-прежнему резолвится.
        val scn = addScenario("s11", """"g": "{{ main_form.name }}"""")
        val ref = fieldReference(scn, "g", "{{ main_form.name }}")
        assertEquals(1, ref.multiResolve(false).size)
    }

    // ---- Find Usages поля ----------------------------------------------

    fun testFieldFindUsagesAndDescription() {
        // Поиск использований имени поля формы из Jinja.
        myFixture.addFileToProject(
            "static/references/scenarios/usage.json",
            """{ "usage": { "type": "form_filling", "form": "hello_form", "g": "{{ main_form.name }}" } }""",
        )
        val nameFieldProp = findFieldProperty("static/references/forms/forms.json", "name")
        // Подписи элемента.
        assertEquals("field", descriptionProvider.getElementDescription(nameFieldProp, UsageViewTypeLocation.INSTANCE))
        assertEquals(
            "hello_form.name",
            descriptionProvider.getElementDescription(nameFieldProp, UsageViewLongNameLocation.INSTANCE),
        )
        // Интеграционный Find Usages.
        val usages = myFixture.findUsages(nameFieldProp)
        assertTrue(
            "ожидался usage поля 'name' в сценарии usage.json",
            usages.any { it.element?.containingFile?.name == "usage.json" },
        )
    }

    // ---- regression: существующие Jinja-тесты остаются корректны -------

    fun testPlainJinjaValueNoFieldReference() {
        // `a_{{ x }}_b` без main_form — ссылок на поле нет.
        val scn = addScenario("s12", """"g": "a_{{ x }}_b"""")
        val literal = findLiteral(scn, "g", "a_{{ x }}_b")
        assertTrue(literal.references.filterIsInstance<SmartAppFieldReference>().isEmpty())
    }

    // ---- вспомогательные методы -----------------------------------------

    private fun addScenario(name: String, body: String): PsiFile {
        val content = """{ "$name": { "type": "form_filling", "form": "hello_form", $body } }"""
        return myFixture.addFileToProject("static/references/scenarios/$name.json", content)
    }

    private fun findFile(path: String): PsiFile {
        val vFile = myFixture.findFileInTempDir(path)
        return myFixture.psiManager.findFile(vFile)!!
    }

    private fun findLiteral(file: PsiFile, propName: String, value: String): JsonStringLiteral {
        val match = PsiTreeUtil.findChildrenOfType(file, JsonProperty::class.java)
            .firstOrNull { it.name == propName && (it.value as? JsonStringLiteral)?.value == value }
            ?: error("no property '$propName' with value '$value' in ${file.name}")
        return match.value as JsonStringLiteral
    }

    private fun findFieldProperty(path: String, fieldName: String): JsonProperty {
        val file = findFile(path)
        return PsiTreeUtil.findChildrenOfType(file, JsonProperty::class.java)
            .first { it.name == fieldName && it.parent.parent is JsonProperty && (it.parent.parent as JsonProperty).name == "fields" }
    }

    private fun fieldReference(file: PsiFile, propName: String, value: String): SmartAppFieldReference =
        findLiteral(file, propName, value).references.filterIsInstance<SmartAppFieldReference>().firstOrNull()
            ?: error("no SmartAppFieldReference on '$value'")

    private fun completeAt(path: String, contentWithCaret: String): List<String> {
        val caret = contentWithCaret.indexOf("<caret>")
        check(caret >= 0) { "no <caret> marker" }
        val clean = contentWithCaret.replace("<caret>", "")
        val file = myFixture.addFileToProject(path, clean)
        myFixture.openFileInEditor(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(caret)
        // completeBasic может не активировать BASIC-contributors в середине
        // непустого строкового значения; явный BASIC-вызов надёжнее.
        myFixture.complete(com.intellij.codeInsight.completion.CompletionType.BASIC)
        return myFixture.lookupElementStrings ?: emptyList()
    }
}
