package ru.sber.smartapp.dsl.resources

import com.intellij.codeInsight.completion.CompletionType
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Ключевые слова приложения на стороне IDEA: активный класс из `RESOURCES`,
 * изоляция приложений (включая вложенные) и автодополнение.
 *
 * Разрешение цепочки и формы регистрации проверяются в
 * [SmartAppResourceScannerTest] и в тестах ядра расширения на одной таблице
 * входов; здесь — то, что видно только в платформе.
 */
class SmartAppCustomKeywordsTest : BasePlatformTestCase() {

    private fun app(root: String, action: String, className: String = "CustomAction") {
        val prefix = if (root.isEmpty()) "" else "$root/"
        myFixture.addFileToProject(
            "${prefix}app_config.py",
            "from app.resources.custom_app_resources import CustomAppResources\n" +
                "RESOURCES = CustomAppResources\n",
        )
        myFixture.addFileToProject(
            "${prefix}app/resources/custom_app_resources.py",
            // Импорт базы обязателен: без него база «ниоткуда», и цепочка по
            // контракту не подтверждается.
            "from smart_kit.resources import SmartAppResources\n\n" +
                "class CustomAppResources(SmartAppResources):\n" +
                "    def init_actions(self):\n" +
                "        super().init_actions()\n" +
                "        actions[\"$action\"] = $className\n",
        )
    }

    private fun keywordsOf(path: String): List<SmartAppResourceResolver.CustomKeyword> {
        val file = myFixture.addFileToProject(path, """{ "some_action": { "type": "x" } }""")
        return SmartAppCustomKeywords.of(file)
    }

    fun testKeywordsOfActiveResourcesClass() {
        app("", "custom_action")
        val keywords = keywordsOf("static/references/actions/actions.json")
        assertEquals(listOf("custom_action"), keywords.map { it.name })
        assertEquals("action", keywords.single().category)
        assertEquals("CustomAction", keywords.single().className)
    }

    fun testUnusedSubclassIsNotScanned() {
        app("", "used")
        myFixture.addFileToProject(
            "app/resources/unused_resources.py",
            "from smart_kit.resources import SmartAppResources\n\n" +
                "class UnusedResources(SmartAppResources):\n" +
                "    def init_actions(self):\n        actions[\"unused\"] = C\n",
        )
        assertEquals(listOf("used"), keywordsOf("static/references/actions/a.json").map { it.name })
    }

    fun testNestedApplicationIsIsolated() {
        app("", "outer_action")
        app("subapp", "inner_action")
        assertEquals(
            listOf("outer_action"),
            keywordsOf("static/references/actions/outer.json").map { it.name },
        )
        assertEquals(
            listOf("inner_action"),
            keywordsOf("subapp/static/references/actions/inner.json").map { it.name },
        )
    }

    fun testOuterApplicationDoesNotReadNestedModule() {
        // Файл физически внутри subapp: владелец у него — вложенное приложение,
        // поэтому цепочка внешнего его не видит и слов не даёт.
        myFixture.addFileToProject(
            "app_config.py",
            "from subapp.app.resources.custom_app_resources import CustomAppResources\n" +
                "RESOURCES = CustomAppResources\n",
        )
        app("subapp", "inner_action")
        // Вложенный набор создаётся до проверки: пока у subapp нет
        // static/references, он не приложение, и его модули законно принадлежат
        // внешнему — владение определяет ближайший набор, а не имя каталога.
        val nested = keywordsOf("subapp/static/references/actions/inner.json").map { it.name }
        assertEquals(listOf("inner_action"), nested)
        assertEquals(
            emptyList<String>(),
            keywordsOf("static/references/actions/outer.json").map { it.name },
        )
    }

    // ---- контракт exists: модуль приложения против библиотеки ----
    //
    // Та же таблица входов проверяется в test/core/resourceKeywords.test.ts:
    // разный ответ меняет местами «пропавший модуль приложения» и «библиотечную
    // базу», то есть пустой словарь и полный.

    private fun derived(module: String): String =
        "from $module import BaseResources\n\nclass R(BaseResources):\n" +
            "    def init_actions(self):\n        actions[\"custom\"] = C\n"

    private fun withActiveClass(module: String) {
        myFixture.addFileToProject(
            "app_config.py",
            "from app.resources.custom import R\nRESOURCES = R\n",
        )
        myFixture.addFileToProject("app/resources/custom.py", derived(module))
    }

    fun testMissingApplicationModuleGivesNothing() {
        withActiveClass("app.resources.missing")
        assertEquals(emptyList<String>(), keywordsOf("static/references/actions/a.json").map { it.name })
    }

    fun testLibraryBaseIsNormalEnd() {
        withActiveClass("smart_kit.resources")
        assertEquals(listOf("custom"), keywordsOf("static/references/actions/a.json").map { it.name })
    }

    fun testModuleFileCountsAsApplicationPackage() {
        withActiveClass("single.missing")
        myFixture.addFileToProject("single.py", "")
        assertEquals(emptyList<String>(), keywordsOf("static/references/actions/a.json").map { it.name })
    }

    fun testNestedApplicationFilesAreNotOurs() {
        withActiveClass("subapp.missing")
        myFixture.addFileToProject("subapp/static/references/actions/b.json", """{ "x": { "type": "y" } }""")
        myFixture.addFileToProject("subapp/app/nested.py", "")
        assertEquals(listOf("custom"), keywordsOf("static/references/actions/a.json").map { it.name })
    }

    fun testExcludedDirectoryIsNotApplicationPackage() {
        withActiveClass("venv.missing")
        myFixture.addFileToProject("venv/lib/module.py", "")
        // venv — зависимости, а не код приложения: база оттуда библиотечная.
        assertEquals(listOf("custom"), keywordsOf("static/references/actions/a.json").map { it.name })
    }

    fun testCompletionOffersCustomKeyword() {
        app("", "custom_action")
        val items = completeTypeValue("static/references/actions/completion.json")
        assertTrue("ожидалось слово приложения, получено $items", items.contains("custom_action"))
        // Фреймворковые слова той же категории никуда не делись.
        assertTrue(items.contains("external"))
    }

    /**
     * Слова приложения читаются по VFS, а не из индекса, поэтому доступны и во
     * время индексации — как фреймворковые.
     */
    fun testCustomKeywordsWorkInDumbMode() {
        app("", "custom_action")
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            val items = completeTypeValue("static/references/actions/dumb.json")
            assertTrue("dumb mode: $items", items.contains("custom_action"))
        }
    }

    private fun completeTypeValue(path: String): List<String> {
        val content = """{ "some_action": { "type": "<caret>" } }"""
        val caret = content.indexOf("<caret>")
        val file = myFixture.addFileToProject(path, content.replace("<caret>", ""))
        myFixture.openFileInEditor(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(caret)
        val elements = myFixture.complete(CompletionType.BASIC)
        return myFixture.lookupElementStrings ?: elements?.map { it.lookupString } ?: emptyList()
    }
}
