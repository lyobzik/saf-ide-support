package ru.sber.smartapp.dsl.resources

import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import ru.sber.smartapp.dsl.resources.SmartAppUserRootResolver.RootState

/**
 * Корневое имя на стороне IDEA: доступ к файлам по VFS, кэш и изоляция
 * приложений.
 *
 * Само правило `<d>` и свёртка проверяются в [SmartAppParametrizerScanTest] и
 * [SmartAppUserRootTest] на одной таблице входов с расширением; здесь — то, что
 * видно только в платформе.
 */
class SmartAppUserRootPlatformTest : BasePlatformTestCase() {

    private fun app(root: String = "", key: String = "user") {
        val prefix = if (root.isEmpty()) "" else "$root/"
        myFixture.addFileToProject(
            "${prefix}app_config.py",
            "from app.user.parametrizer import CustomParametrizer\n\nPARAMETRIZER = CustomParametrizer\n",
        )
        myFixture.addFileToProject(
            "${prefix}app/user/parametrizer.py",
            "from scenarios.user.parametrizer import Parametrizer\n\n\n" +
                "class CustomParametrizer(Parametrizer):\n" +
                "    def _get_user_data(self, tpr=None):\n" +
                "        data = super()._get_user_data(tpr)\n" +
                "        data[\"$key\"] = self._user\n" +
                "        return data\n",
        )
    }

    private fun rootOf(path: String): SmartAppUserRootResolver.Root? =
        SmartAppUserRoot.of(myFixture.addFileToProject(path, """{ "a": { "type": "x" } }"""))

    fun testRootIsReadFromVirtualFiles() {
        app()
        val root = rootOf("static/references/actions/actions.json")
        assertEquals(RootState.PROVEN, root?.state)
        assertEquals(listOf("user"), root?.names)
    }

    fun testWorksInDumbMode() {
        // Индекса здесь нет намеренно, поэтому корневое имя доступно и во время
        // индексации — в отличие от всего, что читает FileBasedIndex.
        app()
        val file = myFixture.addFileToProject("static/references/actions/a.json", "{}")
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            assertEquals(RootState.PROVEN, SmartAppUserRoot.of(file)?.state)
        }
    }

    fun testRepeatedCallReturnsCachedInstance() {
        app()
        val file = myFixture.addFileToProject("static/references/actions/a.json", "{}")
        assertSame(SmartAppUserRoot.of(file), SmartAppUserRoot.of(file))
    }

    fun testNestedApplicationIsIsolated() {
        // Вложенный `subapp` — отдельное приложение: его параметризатор на
        // корневое имя внешнего не влияет и наоборот.
        app(key = "outer")
        app("subapp", key = "inner")
        assertEquals(listOf("outer"), rootOf("static/references/actions/o.json")?.names)
        assertEquals(listOf("inner"), rootOf("subapp/static/references/actions/i.json")?.names)
    }

    fun testFileOutsideApplicationHasNoRoot() {
        app()
        val file = myFixture.addFileToProject("elsewhere/notes.json", "{}")
        assertNull(SmartAppUserRoot.of(file))
    }

    fun testApplicationWithoutParametrizerFallsBackToDefault() {
        myFixture.addFileToProject("app_config.py", "RESOURCES = X\n")
        val root = rootOf("static/references/actions/a.json")
        assertEquals(RootState.DEFAULT, root?.state)
        assertEquals(listOf("user"), root?.names)
    }
}
