package ru.sber.smartapp.dsl

import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import ru.sber.smartapp.dsl.highlight.SmartAppTextAttributes
import ru.sber.smartapp.dsl.reference.SmartAppReference

/**
 * Интеграционные тесты поверх реального JSON PSI + file-based индекса на
 * [BasePlatformTestCase]. Фикстуры определений добавляются по корректным путям
 * `static/references/...`, чтобы заработали определение вида файла и индексация.
 */
class SmartAppDslTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "static/references/forms/forms.json",
            """
            {
              "hello_form": {
                "type": "form",
                "fields": {
                  "name": {
                    "type": "question",
                    "filler": { "type": "external", "filler": "my_filler" },
                    "classifier": { "type": "external", "classifier": "my_classifier" }
                  }
                }
              },
              "second_form": { "type": "base" }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "static/references/field_fillers/field_fillers.json",
            """{ "my_filler": { "type": "approve" } }""",
        )
        myFixture.addFileToProject(
            "static/references/classifiers/classifiers.json",
            """{ "my_classifier": { "type": "external" } }""",
        )
        myFixture.addFileToProject(
            "static/references/actions/actions.json",
            """{ "say_hello": { "type": "sdk_answer" }, "only_action": { "type": "sdk_answer" } }""",
        )
        myFixture.addFileToProject(
            "static/references/behaviors/behaviors.json",
            """{ "say_hello": { "success_action": { "type": "external", "action": "only_action" } } }""",
        )
        myFixture.addFileToProject(
            "static/references/scenarios/other_scenario.json",
            """{ "other_scenario": { "type": "base" } }""",
        )
    }

    // ---- резолв ---------------------------------------------------------

    fun testResolveForm() {
        val file = addScenario("s1", """"form": "hello_form"""")
        assertResolvesToDefinition(file, "form", "hello_form", expected = 1)
    }

    fun testResolveRunScenario() {
        val file = addScenario(
            "s2",
            """"actions": [ { "type": "run_scenario", "scenario": "other_scenario" } ]""",
        )
        assertResolvesToDefinition(file, "scenario", "other_scenario", expected = 1)
    }

    fun testResolveScenarioDescription() {
        val file = addScenario("s3", """"scenario_description": "other_scenario"""")
        assertResolvesToDefinition(file, "scenario_description", "other_scenario", expected = 1)
    }

    fun testResolveExternalFiller() {
        // Ссылка на filler находится внутри самой фикстуры форм.
        val formsFile = findFile("static/references/forms/forms.json")
        assertResolvesToDefinition(formsFile, "filler", "my_filler", expected = 1)
    }

    fun testResolveExternalClassifier() {
        val formsFile = findFile("static/references/forms/forms.json")
        assertResolvesToDefinition(formsFile, "classifier", "my_classifier", expected = 1)
    }

    fun testResolveExternalActionMatchesActionAndBehavior() {
        val file = addScenario(
            "s4",
            """"actions": [ { "type": "external", "action": "say_hello" } ]""",
        )
        // "say_hello" определён и как action, И как behavior -> два результата.
        assertResolvesToDefinition(file, "action", "say_hello", expected = 2)
    }

    fun testResolveActionOnlyInActions() {
        val file = addScenario(
            "s5",
            """"actions": [ { "type": "external", "action": "only_action" } ]""",
        )
        // Определён как action и упомянут в behavior, но существует лишь одно
        // top-level определение (в actions.json), а behavior, ссылающийся на него,
        // его не определяет -> ровно одно определение action; behaviors.json не
        // содержит "only_action" как top-level ключ.
        assertResolvesToDefinition(file, "action", "only_action", expected = 1)
    }

    fun testNameDefinedInDifferentFilesResolvesToBoth() {
        myFixture.addFileToProject(
            "static/references/scenarios/dup_a.json",
            """{ "shared_scn": { "type": "base" } }""",
        )
        myFixture.addFileToProject(
            "static/references/scenarios/dup_b.json",
            """{ "shared_scn": { "type": "tree" } }""",
        )
        val file = addScenario("s6", """"scenario_description": "shared_scn"""")
        assertResolvesToDefinition(file, "scenario_description", "shared_scn", expected = 2)
    }

    fun testDuplicateTopLevelKeysInOneFileMultiResolve() {
        myFixture.addFileToProject(
            "static/references/scenarios/dups.json",
            """{ "twin_scn": { "type": "base" }, "twin_scn": { "type": "tree" } }""",
        )
        val file = addScenario("s7", """"scenario_description": "twin_scn"""")
        assertResolvesToDefinition(file, "scenario_description", "twin_scn", expected = 2)
    }

    // ---- неразрешённые ссылки / jinja ----------------------------------

    fun testUnresolvedFormIsWarning() {
        val file = addScenario("s8", """"form": "missing_form"""")
        myFixture.openFileInEditor(file.virtualFile)
        val warnings = myFixture.doHighlighting(HighlightSeverity.WARNING)
        assertTrue(
            "expected an unresolved-reference warning",
            warnings.any { it.description?.contains("Не удаётся разрешить") == true },
        )
    }

    fun testJinjaValueIsNotAReference() {
        val file = addScenario("s9", """"form": "{{ main_form.name }}"""")
        val literal = findLiteral(file, "form", "{{ main_form.name }}")
        assertNull(smartAppReference(literal))
    }

    fun testMixedJinjaValueIsNotAReference() {
        val file = addScenario("s10", """"form": "a_{{ x }}_b"""")
        val literal = findLiteral(file, "form", "a_{{ x }}_b")
        assertNull(smartAppReference(literal))
    }

    // ---- подсветка ------------------------------------------------------

    fun testKeywordValueIsHighlighted() {
        val file = addScenario("s11", """"form": "hello_form"""")
        myFixture.openFileInEditor(file.virtualFile)
        val infos = myFixture.doHighlighting()
        assertTrue(
            "expected a SMARTAPP_KEYWORD highlight on the type value",
            infos.any { it.forcedTextAttributesKey == SmartAppTextAttributes.KEYWORD },
        )
    }

    // ---- вне области DSL ------------------------------------------------

    fun testFileOutsideReferencesHasNoReference() {
        val file = myFixture.addFileToProject(
            "other/place/foo.json",
            """{ "x": { "type": "form_filling", "form": "hello_form" } }""",
        )
        val literal = findLiteral(file, "form", "hello_form")
        assertNull(smartAppReference(literal))
    }

    // ---- автодополнение -------------------------------------------------

    fun testTypeKeywordCompletion() {
        val items = completeAt(
            "static/references/field_fillers/new_filler.json",
            """{ "new_filler": { "type": "<caret>" } }""",
        )
        assertTrue("expected filler keywords", items.contains("approve"))
        assertTrue(items.contains("external"))
    }

    fun testReferenceNameCompletion() {
        val items = completeAt(
            "static/references/scenarios/comp_scn.json",
            """{ "comp_scn": { "type": "form_filling", "form": "<caret>" } }""",
        )
        assertTrue("expected form names", items.contains("hello_form"))
        assertTrue(items.contains("second_form"))
    }

    // ---- dumb mode (во время индексации) --------------------------------

    fun testDumbModeResolveSilentButKeywordsStillComplete() {
        val file = addScenario("s12", """"form": "hello_form"""")
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            val literal = findLiteral(file, "form", "hello_form")
            val ref = smartAppReference(literal)
            assertNotNull("reference still attaches in dumb mode", ref)
            assertEquals("resolution is silent in dumb mode", 0, ref!!.multiResolve(false).size)
        }
        // Автодополнение ключевых слов (без индекса) работает и в dumb mode.
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            val items = completeAt(
                "static/references/field_fillers/dumb_filler.json",
                """{ "dumb_filler": { "type": "<caret>" } }""",
            )
            assertTrue(items.contains("approve"))
        }
    }

    // ---- расширенные правила ссылок ------------------------------------

    fun testResolveBehaviorViaProcessBehavior() {
        myFixture.addFileToProject(
            "static/references/behaviors/greet.json",
            """{ "greet_behavior": { "success_action": { "type": "sdk_answer" } } }""",
        )
        val file = addScenario(
            "s_proc",
            """"actions": [ { "type": "process_behavior", "behavior": "greet_behavior" } ]""",
        )
        assertResolvesToDefinition(file, "behavior", "greet_behavior", expected = 1)
    }

    fun testResolveClassifierWhenOwnerTypeIsClassifier() {
        val form = myFixture.addFileToProject(
            "static/references/forms/clf_form.json",
            """
            {
              "clf_form": {
                "type": "base",
                "fields": {
                  "x": {
                    "type": "question",
                    "filler": { "type": "classifier", "classifier": "my_classifier" }
                  }
                }
              }
            }
            """.trimIndent(),
        )
        assertResolvesToDefinition(form, "classifier", "my_classifier", expected = 1)
    }

    // ---- контекст категории type (completion) --------------------------

    fun testFieldTypeCompletionUsesFieldDescription() {
        val items = completeAt(
            "static/references/forms/comp_form.json",
            """{ "comp_form": { "type": "base", "fields": { "name": { "type": "<caret>" } } } }""",
        )
        assertTrue("expected field_description keywords", items.contains("question"))
        assertTrue(items.contains("integration"))
        assertFalse("must not offer scenario types", items.contains("form_filling"))
    }

    fun testRequirementInsideFieldsUsesFieldRequirement() {
        val items = completeAt(
            "static/references/forms/req_form.json",
            """{ "req_form": { "type": "base", "fields": { "age": { "type": "question", "requirement": { "type": "<caret>" } } } } }""",
        )
        assertTrue("expected field_requirement keywords", items.contains("comparison"))
        assertTrue(items.contains("value_in_set"))
        assertFalse("must not offer scenario-level requirements", items.contains("intersection"))
    }

    fun testRequirementAtScenarioLevelUsesRequirement() {
        val items = completeAt(
            "static/references/scenarios/req_scn.json",
            """{ "req_scn": { "type": "form_filling", "requirement": { "type": "<caret>" } } }""",
        )
        assertTrue("expected scenario-level requirement keywords", items.contains("intersection"))
        assertFalse("must not offer field_requirement keywords", items.contains("comparison"))
    }

    fun testOnFilledActionsTypeCompletionUsesAction() {
        val items = completeAt(
            "static/references/forms/ofa_form.json",
            """{ "ofa_form": { "type": "base", "fields": { "name": { "type": "question", "on_filled_actions": [ { "type": "<caret>" } ] } } } }""",
        )
        assertTrue("expected action keywords", items.contains("sdk_answer"))
        assertTrue(items.contains("external"))
        assertFalse("must not offer field_description here", items.contains("question"))
    }

    // ---- изоляция набора static/references (scope) ----------------------

    fun testScopeIsolatesReferenceSets() {
        myFixture.addFileToProject(
            "projA/static/references/forms/iso.json",
            """{ "iso_form": { "type": "base" } }""",
        )
        myFixture.addFileToProject(
            "projB/static/references/forms/iso.json",
            """{ "iso_form": { "type": "base" } }""",
        )
        val scnA = myFixture.addFileToProject(
            "projA/static/references/scenarios/a.json",
            """{ "a_scn": { "type": "form_filling", "form": "iso_form" } }""",
        )
        val literal = findLiteral(scnA, "form", "iso_form")
        val ref = smartAppReference(literal) ?: error("no SmartAppReference on 'iso_form'")
        val results = ref.multiResolve(false)
        // iso_form определён в обоих наборах, но scope ограничен projA.
        assertEquals("scope must isolate to projA", 1, results.size)
        val prop = results[0].element as JsonProperty
        assertTrue(
            "resolved definition must come from projA",
            prop.containingFile.virtualFile.path.contains("projA"),
        )
    }

    // ---- вспомогательные методы -----------------------------------------

    private fun addScenario(name: String, body: String): PsiFile {
        val content = """{ "$name": { "type": "form_filling", $body } }"""
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

    private fun smartAppReference(literal: JsonStringLiteral): SmartAppReference? =
        literal.references.filterIsInstance<SmartAppReference>().firstOrNull()

    private fun assertResolvesToDefinition(
        file: PsiFile,
        propName: String,
        value: String,
        expected: Int,
    ) {
        val literal = findLiteral(file, propName, value)
        val ref = smartAppReference(literal) ?: error("no SmartAppReference on '$value'")
        val results = ref.multiResolve(false)
        assertEquals("resolve count for '$value'", expected, results.size)
        results.forEach {
            val prop = it.element as? JsonProperty
            assertNotNull("resolved element is a JsonProperty", prop)
            assertEquals("resolved to the named definition", value, prop!!.name)
        }
    }

    private fun completeAt(path: String, contentWithCaret: String): List<String> {
        val caret = contentWithCaret.indexOf("<caret>")
        check(caret >= 0) { "no <caret> marker in content" }
        val clean = contentWithCaret.replace("<caret>", "")
        val file = myFixture.addFileToProject(path, clean)
        myFixture.openFileInEditor(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(caret)
        myFixture.completeBasic()
        return myFixture.lookupElementStrings ?: emptyList()
    }
}
