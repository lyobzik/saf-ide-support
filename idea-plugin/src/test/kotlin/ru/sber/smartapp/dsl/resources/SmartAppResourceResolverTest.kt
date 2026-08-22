package ru.sber.smartapp.dsl.resources

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Разрешение цепочки ресурсов — те же кейсы, что в
 * `test/core/resourceKeywords.test.ts`: активный класс из `RESOURCES`, свёртка
 * по `super()` и fail-closed на любом обрыве цепочки.
 */
class SmartAppResourceResolverTest {

    private fun reader(files: Map<String, String>) =
        SmartAppResourceResolver.ModuleReader { files[it] }

    private fun keywords(files: Map<String, String>) =
        SmartAppResourceResolver.customKeywords(reader(files))

    /**
     * Класс ресурсов вместе с импортом базы: без импорта база «ниоткуда», и по
     * контракту такая цепочка не подтверждается.
     */
    private fun cls(name: String, base: String, body: String): String =
        "from smart_kit.resources import $base\n\nclass $name($base):\n$body\n"

    private val config = "from app.resources.custom_app_resources import CustomAppResources\n" +
        "RESOURCES = CustomAppResources\n"

    private val base = cls(
        "BaseResources",
        "SmartAppResources",
        listOf(
            "    def init_actions(self):",
            """        actions["base_action"] = BaseAction""",
            """        actions["shared"] = BaseAction""",
            "",
            "    def init_requirements(self):",
            """        requirements["base_requirement"] = BaseRequirement""",
        ).joinToString("\n"),
    )

    private fun derived(superCall: Boolean): String = buildString {
        append("from app.resources.base_resources import BaseResources\n\n")
        append("class CustomAppResources(BaseResources):\n")
        append("    def init_actions(self):\n")
        if (superCall) append("        super().init_actions()\n")
        append("""        actions["custom_action"] = CustomAction""").append('\n')
        append("""        actions["shared"] = CustomAction""").append('\n')
    }

    private fun chain(superCall: Boolean) = keywords(
        mapOf(
            "app_config.py" to config,
            "app/resources/custom_app_resources.py" to derived(superCall),
            "app/resources/base_resources.py" to base,
        ),
    )

    @Test
    fun activeClassFromResources() {
        val found = keywords(
            mapOf(
                "app_config.py" to config,
                "app/resources/custom_app_resources.py" to cls(
                    "CustomAppResources",
                    "SmartAppResources",
                    """    def init_actions(self):
        actions["custom_action"] = CustomAction""",
                ),
            ),
        )
        assertEquals(listOf("custom_action"), found.map { it.name })
        assertEquals("action", found.single().category)
        assertEquals("CustomAction", found.single().className)
        assertEquals("app/resources/custom_app_resources.py", found.single().file)
    }

    @Test
    fun unusedSubclassIsNotScanned() {
        val found = keywords(
            mapOf(
                "app_config.py" to config,
                "app/resources/custom_app_resources.py" to cls(
                    "CustomAppResources",
                    "SmartAppResources",
                    """    def init_actions(self):
        actions["used"] = C""",
                ),
                "app/resources/unused_resources.py" to cls(
                    "UnusedResources",
                    "SmartAppResources",
                    """    def init_actions(self):
        actions["unused"] = C""",
                ),
            ),
        )
        assertEquals(listOf("used"), found.map { it.name })
    }

    @Test
    fun conditionalResourcesGivesNothing() {
        val found = keywords(
            mapOf(
                "app_config.py" to "from app.resources.custom_app_resources import CustomAppResources\n" +
                    "RESOURCES = CustomAppResources\nif dev:\n    RESOURCES = DevResources\n",
                "app/resources/custom_app_resources.py" to cls(
                    "CustomAppResources",
                    "SmartAppResources",
                    """    def init_actions(self):
        actions["x"] = C""",
                ),
            ),
        )
        assertEquals(emptyList<Any>(), found)
    }

    @Test
    fun packageIsReadThroughInitFile() {
        val found = keywords(
            mapOf(
                "app_config.py" to "from app.resources import CustomAppResources\nRESOURCES = CustomAppResources\n",
                "app/resources/__init__.py" to cls(
                    "CustomAppResources",
                    "SmartAppResources",
                    """    def init_actions(self):
        actions["packaged"] = C""",
                ),
            ),
        )
        assertEquals(listOf("packaged"), found.map { it.name })
    }

    @Test
    fun superInheritsBaseRegistrations() {
        assertEquals(
            listOf("base_action", "base_requirement", "custom_action", "shared"),
            chain(superCall = true).map { it.name }.sorted(),
        )
    }

    @Test
    fun derivedRegistrationWins() {
        val shared = chain(superCall = true).single { it.name == "shared" }
        assertEquals("CustomAction", shared.className)
        assertEquals("app/resources/custom_app_resources.py", shared.file)
    }

    @Test
    fun withoutSuperBaseRegistrationsAreLost() {
        assertEquals(
            listOf("base_requirement", "custom_action", "shared"),
            chain(superCall = false).map { it.name }.sorted(),
        )
    }

    @Test
    fun multipleInheritanceGivesNothing() {
        val found = keywords(
            mapOf(
                "app_config.py" to config,
                "app/resources/custom_app_resources.py" to
                    "from app.resources.base_resources import BaseResources\n\n" +
                    "class CustomAppResources(BaseResources, LoggingMixin):\n" +
                    """    def init_actions(self):
        actions["custom_action"] = C""" + "\n",
                "app/resources/base_resources.py" to base,
            ),
        )
        assertEquals(emptyList<Any>(), found)
    }

    @Test
    fun cyclicBaseGivesNothing() {
        val found = keywords(
            mapOf(
                "app_config.py" to config,
                "app/resources/custom_app_resources.py" to
                    "from app.resources.base import BaseResources\n\n" +
                    "class CustomAppResources(BaseResources):\n" +
                    """    def init_actions(self):
        actions["custom"] = C""" + "\n",
                "app/resources/base.py" to
                    "from app.resources.custom_app_resources import CustomAppResources\n\n" +
                    "class BaseResources(CustomAppResources):\n" +
                    """    def init_actions(self):
        actions["base"] = C""" + "\n",
            ),
        )
        assertEquals(emptyList<Any>(), found)
    }

    @Test
    fun missingClassInReadableModuleGivesNothing() {
        val found = keywords(
            mapOf(
                "app_config.py" to config,
                "app/resources/custom_app_resources.py" to
                    "from app.resources.base import BaseResources\n\n" +
                    "class CustomAppResources(BaseResources):\n" +
                    """    def init_actions(self):
        actions["custom"] = C""" + "\n",
                // Модуль читается, а класса в нём нет — это не библиотечная база.
                "app/resources/base.py" to "class Other(SmartAppResources):\n    pass\n",
            ),
        )
        assertEquals(emptyList<Any>(), found)
    }

    @Test
    fun unknownBaseGivesNothing() {
        // `class C(MissingBase)` без импорта — это не библиотечная база, а
        // неизвестность: подтвердить цепочку нечем.
        val found = keywords(
            mapOf(
                "app_config.py" to config,
                "app/resources/custom_app_resources.py" to
                    "class CustomAppResources(MissingBase):\n" +
                    """    def init_actions(self):
        actions["custom"] = C""" + "\n",
            ),
        )
        assertEquals(emptyList<Any>(), found)
    }

    @Test
    fun tooLongChainGivesNothing() {
        val files = HashMap<String, String>()
        files["app_config.py"] = config
        files["app/resources/custom_app_resources.py"] =
            "from app.resources.b0 import R0\n\nclass CustomAppResources(R0):\n" +
            """    def init_actions(self):
        actions["custom"] = C""" + "\n"
        for (i in 0 until 12) {
            files["app/resources/b$i.py"] =
                "from app.resources.b${i + 1} import R${i + 1}\n\nclass R$i(R${i + 1}):\n    pass\n"
        }
        assertEquals(emptyList<Any>(), keywords(files))
    }

    @Test
    fun moduleInExcludedDirectoryIsNotRead() {
        val found = keywords(
            mapOf(
                "app_config.py" to "from venv.resources import R\nRESOURCES = R\n",
                "venv/resources.py" to cls(
                    "R",
                    "SmartAppResources",
                    """    def init_actions(self):
        actions["x"] = C""",
                ),
            ),
        )
        assertEquals(emptyList<Any>(), found)
    }

    @Test
    fun registryWithoutCategoryIsSkipped() {
        val found = keywords(
            mapOf(
                "app_config.py" to config,
                "app/resources/custom_app_resources.py" to cls(
                    "CustomAppResources",
                    "SmartAppResources",
                    listOf(
                        "    def init_db_adapters(self):",
                        """        db_adapters["custom_db_adapter"] = A""",
                        "",
                        "    def init_actions(self):",
                        """        actions["kept"] = C""",
                    ).joinToString("\n"),
                ),
            ),
        )
        assertEquals(listOf("kept"), found.map { it.name })
    }
}
