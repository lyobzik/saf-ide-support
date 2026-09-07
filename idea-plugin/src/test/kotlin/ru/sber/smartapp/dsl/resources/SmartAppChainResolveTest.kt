package ru.sber.smartapp.dsl.resources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Разрешение цепочки наследования как самостоятельный примитив.
 *
 * У резолвера два потребителя с разными нуждами: ресурсам нужны только классы
 * приложения, модели пользователя — ещё и имя библиотечной базы, по которому
 * выбирается пол из снимка фреймворка. Отсюда два исхода, которых раньше не
 * было: пустая цепочка при известной базе (активный класс библиотечный) и
 * цепочка, кончившаяся классом без базы.
 *
 * Та же таблица входов прогоняется в ядре расширения (`chainResolve.test.ts`).
 */
class SmartAppChainResolveTest {

    private fun reader(files: Map<String, String>) = object : SmartAppResourceResolver.AppFiles {
        override fun read(relativePath: String): String? = files[relativePath]
        override fun exists(path: String): Boolean =
            files.keys.any { it == path || it.startsWith("$path/") }
    }

    /** Разрешает значение переменной [variable] из `app_config.py` и строит цепочку. */
    private fun resolve(
        files: Map<String, String>,
        variable: String,
    ): SmartAppResourceResolver.ChainResult? {
        val config = SmartAppResourceScanner.parseModule(files.getValue("app_config.py"))
        val value = config.topLevelVars[variable] ?: return null
        val start = SmartAppResourceResolver.classRefOf(value, config, "app_config.py") ?: return null
        return SmartAppResourceResolver.resolveChain(start, reader(files))
    }

    private fun config(importLine: String, assignment: String) = "$importLine\n\n$assignment\n"

    @Test
    fun testApplicationClassOverLibraryUser() {
        val result = resolve(
            mapOf(
                "app_config.py" to config("from app.user.user import CustomUser", "USER = CustomUser"),
                "app/user/user.py" to
                    "from scenarios.user.user_model import User\n\n\nclass CustomUser(User):\n    pass\n",
            ),
            "USER",
        )
        assertEquals(listOf("CustomUser"), result?.classes?.map { it.cls.name })
        assertEquals("scenarios.user.user_model.User", result?.libraryBase)
    }

    @Test
    fun testApplicationClassOverLibraryBaseUser() {
        val result = resolve(
            mapOf(
                "app_config.py" to config("from app.user.user import CustomUser", "USER = CustomUser"),
                "app/user/user.py" to
                    "from core.model.base_user import BaseUser\n\n\nclass CustomUser(BaseUser):\n    pass\n",
            ),
            "USER",
        )
        assertEquals("core.model.base_user.BaseUser", result?.libraryBase)
    }

    @Test
    fun testTwoApplicationClassesFromBaseToDerived() {
        val result = resolve(
            mapOf(
                "app_config.py" to config("from app.user.user import CustomUser", "USER = CustomUser"),
                "app/user/user.py" to
                    "from app.user.middle import Middle\n\n\nclass CustomUser(Middle):\n    pass\n",
                "app/user/middle.py" to
                    "from scenarios.user.user_model import User\n\n\nclass Middle(User):\n    pass\n",
            ),
            "USER",
        )
        assertEquals(listOf("Middle", "CustomUser"), result?.classes?.map { it.cls.name })
        assertEquals("scenarios.user.user_model.User", result?.libraryBase)
    }

    @Test
    fun testLibraryClassNamedDirectly() {
        // Раньше этот исход был отказом (`chain.isEmpty()` -> null). Для модели
        // пользователя он основной: так выглядит `USER = User` и подстановка
        // библиотечного дефолта при отсутствующем `USER`.
        val result = resolve(
            mapOf(
                "app_config.py" to
                    config("from scenarios.user.user_model import User", "USER = User"),
            ),
            "USER",
        )
        assertEquals(emptyList<String>(), result?.classes?.map { it.cls.name })
        assertEquals("scenarios.user.user_model.User", result?.libraryBase)
    }

    @Test
    fun testClassWithoutBaseHasNoLibraryFloor() {
        val result = resolve(
            mapOf(
                "app_config.py" to config("from app.user.user import CustomUser", "USER = CustomUser"),
                "app/user/user.py" to "class CustomUser:\n    pass\n",
            ),
            "USER",
        )
        assertEquals(listOf("CustomUser"), result?.classes?.map { it.cls.name })
        assertNull(result?.libraryBase)
    }

    @Test
    fun testPackageFormGivesSameDottedName() {
        val result = resolve(
            mapOf(
                "app_config.py" to config("from app.user.user import CustomUser", "USER = CustomUser"),
                "app/user/user.py" to "from a.b.c import Base\n\n\nclass CustomUser(Base):\n    pass\n",
            ),
            "USER",
        )
        // Модуль `a.b.c` мог бы лежать и как `a/b/c/__init__.py`; имя базы от
        // этого не зависит — ClassRef.file всегда хранит `.py`-вариант.
        assertEquals("a.b.c.Base", result?.libraryBase)
    }

    @Test
    fun testUnmatchedBaseInvalidatesChain() {
        val result = resolve(
            mapOf(
                "app_config.py" to config("from app.user.user import CustomUser", "USER = CustomUser"),
                "app/user/user.py" to "class CustomUser(Unknown):\n    pass\n",
            ),
            "USER",
        )
        assertNull(result)
    }

    @Test
    fun testMissingApplicationModuleInvalidatesChain() {
        val result = resolve(
            mapOf(
                "app_config.py" to config("from app.user.user import CustomUser", "USER = CustomUser"),
                // Модуля нет, но корневой пакет `app` в приложении есть: значит
                // это наш модуль, которого не хватает, а не библиотека.
                "app/other.py" to "x = 1\n",
            ),
            "USER",
        )
        assertNull(result)
    }

    @Test
    fun testMultipleBasesInvalidateChain() {
        val result = resolve(
            mapOf(
                "app_config.py" to config("from app.user.user import CustomUser", "USER = CustomUser"),
                "app/user/user.py" to
                    "from scenarios.user.user_model import User\nfrom app.user.mixin import Mixin\n\n\n" +
                    "class CustomUser(User, Mixin):\n    pass\n",
                "app/user/mixin.py" to "class Mixin:\n    pass\n",
            ),
            "USER",
        )
        assertNull(result)
    }

    @Test
    fun testInheritanceCycleInvalidatesChain() {
        val result = resolve(
            mapOf(
                "app_config.py" to config("from app.user.a import A", "USER = A"),
                "app/user/a.py" to "from app.user.b import B\n\n\nclass A(B):\n    pass\n",
                "app/user/b.py" to "from app.user.a import A\n\n\nclass B(A):\n    pass\n",
            ),
            "USER",
        )
        assertNull(result)
    }
}
