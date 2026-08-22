package ru.sber.smartapp.dsl.resources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Таблица форм сканера ресурсов — та же, что в `test/core/pythonScan.test.ts`.
 * Расхождение двух реализаций иначе не ловится ничем (план, раздел 5).
 */
class SmartAppResourceScannerTest {

    private fun inMethod(body: String, method: String = "init_actions"): String =
        "class CustomAppResources(SmartAppResources):\n    def $method(self):\n        $body\n"

    private fun registrations(source: String) =
        SmartAppResourceScanner.parseModule(source).classes.flatMap { cls ->
            cls.methods.flatMap { it.registrations }
        }

    @Test
    fun acceptedForms() {
        val cases = listOf(
            Triple("""actions["custom_action"] = CustomAction""", "actions", "custom_action"),
            Triple("""actions['custom_action'] = CustomAction""", "actions", "custom_action"),
            Triple(
                """ffd.field_filler_description["custom_filler"] = C""",
                "field_filler_description",
                "custom_filler",
            ),
            Triple("""core.basic_models.actions.basic_actions.actions["x"] = C""", "actions", "x"),
            Triple("""actions[""${'"'}triple""${'"'}] = C""", "actions", "triple"),
        )
        for ((line, registry, name) in cases) {
            val found = registrations(inMethod(line))
            assertEquals("одна регистрация в '$line'", 1, found.size)
            assertEquals(registry, found[0].registry)
            assertEquals(name, found[0].name)
        }
    }

    @Test
    fun escapeIsDecodedAndRangeStaysRaw() {
        val source = inMethod("""actions["esc\u0041"] = mod.CustomAction""")
        val found = registrations(source).single()
        assertEquals("escA", found.name)
        // Диапазон — сырое содержимое литерала, имя — декодированное.
        assertEquals("""esc\u0041""", source.substring(found.nameStart, found.nameEnd))
        assertEquals("mod.CustomAction", found.className)
    }

    @Test
    fun updateWithDictLiteral() {
        val found = registrations(inMethod("""actions.update({"a": A, 'b': B})"""))
        assertEquals(listOf("a", "b"), found.map { it.name })
        assertEquals(listOf("A", "B"), found.map { it.className })
    }

    @Test
    fun lineBreakInsideBracketsKeepsStatement() {
        val source = "class R(Base):\n    def init_actions(self):\n        actions.update({\n" +
            """            "a": A,""" + "\n" +
            """            "b": B,""" + "\n        })\n"
        assertEquals(listOf("a", "b"), registrations(source).map { it.name })
    }

    @Test
    fun rejectedForms() {
        val rejected = listOf(
            "actions[name] = C",
            "actions[NAME_CONST] = C",
            """actions[f"{p}_x"] = C""",
            """actions["a" + "b"] = C""",
            "actions.update(mapping)",
            "actions.update(**kwargs)",
            """if actions["x"] == C:""",
        )
        for (line in rejected) {
            assertEquals("'$line' не должно давать регистраций", emptyList<Any>(), registrations(inMethod(line)))
        }
    }

    @Test
    fun registrationOutsideInitMethodIsIgnored() {
        assertEquals(emptyList<Any>(), registrations(inMethod("""actions["x"] = C""", "configure")))
    }

    @Test
    fun stringsAndCommentsAreNotParsed() {
        val source = inMethod("""# actions["commented"] = C""" + "\n        doc = " + "\"\"\"" + """actions["inside"] = C""" + "\"\"\"")
        assertEquals(emptyList<Any>(), registrations(source))
    }

    @Test
    fun classDocstringDoesNotHideMethods() {
        val source = "class R(Base):\n    \"\"\"Присвойте RESOURCES этот класс.\n\n    Многострочный docstring не должен выбрасывать разбор из тела класса.\n    \"\"\"\n" +
            "    def init_actions(self):\n        actions[\"real\"] = C\n"
        assertEquals(listOf("real"), registrations(source).map { it.name })
    }

    @Test
    fun moduleStructure() {
        val source = listOf(
            "from smart_kit.resources import SmartAppResources",
            "from app.basic_entities.actions import CustomAction as Action",
            "import app.adapters.db_adapters as adapters",
            "",
            "class CustomAppResources(SmartAppResources):",
            "    def init_actions(self):",
            "        super().init_actions()",
            """        actions["custom_action"] = Action""",
            "",
            "    def init_requirements(self):",
            """        requirements["custom_requirement"] = R""",
            "",
            "RESOURCES = CustomAppResources",
        ).joinToString("\n")

        val module = SmartAppResourceScanner.parseModule(source)
        val cls = module.classes.single()
        assertEquals("CustomAppResources", cls.name)
        assertEquals(listOf("SmartAppResources"), cls.bases)
        assertEquals(
            listOf("init_actions" to true, "init_requirements" to false),
            cls.methods.map { it.name to it.callsSuper },
        )
        assertEquals(
            SmartAppResourceScanner.ImportedName("app.basic_entities.actions", "CustomAction"),
            module.imports["Action"],
        )
        assertEquals("app.adapters.db_adapters", module.moduleImports["adapters"])
        assertEquals("CustomAppResources", module.topLevelVars["RESOURCES"])
        assertTrue("RESOURCES" !in module.conditionalVars)
    }

    @Test
    fun keywordArgumentsAreNotBases() {
        val module = SmartAppResourceScanner.parseModule("class C(Base, metaclass=M):\n    pass\n")
        assertEquals(listOf("Base"), module.classes.single().bases)
    }

    @Test
    fun conditionalResourcesAssignmentIsMarked() {
        val module = SmartAppResourceScanner.parseModule(
            "if dev:\n    RESOURCES = DevResources\nelse:\n    RESOURCES = ProdResources\n",
        )
        assertTrue("RESOURCES" in module.conditionalVars)
        assertTrue(module.topLevelVars["RESOURCES"] == null)
    }
}
