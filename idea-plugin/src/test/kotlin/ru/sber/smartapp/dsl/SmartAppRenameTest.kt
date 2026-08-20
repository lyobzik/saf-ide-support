package ru.sber.smartapp.dsl

import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import ru.sber.smartapp.dsl.reference.SmartAppReference

/**
 * Тесты rename: переименование top-level определения с обновлением ссылок (R1),
 * переименование только ссылки (R2), spec-тест [SmartAppReference.handleElementRename]
 * (R3) и rename сценария (R4).
 *
 * Фикстуры добавляются по путям `static/references/...`, чтобы определение вида
 * файла и индексация заработали.
 */
class SmartAppRenameTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "static/references/forms/forms.json",
            """{ "hello_form": { "type": "form" } }""",
        )
        myFixture.addFileToProject(
            "static/references/scenarios/other_scenario.json",
            """{ "other_scenario": { "type": "base" } }""",
        )
    }

    // ---- R1: rename определения формы → обновляются ссылки в сценарии ----

    fun testRenameDefinitionUpdatesReferences() {
        myFixture.addFileToProject(
            "static/references/scenarios/consumer.json",
            """{ "consumer": { "type": "form_filling", "form": "hello_form" } }""",
        )
        val helloFormProp = findTopLevelProperty("static/references/forms/forms.json", "hello_form")

        myFixture.renameElement(helloFormProp, "renamed_form")

        // Ключ в forms.json переименован.
        val formsFile = findFile("static/references/forms/forms.json")
        assertNotNull("forms.json must now define 'renamed_form'", findPropertySoft(formsFile, "renamed_form"))
        assertNull("forms.json must no longer define 'hello_form'", findPropertySoft(formsFile, "hello_form"))

        // Ссылка в сценарии обновлена, и резолв по новому имени работает.
        val consumerFile = findFile("static/references/scenarios/consumer.json")
        val literal = findLiteral(consumerFile, "form", "renamed_form")
        val ref = smartAppReference(literal) ?: error("no SmartAppReference on 'renamed_form'")
        assertEquals("rename must keep the reference resolvable", 1, ref.multiResolve(false).size)
        assertEquals("renamed_form", (ref.multiResolve(false)[0].element as JsonProperty).name)
    }

    // ---- R2: rename только ссылки — определение не трогается ------------

    fun testRenameReferenceRewritesStringOnly() {
        myFixture.addFileToProject(
            "static/references/scenarios/consumer.json",
            """{ "consumer": { "type": "form_filling", "form": "hello_form" } }""",
        )
        val consumerFile = findFile("static/references/scenarios/consumer.json")
        val literal = findLiteral(consumerFile, "form", "hello_form")
        val ref = smartAppReference(literal) ?: error("no SmartAppReference on 'hello_form'")

        // handleElementRename переписывает PSI, поэтому требуется write-action.
        WriteCommandAction.runWriteCommandAction(project) { ref.handleElementRename("only_ref_renamed") }

        // Строка-ссылка изменилась...
        val renamedLiteral = findLiteral(consumerFile, "form", "only_ref_renamed")
        assertEquals("only_ref_renamed", renamedLiteral.value)
        // ...но определение формы осталось прежним.
        val formsFile = findFile("static/references/forms/forms.json")
        assertNotNull("definition must stay 'hello_form'", findPropertySoft(formsFile, "hello_form"))
    }

    // ---- R3: spec — handleElementRename делегирует super ---------------

    fun testHandleElementRenameDelegatesToSuper() {
        myFixture.addFileToProject(
            "static/references/scenarios/consumer.json",
            """{ "consumer": { "type": "form_filling", "form": "hello_form" } }""",
        )
        val consumerFile = findFile("static/references/scenarios/consumer.json")
        val literal = findLiteral(consumerFile, "form", "hello_form")
        val ref = smartAppReference(literal) ?: error("no SmartAppReference on 'hello_form'")

        WriteCommandAction.runWriteCommandAction(project) { ref.handleElementRename("spec_name") }

        // После rename читаем literal заново из актуального PSI (старая ссылка
        // на literal ссылается на узел до мутации).
        val refreshed = findLiteral(consumerFile, "form", "spec_name")
        assertEquals("value rewritten by delegated handleElementRename", "spec_name", refreshed.value)
    }

    // ---- R4: rename top-level сценария → обновляются run_scenario-ссылки

    fun testRenameTopLevelScenarioUpdatesRunScenarioRefs() {
        myFixture.addFileToProject(
            "static/references/scenarios/runner.json",
            """{ "runner": { "type": "form_filling", "actions": [ { "type": "run_scenario", "scenario": "other_scenario" } ] } }""",
        )
        val otherScenarioProp = findTopLevelProperty("static/references/scenarios/other_scenario.json", "other_scenario")

        myFixture.renameElement(otherScenarioProp, "renamed_scn")

        val runnerFile = findFile("static/references/scenarios/runner.json")
        val literal = findLiteral(runnerFile, "scenario", "renamed_scn")
        val ref = smartAppReference(literal) ?: error("no SmartAppReference on 'renamed_scn'")
        assertEquals("scenario reference must resolve to the renamed definition", 1, ref.multiResolve(false).size)
    }

    // ---- вспомогательные методы -----------------------------------------

    private fun findFile(path: String): PsiFile {
        val vFile = myFixture.findFileInTempDir(path)
        return myFixture.psiManager.findFile(vFile)!!
    }

    private fun findTopLevelProperty(path: String, name: String): JsonProperty {
        val file = findFile(path)
        return PsiTreeUtil.findChildrenOfType(file, JsonProperty::class.java)
            .firstOrNull { it.name == name && it.parent.parent is JsonFile }
            ?: error("no top-level property '$name' in $path")
    }

    private fun findPropertySoft(file: PsiElement, name: String): JsonProperty? =
        PsiTreeUtil.findChildrenOfType(file, JsonProperty::class.java)
            .firstOrNull { it.name == name }

    private fun findLiteral(file: PsiFile, propName: String, value: String): JsonStringLiteral {
        val match = PsiTreeUtil.findChildrenOfType(file, JsonProperty::class.java)
            .firstOrNull { it.name == propName && (it.value as? JsonStringLiteral)?.value == value }
            ?: error("no property '$propName' with value '$value' in ${file.name}")
        return match.value as JsonStringLiteral
    }

    private fun smartAppReference(literal: JsonStringLiteral): SmartAppReference? =
        literal.references.filterIsInstance<SmartAppReference>().firstOrNull()
    // ---- protection: rename не должен утекать в чужой набор references ----

    fun testRenameDoesNotLeakIntoOtherReferenceSets() {
        // Одноимённые определения в нескольких наборах — обычная ситуация для
        // монорепозитория. JSON-плагин платформы связывает одноимённые ключи
        // между файлами, поэтому без SmartAppRenameProcessor рефакторинг в одном
        // наборе переписывал бы соседние приложения.
        val foreignSets = listOf("app_b", "app_c", "app_d", "app_e")
        val a = myFixture.addFileToProject(
            "app_a/static/references/forms/forms.json",
            """
            {
              "shared_form": {
                "type": "form"
              }
            }
            """.trimIndent(),
        )
        // Ссылка в своём наборе: без неё платформа ограничивается простым
        // inplace-переименованием и поиск usages не запускается.
        myFixture.addFileToProject(
            "app_a/static/references/scenarios/main.json",
            """
            {
              "main": {
                "form": "shared_form"
              }
            }
            """.trimIndent(),
        )
        for (set in foreignSets) {
            myFixture.addFileToProject(
                "$set/static/references/forms/forms.json",
                """
                {
                  "shared_form": {
                    "type": "form"
                  }
                }
                """.trimIndent(),
            )
            myFixture.addFileToProject(
                "$set/static/references/scenarios/main.json",
                """
                {
                  "main": {
                    "form": "shared_form"
                  }
                }
                """.trimIndent(),
            )
        }

        myFixture.openFileInEditor(a.virtualFile)
        myFixture.editor.caretModel.moveToOffset(a.text.indexOf("shared_form") + 2)
        myFixture.renameElementAtCaretUsingHandler("renamed_form")

        for (set in foreignSets) {
            for (path in listOf("forms/forms.json", "scenarios/main.json")) {
                val text = textInProject("$set/static/references/$path")
                assertTrue(
                    "чужой набор '$set/$path' не должен меняться при rename в app_a: $text",
                    text.contains("shared_form"),
                )
                assertFalse(
                    "в чужом наборе '$set/$path' появилось новое имя: $text",
                    text.contains("renamed_form"),
                )
            }
        }

        // В своём наборе переименование, наоборот, обязано пройти полностью.
        assertTrue(
            "ссылка в своём наборе обязана быть переименована",
            textInProject("app_a/static/references/scenarios/main.json").contains("renamed_form"),
        )
    }

    /**
     * Текущий текст файла проекта. Читать `contentsToByteArray` нельзя: правки
     * рефакторинга живут в документе и на диск ещё не сохранены — тест на
     * дисковом содержимом молча проходил бы всегда.
     */
    private fun textInProject(path: String): String {
        val virtualFile = myFixture.findFileInTempDir(path) ?: error("файл $path не найден")
        return myFixture.psiManager.findFile(virtualFile)?.text
            ?: error("PSI для $path недоступен")
    }

}
