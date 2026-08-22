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

    @Test
    fun controlFlowInsideMethodIsRejected() {
        // Регистрация под `if`/`for`/`try`/`with` условна: в рантайме её может не
        // быть, поэтому принимаются только прямые операторы тела метода.
        for (header in listOf("if enabled:", "for name in names:", "try:", "with lock:")) {
            val source = "class R(Base):\n    def init_actions(self):\n" +
                "        $header\n" +
                """            actions["nested"] = C""" + "\n" +
                """        actions["direct"] = C""" + "\n"
            assertEquals("'$header'", listOf("direct"), registrations(source).map { it.name })
        }
    }

    @Test
    fun superInsideConditionIsNotCounted() {
        val source = "class R(Base):\n    def init_actions(self):\n" +
            "        if enabled:\n            super().init_actions()\n"
        val method = SmartAppResourceScanner.parseModule(source).classes.single().methods.single()
        assertEquals(false, method.callsSuper)
    }

    @Test
    fun updateTakesOnlyTopLevelKeys() {
        val nested = "class R(Base):\n    def init_actions(self):\n        actions.update({\n" +
            """            "outer": {""" + "\n" +
            """                "inner": CustomAction,""" + "\n" +
            "            },\n        })\n"
        assertEquals(listOf("outer"), registrations(nested).map { it.name })

        val list = "class R(Base):\n    def init_actions(self):\n" +
            """        actions.update({"outer": ["inner", "second"]})""" + "\n"
        assertEquals(listOf("outer"), registrations(list).map { it.name })
    }

    @Test
    fun supportedEscapes() {
        val cases = listOf(
            """"a@BSu0041b"""" to "aAb",
            """"a@BSx41b"""" to "aAb",
            """"a@BS@BSb"""" to "a@BSb",
            "'it@BS's'" to "it's",
        )
        for ((literal, expected) in cases) {
            val source = inMethod("actions[${literal.replace("@BS", BACKSLASH)}] = C")
            assertEquals(
                literal,
                expected.replace("@BS", BACKSLASH),
                registrations(source).single().name,
            )
        }
    }

    @Test
    fun unsupportedEscapesRejectRegistration() {
        // Контракт escape сознательно узкий: подставить не то имя хуже, чем никакого.
        val rejected = listOf(""""bell@BSa"""", """"back@BSb"""", """"octal@BS101"""", """"named@BSN{BULLET}"""", """"huge@BSU00110000"""")
        for (literal in rejected) {
            val source = inMethod("actions[${literal.replace("@BS", BACKSLASH)}] = C")
            assertEquals(literal, emptyList<Any>(), registrations(source))
        }
    }

    @Test
    fun rawStringKeepsEscapedQuote() {
        val source = inMethod("""actions[r"a@BS"b"] = C""".replace("@BS", BACKSLASH))
        assertEquals(listOf("""a@BS"b""".replace("@BS", BACKSLASH)), registrations(source).map { it.name })
    }

    private companion object {
        /** Обратный слэш строкой: в исходниках тестов он иначе съедается разбором. */
        const val BACKSLASH = "\\"
    }

    @Test
    fun inlineSuiteIsRejected() {
        val source = "class R(Base):\n    def init_actions(self):\n" +
            """        if enabled: actions["wrong"] = C""" + "\n" +
            """        actions["direct"] = C""" + "\n"
        assertEquals(listOf("direct"), registrations(source).map { it.name })
    }

    @Test
    fun inlineSuperInConditionIsNotCounted() {
        val source = "class R(Base):\n    def init_actions(self):\n" +
            "        if enabled: super().init_actions()\n"
        assertEquals(
            false,
            SmartAppResourceScanner.parseModule(source).classes.single().methods.single().callsSuper,
        )
    }

    @Test
    fun inlineTopLevelConditionMarksVariable() {
        val module = SmartAppResourceScanner.parseModule(
            "RESOURCES = ProdResources\nif dev: RESOURCES = DevResources\n",
        )
        assertEquals(true, "RESOURCES" in module.conditionalVars)
    }

    @Test
    fun superRequiresIdentifierBoundary() {
        for (call in listOf("my_super().init_actions()", "obj.super().init_actions()")) {
            val source = "class R(Base):\n    def init_actions(self):\n        $call\n"
            assertEquals(
                call,
                false,
                SmartAppResourceScanner.parseModule(source).classes.single().methods.single().callsSuper,
            )
        }
        val real = "class R(Base):\n    def init_actions(self):\n        super().init_actions()\n"
        assertEquals(
            true,
            SmartAppResourceScanner.parseModule(real).classes.single().methods.single().callsSuper,
        )
    }

    @Test
    fun importsWithCommentAndExtraSpacesAreParsed() {
        for (line in listOf(
            "from app.resources import R  # комментарий",
            "from app.resources    import R",
        )) {
            val module = SmartAppResourceScanner.parseModule("$line\nRESOURCES = R\n")
            assertEquals(
                line,
                SmartAppResourceScanner.ImportedName("app.resources", "R"),
                module.imports["R"],
            )
        }
    }

    @Test
    fun nestedClassInsideMethodIsNotResourceClass() {
        val source = "class R(Base):\n    def init_actions(self):\n" +
            "        class Nested:\n" +
            "            def init_actions(self):\n" +
            """                actions["wrong"] = C""" + "\n"
        assertEquals(emptyList<Any>(), registrations(source))
        assertEquals(listOf("R"), SmartAppResourceScanner.parseModule(source).classes.map { it.name })
    }

    @Test
    fun octalEscapeIsRejectedWhileNulIsSupported() {
        assertEquals(
            emptyList<Any>(),
            registrations(inMethod("""actions["a@BS012b"] = C""".replace("@BS", BACKSLASH))),
        )
        assertEquals(
            "a" + '\u0000' + "b",
            registrations(inMethod("""actions["a@BS0b"] = C""".replace("@BS", BACKSLASH))).single().name,
        )
    }
}
