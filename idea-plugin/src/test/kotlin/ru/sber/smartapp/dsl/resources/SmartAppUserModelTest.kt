package ru.sber.smartapp.dsl.resources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.sber.smartapp.dsl.contract.UserModelSpec
import ru.sber.smartapp.dsl.resources.SmartAppResourceScanner.DeclarationOrigin

/**
 * Словарь модели пользователя: пол фреймворка плюс половина приложения.
 *
 * Та же таблица входов прогоняется в ядре расширения (`userModel.test.ts`).
 */
class SmartAppUserModelTest {

    private fun reader(files: Map<String, String>) = object : SmartAppResourceResolver.AppFiles {
        override fun read(relativePath: String): String? = files[relativePath]
        override fun exists(path: String): Boolean =
            files.keys.any { it == path || it.startsWith("$path/") }
    }

    private fun infoOf(files: Map<String, String>) = SmartAppUserModelResolver.of(reader(files))

    private fun names(files: Map<String, String>): Set<String> =
        infoOf(files)?.attributes?.keys.orEmpty()

    /** Типовая раскладка: `USER = CustomUser`, класс наследует библиотечный `User`. */
    private fun typicalApp(body: String): Map<String, String> = mapOf(
        "app_config.py" to "from app.user.user import CustomUser\n\nUSER = CustomUser\n",
        "app/user/user.py" to
            "from scenarios.user.user_model import User\n\n\nclass CustomUser(User):\n$body",
    )

    private val floor = SmartAppUserFields.of(UserModelSpec.defaultClass)

    // --- пол фреймворка ---

    @Test
    fun testTypicalAppGetsLibraryNames() {
        // `CustomUser.fields` возвращает `super().fields + []`, то есть всё
        // приходит из библиотеки — включая `variables` из исходной задачи.
        val result = names(
            typicalApp("    @property\n    def fields(self):\n        return super().fields + []\n"),
        )
        assertTrue("variables" in result)
        assertTrue("forms" in result)
        assertTrue("message" in result)
        for (name in floor!!.fields) assertTrue("поле пола '$name' обязано быть", name in result)
    }

    @Test
    fun testMissingUserVariableIsNotRefusal() {
        val info = infoOf(mapOf("app_config.py" to "RESOURCES = X\n"))
        assertNotNull(info)
        assertTrue("variables" in info!!.attributes.keys)
        // Классов приложения в цепочке нет — переходить с корневой переменной некуда.
        assertNull(info.userClass)
    }

    @Test
    fun testConditionalUserVariableGivesNoDictionary() {
        assertNull(infoOf(mapOf("app_config.py" to "if dev:\n    USER = A\nelse:\n    USER = B\n")))
    }

    @Test
    fun testInvalidChainGivesNoDictionary() {
        assertNull(
            infoOf(
                mapOf(
                    "app_config.py" to "from app.user.user import CustomUser\n\nUSER = CustomUser\n",
                    "app/user/user.py" to "class CustomUser(Unknown):\n    pass\n",
                ),
            ),
        )
    }

    // --- деградация при неизвестном поле ---

    @Test
    fun testUnknownFloorKeepsApplicationNames() {
        // Отключать всё молча значило бы для такого приложения ничем не
        // отличаться от «плагин не установлен».
        val info = infoOf(
            mapOf(
                "app_config.py" to "from app.user.user import CustomUser\n\nUSER = CustomUser\n",
                "app/user/user.py" to
                    "from nlpf_statemachine.override.user import SMUser\n\n\n" +
                    "class CustomUser(SMUser):\n" +
                    "    def __init__(self):\n" +
                    "        super().__init__()\n" +
                    "        self.own_attribute = 1\n",
            ),
        )
        assertNotNull(info)
        assertTrue("own_attribute" in info!!.attributes.keys)
        assertFalse("variables" in info.attributes.keys)
        assertFalse(info.diagnosticsSafe)
    }

    @Test
    fun testUnknownFloorAndEmptyChainGiveNoDictionary() {
        assertNull(infoOf(mapOf("app_config.py" to "from far.away import Other\n\nUSER = Other\n")))
    }

    // --- свёртка fields ---

    @Test
    fun testWithoutSuperDropsFloorFieldsButKeepsAttributes() {
        val result = names(
            typicalApp("    @property\n    def fields(self):\n        return [Field('own', M)]\n"),
        )
        assertTrue("own" in result)
        assertFalse("variables" in result)
        // `message` создаётся не списком `fields`, а `self.message = …` в базе,
        // и переопределение свойства на него не влияет.
        assertTrue("message" in result)
    }

    @Test
    fun testUnparsedFormKeepsFloorFields() {
        val info = infoOf(
            typicalApp(
                "    @property\n" +
                    "    def fields(self):\n" +
                    "        return [*super().fields, Field('own', M)]\n",
            ),
        )
        assertTrue("variables" in info!!.attributes.keys)
        assertTrue("own" in info.attributes.keys)
        assertFalse(info.diagnosticsSafe)
    }

    @Test
    fun testLastOfDuplicateClassesWins() {
        // Так работает Python; первый вернул бы не тот словарь.
        val result = names(
            mapOf(
                "app_config.py" to "from app.user.user import CustomUser\n\nUSER = CustomUser\n",
                "app/user/user.py" to
                    "from scenarios.user.user_model import User\n\n\n" +
                    "class CustomUser(User):\n    @property\n    def fields(self):\n" +
                    "        return super().fields + [Field('first', M)]\n\n\n" +
                    "class CustomUser(User):\n    @property\n    def fields(self):\n" +
                    "        return super().fields + [Field('second', M)]\n",
            ),
        )
        assertFalse("first" in result)
        assertTrue("second" in result)
    }

    @Test
    fun testClassWithoutOwnFieldsKeepsFloor() {
        // Самый частый вид класса пользователя вообще.
        assertTrue("variables" in names(typicalApp("    pass\n")))
    }

    // --- объявления и цели перехода ---

    @Test
    fun testSnapshotNameHasNoDeclarations() {
        assertEquals(
            emptyList<SmartAppUserModelResolver.DeclarationSite>(),
            infoOf(typicalApp("    pass\n"))?.attributes?.get("variables")?.declarations,
        )
    }

    @Test
    fun testApplicationFieldPointsAtLiteralContent() {
        val files = typicalApp(
            "    @property\n" +
                "    def fields(self):\n" +
                "        return super().fields + [Field('own_field', M)]\n",
        )
        val site = infoOf(files)?.attributes?.get("own_field")?.declarations?.single()
        assertEquals(DeclarationOrigin.FIELD, site?.origin)
        assertEquals("app/user/user.py", site?.file)
        assertEquals(
            "own_field",
            files.getValue("app/user/user.py").substring(site!!.nameStart, site.nameEnd),
        )
    }

    @Test
    fun testNameDeclaredTwiceHasTwoTargets() {
        val files = typicalApp(
            "    @property\n" +
                "    def fields(self):\n" +
                "        return super().fields + [Field('dual', M)]\n" +
                "\n" +
                "    def __init__(self):\n" +
                "        super().__init__()\n" +
                "        self.dual = 1\n",
        )
        val origins = infoOf(files)?.attributes?.get("dual")?.declarations?.map { it.origin }
        assertEquals(
            listOf(DeclarationOrigin.FIELD, DeclarationOrigin.SELF).sortedBy { it.name },
            origins?.sortedBy { it.name },
        )
    }

    @Test
    fun testRootVariableNavigatesToApplicationClass() {
        val files = typicalApp("    pass\n")
        val cls = infoOf(files)?.userClass
        assertEquals("CustomUser", cls?.name)
        assertEquals("app/user/user.py", cls?.file)
        assertEquals(
            "CustomUser",
            files.getValue("app/user/user.py").substring(cls!!.nameStart, cls.nameEnd),
        )
    }

    @Test
    fun testMostDerivedClassIsNavigationTarget() {
        val info = infoOf(
            mapOf(
                "app_config.py" to "from app.user.user import CustomUser\n\nUSER = CustomUser\n",
                "app/user/user.py" to
                    "from app.user.middle import Middle\n\n\nclass CustomUser(Middle):\n    pass\n",
                "app/user/middle.py" to
                    "from scenarios.user.user_model import User\n\n\nclass Middle(User):\n    pass\n",
            ),
        )
        assertEquals("CustomUser", info?.userClass?.name)
    }

    // --- фильтр имён и гасители ---

    @Test
    fun testPrivateAndUnaddressableNamesAreFiltered() {
        val result = names(
            typicalApp(
                "    @property\n" +
                    "    def fields(self):\n" +
                    "        return super().fields + [Field('ok', M), Field('foo-bar', M)]\n" +
                    "\n" +
                    "    def __init__(self):\n" +
                    "        super().__init__()\n" +
                    "        self._private = 1\n",
            ),
        )
        assertTrue("ok" in result)
        assertFalse("foo-bar" in result)
        assertFalse("_private" in result)
    }

    @Test
    fun testBlockerInApplicationClassDisablesDiagnostics() {
        val info = infoOf(typicalApp("    def __getattr__(self, name):\n        return None\n"))
        assertFalse(info!!.diagnosticsSafe)
        // Словарь при этом работает: гаситель отнимает право утверждать, а не имена.
        assertTrue("variables" in info.attributes.keys)
    }

    @Test
    fun testCleanClassOverSafeFloorEnablesDiagnostics() {
        val info = infoOf(
            typicalApp("    @property\n    def fields(self):\n        return super().fields + []\n"),
        )
        assertTrue(info!!.diagnosticsSafe)
    }
}
