package ru.sber.smartapp.dsl

import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import ru.sber.smartapp.dsl.annotator.SmartAppAnnotator
import ru.sber.smartapp.dsl.highlight.SmartAppTextAttributes

/**
 * Тесты подсветки структурных ключей ([SmartAppTextAttributes.FIELD]) и
 * контекстной подсветки ключевых слов `type` ([SmartAppTextAttributes.KEYWORD]).
 *
 * Дополняет `SmartAppDslTest.testKeywordValueIsHighlighted`, который проверял
 * только KEYWORD на значении `type`, но не FIELD на структурных ключах.
 */
class SmartAppHighlightingTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "static/references/forms/forms.json",
            """{ "hello_form": { "type": "form" } }""",
        )
    }

    // ---- H1: FIELD на структурном ключе "form" --------------------------

    fun testStructuralKeyFormIsHighlightedAsField() {
        val file = addScenario("s1", """"form": "hello_form"""")
        myFixture.openFileInEditor(file.virtualFile)
        val infos = myFixture.doHighlighting()
        val formProp = findProperty(file, "form")
        assertTrue(
            "структурный ключ 'form' должен подсвечиваться как SMARTAPP_FIELD на nameElement",
            infos.any {
                it.forcedTextAttributesKey == SmartAppTextAttributes.FIELD &&
                    it.startOffset == formProp.nameElement.textRange.startOffset
            },
        )
    }

    // ---- H2: все структурные ключи подсвечиваются -----------------------

    fun testAllStructuralKeysHighlighted() {
        // Один файл со всеми структурными ключами, чтобы проверить каждый.
        val file = myFixture.addFileToProject(
            "static/references/scenarios/all_keys.json",
            """
            {
              "all_keys": {
                "type": "form_filling",
                "form": "hello_form",
                "scenario": "x",
                "scenario_description": "x",
                "actions": [],
                "requirement": {},
                "questions": {},
                "on_filled_actions": [],
                "filler": { "type": "x" },
                "classifier": { "type": "x" },
                "action": "x",
                "behavior": "x",
                "fields": {}
              }
            }
            """.trimIndent(),
        )
        myFixture.openFileInEditor(file.virtualFile)
        val infos = myFixture.doHighlighting()
        for (key in STRUCTURAL_KEYS) {
            val prop = findProperty(file, key)
            val hasField = infos.any {
                it.forcedTextAttributesKey == SmartAppTextAttributes.FIELD &&
                    it.startOffset == prop.nameElement.textRange.startOffset
            }
            assertTrue("структурный ключ '$key' должен быть подсвечен как FIELD", hasField)
        }
    }

    // ---- H3: не-структурный ключ НЕ подсвечивается как FIELD -----------

    fun testNonStructuralKeyNotHighlightedAsField() {
        val file = addScenario("s2", """"comment": "note"""")
        myFixture.openFileInEditor(file.virtualFile)
        val infos = myFixture.doHighlighting()
        val commentProp = findProperty(file, "comment")
        assertFalse(
            "произвольный ключ 'comment' не должен подсвечиваться как FIELD",
            infos.any {
                it.forcedTextAttributesKey == SmartAppTextAttributes.FIELD &&
                    it.startOffset == commentProp.nameElement.textRange.startOffset
            },
        )
    }

    // ---- H4: диапазон FIELD = только nameElement ------------------------

    fun testStructuralKeyHighlightRangeCoversOnlyNameElement() {
        val file = addScenario("s3", """"on_filled_actions": []""")
        myFixture.openFileInEditor(file.virtualFile)
        val infos = myFixture.doHighlighting()
        val prop = findProperty(file, "on_filled_actions")
        val expected: TextRange = prop.nameElement.textRange
        val match = infos.firstOrNull { it.forcedTextAttributesKey == SmartAppTextAttributes.FIELD }
        assertNotNull("FIELD highlight на 'on_filled_actions' должен быть", match)
        assertEquals("start offset = nameElement", expected.startOffset, match!!.startOffset)
        assertEquals("end offset = nameElement", expected.endOffset, match.endOffset)
    }

    // ---- H5: статический контракт DumbAware (см. insight о doHighlighting)

    fun testAnnotatorIsDumbAwareStaticContract() {
        // Статический контракт, НЕ функциональный тест dumb mode: прямой прогон
        // myFixture.doHighlighting() внутри runInDumbModeSynchronously виснет
        // (doHighlighting форсирует daemon-pass, ждущий окончания индексации —
        // см. docs/insights/2026-07-25-dohighlighting-hangs-in-dumb-mode.md).
        // Поэтому проверяем маркер DumbAware: пока он стоит, платформа вызывает
        // аннотатор и в dumb mode, а чисто-PSI ветка структурных ключей не
        // зависит от индекса. Функциональная корректность FIELD покрыта в H1–H4.
        assertTrue(
            "SmartAppAnnotator должен быть DumbAware для PSI-ветки структурных ключей",
            DumbAware::class.java.isAssignableFrom(SmartAppAnnotator::class.java),
        )
    }

    // ---- H6: контекстная подсветка ключевого слова type ----------------

    fun testKeywordHighlightUsesContextCategory() {
        // Сценарий: type=form_filling (категория scenario) — KEYWORD точно на
        // диапазоне этого literal.
        val scnFile = myFixture.addFileToProject(
            "static/references/scenarios/ctx_scn.json",
            """{ "ctx_scn": { "type": "form_filling" } }""",
        )
        myFixture.openFileInEditor(scnFile.virtualFile)
        val scnInfos = myFixture.doHighlighting()
        val formFillingRange = typeValueRange(scnFile, "form_filling")
        assertTrue(
            "form_filling в позиции type сценария должен быть KEYWORD на диапазоне literal",
            scnInfos.any {
                it.forcedTextAttributesKey == SmartAppTextAttributes.KEYWORD &&
                    it.startOffset == formFillingRange.startOffset &&
                    it.endOffset == formFillingRange.endOffset
            },
        )

        // Поле формы: type=question (категория field_description) — KEYWORD
        // именно на диапазоне literal "question".
        val formFile = myFixture.addFileToProject(
            "static/references/forms/field_form.json",
            """{ "field_form": { "type": "base", "fields": { "age": { "type": "question" } } } }""",
        )
        myFixture.openFileInEditor(formFile.virtualFile)
        val formInfos = myFixture.doHighlighting()
        val questionRange = typeValueRange(formFile, "question")
        assertTrue(
            "question в позиции type поля формы должен быть KEYWORD на диапазоне literal",
            formInfos.any {
                it.forcedTextAttributesKey == SmartAppTextAttributes.KEYWORD &&
                    it.startOffset == questionRange.startOffset &&
                    it.endOffset == questionRange.endOffset
            },
        )

        // В позиции type сценария нерелевантное слово не подсвечивается.
        val badFile = myFixture.addFileToProject(
            "static/references/scenarios/bad_scn.json",
            """{ "bad_scn": { "type": "approve" } }""",
        )
        myFixture.openFileInEditor(badFile.virtualFile)
        val badInfos = myFixture.doHighlighting()
        val approveRange = typeValueRange(badFile, "approve")
        assertFalse(
            "'approve' не ключевое слово категории scenario — не подсвечивается",
            badInfos.any {
                it.forcedTextAttributesKey == SmartAppTextAttributes.KEYWORD &&
                    it.startOffset == approveRange.startOffset
            },
        )
    }

    // ---- вспомогательные методы -----------------------------------------

    private fun addScenario(name: String, body: String): PsiFile {
        val content = """{ "$name": { "type": "form_filling", $body } }"""
        return myFixture.addFileToProject("static/references/scenarios/$name.json", content)
    }

    private fun findProperty(file: PsiElement, name: String): JsonProperty =
        PsiTreeUtil.findChildrenOfType(file, JsonProperty::class.java)
            .firstOrNull { it.name == name }
            ?: error("no property '$name' in ${(file as? PsiFile)?.name}")

    private fun typeValueRange(file: PsiElement, expectedValue: String): TextRange {
        // Находим literal-значение свойства "type" с конкретным ожидаемым
        // значением, чтобы диапазон highlight сопоставлялся именно с этим
        // literal, а не с любым встретившимся KEYWORD.
        val literal = PsiTreeUtil.findChildrenOfType(file, JsonProperty::class.java)
            .firstOrNull { it.name == "type" && (it.value as? JsonStringLiteral)?.value == expectedValue }
            ?.value
            ?: error("no 'type' property with value '$expectedValue' in ${(file as? PsiFile)?.name}")
        return literal.textRange
    }

    private companion object {
        val STRUCTURAL_KEYS = setOf(
            "form", "filler", "classifier", "action", "behavior", "scenario",
            "scenario_description", "actions", "requirement", "fields",
            "questions", "on_filled_actions",
        )
    }
}
