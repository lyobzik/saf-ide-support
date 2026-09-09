package ru.sber.smartapp.dsl.resources

import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Словарь модели пользователя на стороне IDEA: доступ к файлам по VFS, кэш и
 * изоляция приложений.
 *
 * Свёртка, фильтр имён и гасители проверяются в [SmartAppUserModelTest] и в
 * тестах ядра расширения на одной таблице входов; здесь — то, что видно только
 * в платформе, и то, что чистый резолвер не покрывает: путь «VFS -> словарь».
 */
class SmartAppUserModelPlatformTest : BasePlatformTestCase() {

    private fun app(root: String = "", body: String = "    pass\n") {
        val prefix = if (root.isEmpty()) "" else "$root/"
        myFixture.addFileToProject(
            "${prefix}app_config.py",
            "from app.user.user import CustomUser\n\nUSER = CustomUser\n",
        )
        myFixture.addFileToProject(
            "${prefix}app/user/user.py",
            // Импорт базы обязателен: без него база «ниоткуда», и цепочка по
            // контракту не подтверждается.
            "from scenarios.user.user_model import User\n\n\nclass CustomUser(User):\n$body",
        )
    }

    private fun modelOf(path: String): SmartAppUserModelResolver.Info? {
        val file = myFixture.addFileToProject(path, """{ "some_action": { "type": "x" } }""")
        return SmartAppUserModel.of(file)
    }

    fun testDictionaryIsBuiltFromVirtualFiles() {
        app()
        val info = modelOf("static/references/actions/actions.json")
        assertNotNull(info)
        // Пол фреймворка на месте: имя из исходной задачи.
        assertTrue("variables" in info!!.attributes.keys)
        assertEquals("CustomUser", info.userClass?.name)
        assertEquals("app/user/user.py", info.userClass?.file)
    }

    fun testApplicationDeclarationIsFound() {
        app(body = "    def __init__(self):\n        super().__init__()\n        self.own = 1\n")
        val info = modelOf("static/references/actions/actions.json")
        val site = info?.attributes?.get("own")?.declarations?.single()
        assertEquals("app/user/user.py", site?.file)
        assertEquals(SmartAppResourceScanner.DeclarationOrigin.SELF, site?.origin)
    }

    fun testWorksInDumbMode() {
        // Индекса здесь нет намеренно, поэтому словарь доступен и во время
        // индексации — в отличие от всего, что читает FileBasedIndex.
        app()
        val file = myFixture.addFileToProject("static/references/actions/a.json", "{}")
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            val info = SmartAppUserModel.of(file)
            assertNotNull(info)
            assertTrue("variables" in info!!.attributes.keys)
        }
    }

    fun testRepeatedCallReturnsCachedInstance() {
        app()
        val file = myFixture.addFileToProject("static/references/actions/a.json", "{}")
        assertSame(SmartAppUserModel.of(file), SmartAppUserModel.of(file))
    }

    fun testNestedApplicationIsIsolated() {
        // Вложенный `subapp` — отдельное приложение: его класс пользователя в
        // словарь внешнего не идёт и наоборот.
        app(body = "    def __init__(self):\n        super().__init__()\n        self.outer = 1\n")
        app("subapp", body = "    def __init__(self):\n        super().__init__()\n        self.inner = 1\n")

        val outer = modelOf("static/references/actions/outer.json")
        val inner = modelOf("subapp/static/references/actions/inner.json")
        assertTrue("outer" in outer!!.attributes.keys)
        assertFalse("inner" in outer.attributes.keys)
        assertTrue("inner" in inner!!.attributes.keys)
        assertFalse("outer" in inner.attributes.keys)
    }

    fun testFileOutsideApplicationHasNoModel() {
        app()
        val file = myFixture.addFileToProject("elsewhere/notes.json", "{}")
        assertNull(SmartAppUserModel.of(file))
    }
}
