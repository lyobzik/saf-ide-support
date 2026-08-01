package ru.sber.smartapp.dsl

import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.usageView.UsageViewLongNameLocation
import com.intellij.usageView.UsageViewTypeLocation
import ru.sber.smartapp.dsl.findusages.SmartAppElementDescriptionProvider
import ru.sber.smartapp.dsl.highlight.SmartAppTextAttributes
import ru.sber.smartapp.dsl.reference.SmartAppFieldReference

/**
 * Интеграционные тесты поддержки полей форм и Jinja-навигации/подсветки.
 *
 * Фикстуры: форма `hello_form` с полями `name` и `age`; сценарий `s` ссылается на
 * неё через статический `form`. В сценарии используются Jinja-значения
 * `{{ main_form.<field> }}`.
 */
class SmartAppFormFieldTest : BasePlatformTestCase() {

    private val descriptionProvider = SmartAppElementDescriptionProvider()

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "static/references/forms/forms.json",
            """
            {
              "hello_form": {
                "type": "form",
                "fields": {
                  "name": { "type": "question" },
                  "age": { "type": "question" },
                  "age": { "type": "integration" }
                }
              }
            }
            """.trimIndent(),
        )
    }

    // ---- резолв ---------------------------------------------------------

    fun testResolvesFormFieldInScenario() {
        val scn = addScenario("s1", """"greeting": "{{ main_form.name }}"""")
        val ref = fieldReference(scn, "greeting", "{{ main_form.name }}")
        assertEquals("main_form.name резолвится в поле формы", 1, ref.multiResolve(false).size)
        val resolved = ref.multiResolve(false)[0].element as JsonProperty
        assertEquals("name", resolved.name)
    }

    fun testResolvesJinjaInsideNestedActionObject() {
        // Jinja внутри вложенного объекта сценария: targetFormOf поднимается к
        // top-level свойству `form` сценария.
        val scn = addScenario(
            "s2",
            """"actions": [ { "type": "sdk_answer", "message": "{{ main_form.name }}" } ]""",
        )
        val ref = fieldReference(scn, "message", "{{ main_form.name }}")
        assertEquals(1, ref.multiResolve(false).size)
    }

    fun testDynamicFormHasNoReference() {
        // `"form": "{{ main_form.name }}"` — динамический выбор формы.
        // targetFormOf возвращает null: ссылки на поле нет, WARNING нет.
        myFixture.addFileToProject(
            "static/references/scenarios/dyn.json",
            """{ "dyn": { "type": "form_filling", "form": "{{ main_form.name }}" } }""",
        )
        val file = findFile("static/references/scenarios/dyn.json")
        val literal = findLiteral(file, "form", "{{ main_form.name }}")
        val refs = literal.references.filterIsInstance<SmartAppFieldReference>()
        assertTrue("динамическая форма не даёт ссылки на поле", refs.isEmpty())
    }

    fun testDuplicateFieldMultiResolves() {
        // Поле `age` определено дважды в hello_form — multiResolve size == 2.
        val scn = addScenario("s3", """"g": "{{ main_form.age }}"""")
        val ref = fieldReference(scn, "g", "{{ main_form.age }}")
        assertEquals("дубли поля формы дают multi-resolve size 2", 2, ref.multiResolve(false).size)
    }

    fun testFieldReferenceRangeCoversOnlyFieldName() {
        val scn = addScenario("s4", """"g": "{{ main_form.name }}"""")
        val ref = fieldReference(scn, "g", "{{ main_form.name }}")
        // Диапазон ссылки — только на "name", не на весь "main_form.name".
        val literal = findLiteral(scn, "g", "{{ main_form.name }}")
        val absoluteRange = ref.rangeInElement.shiftRight(literal.textOffset)
        assertEquals("name", literal.containingFile.text.substring(absoluteRange.startOffset, absoluteRange.endOffset))
    }

    // ---- negative: нерелевантные Jinja-конструкции ---------------------

    fun testNoReferenceForVariablesDotField() {
        // `{{ x.y }}` — не main_form, ссылки на поле нет.
        val scn = addScenario("s5", """"g": "{{ x.y }}"""")
        val literal = findLiteral(scn, "g", "{{ x.y }}")
        assertTrue(literal.references.filterIsInstance<SmartAppFieldReference>().isEmpty())
    }

    fun testNoReferenceForStatementTag() {
        // `{% if x %}` — statement, ссылок на поле не создаёт.
        val scn = addScenario("s7", """"g": "{% if x %}hi{% endif %}"""")
        val literal = findLiteral(scn, "g", "{% if x %}hi{% endif %}")
        assertTrue(literal.references.filterIsInstance<SmartAppFieldReference>().isEmpty())
    }

    fun testNoReferenceOrWarningForVariablesMainFormChain() {
        // `{{ variables.main_form.unknown }}` — main_form в позиции чужого поля:
        // вне scope main_form.<field>, ни ссылки, ни WARNING.
        val scn = addScenario("s_chain", """"g": "{{ variables.main_form.unknown }}"""")
        val literal = findLiteral(scn, "g", "{{ variables.main_form.unknown }}")
        assertTrue(
            "цепочка variables.main_form не даёт SmartAppFieldReference",
            literal.references.filterIsInstance<SmartAppFieldReference>().isEmpty(),
        )
        myFixture.openFileInEditor(scn.virtualFile)
        val warnings = myFixture.doHighlighting(HighlightSeverity.WARNING)
        assertFalse(
            "цепочка variables.main_form не даёт WARNING о поле",
            warnings.any { it.description?.contains("Не удаётся разрешить поле") == true },
        )
    }

    fun testNoReferenceForSubobjectChain() {
        // `{{ main_form.name.extra }}` — подобъекты main_form.x.y не разбираются:
        // ссылки на name не создаётся.
        val scn = addScenario("s_sub", """"g": "{{ main_form.name.extra }}"""")
        val literal = findLiteral(scn, "g", "{{ main_form.name.extra }}")
        assertTrue(
            "подобъект main_form.x.y не даёт SmartAppFieldReference",
            literal.references.filterIsInstance<SmartAppFieldReference>().isEmpty(),
        )
    }

    // ---- regression: statement-тег с main_form не даёт ссылки/WARNING ---

    fun testStatementTagWithMainFormHasNoReferenceOrWarning() {
        // {% set x = main_form.unknown %} — statement-тег: семантика полей
        // допустима только внутри {{ }}. Здесь нет ни ссылки, ни WARNING.
        val scn = addScenario("s_stmt", """"g": "{% set x = main_form.unknown %}"""")
        val literal = findLiteral(scn, "g", "{% set x = main_form.unknown %}")
        assertTrue(
            "statement-тег не должен давать SmartAppFieldReference",
            literal.references.filterIsInstance<SmartAppFieldReference>().isEmpty(),
        )
        myFixture.openFileInEditor(scn.virtualFile)
        val warnings = myFixture.doHighlighting(HighlightSeverity.WARNING)
        assertFalse(
            "statement-тег не должен давать WARNING о поле",
            warnings.any { it.description?.contains("Не удаётся разрешить поле") == true },
        )
    }

    // ---- regression: закрывающий разделитель внутри Jinja-строки -------

    fun testCloseDelimInsideJinjaStringDoesNotBreak() {
        // {{ main_form.name | default('}}') }} — }} внутри строки не закрывает
        // интерполяцию; поле name резолвится корректно.
        val scn = addScenario("s_str", """"g": "{{ main_form.name | default('}}') }}"""")
        val ref = fieldReference(scn, "g", "{{ main_form.name | default('}}') }}")
        assertEquals("поле резолвится несмотря на }} внутри строки", 1, ref.multiResolve(false).size)
    }

    fun testJsonEscapedDoubleQuotedJinjaString() {
        // JSON-представление с экранированной двойной кавычкой внутри Jinja.
        // В файле записано \"y\"; лексер раскрывает escape -> decoded value
        // содержит обычную двойную кавычку.
        val scn = myFixture.addFileToProject(
            "static/references/scenarios/esc.json",
            """{ "esc": { "type": "form_filling", "form": "hello_form", "g": "{{ main_form.name | default(\"y\") }}" } }""",
        )
        val ref = fieldReference(scn, "g", """{{ main_form.name | default("y") }}""")
        assertEquals(1, ref.multiResolve(false).size)
    }

    // ---- regression: Jinja в JSON-ключах и элементах массива ------------

    fun testJinjaKeyGetsNoFieldReference() {
        // JSON-ключ "{{ main_form.name }}" — не значение: ссылки на поле нет.
        myFixture.addFileToProject(
            "static/references/scenarios/jkey.json",
            """{ "jkey": { "type": "form_filling", "form": "hello_form", "{{ main_form.name }}": "x" } }""",
        )
        val file = findFile("static/references/scenarios/jkey.json")
        val keyLiteral = PsiTreeUtil.findChildrenOfType(file, JsonProperty::class.java)
            .first { it.name == "{{ main_form.name }}" }.nameElement as JsonStringLiteral
        assertTrue(
            "JSON-ключ не должен получать SmartAppFieldReference",
            keyLiteral.references.filterIsInstance<SmartAppFieldReference>().isEmpty(),
        )
    }

    fun testResolvesFieldInArrayValue() {
        val scn = myFixture.addFileToProject(
            "static/references/scenarios/arrres.json",
            """{ "arrres": { "type": "form_filling", "form": "hello_form", "messages": ["{{ main_form.name }}"] } }""",
        )
        val literal = findArrayLiteral(scn, "{{ main_form.name }}")
        val ref = literal.references.filterIsInstance<SmartAppFieldReference>().firstOrNull()
            ?: error("no SmartAppFieldReference on array element")
        assertEquals("поле в элементе массива резолвится", 1, ref.multiResolve(false).size)
    }

    fun testUnresolvedFieldInArrayValueIsWarning() {
        val scn = myFixture.addFileToProject(
            "static/references/scenarios/arrwarn.json",
            """{ "arrwarn": { "type": "form_filling", "form": "hello_form", "messages": ["{{ main_form.unknown }}"] } }""",
        )
        myFixture.openFileInEditor(scn.virtualFile)
        val warnings = myFixture.doHighlighting(HighlightSeverity.WARNING)
        assertTrue(
            "ожидался WARNING о поле в элементе массива",
            warnings.any { it.description?.contains("Не удаётся разрешить поле") == true },
        )
    }

    fun testJinjaAttributesInArrayValue() {
        // Подсветка Jinja в элементе массива — с точным диапазоном VAR(name).
        val scn = myFixture.addFileToProject(
            "static/references/scenarios/arrhl.json",
            """{ "arrhl": { "type": "form_filling", "form": "hello_form", "messages": ["{{ main_form.name }}"] } }""",
        )
        myFixture.openFileInEditor(scn.virtualFile)
        val infos = myFixture.doHighlighting()
        val nameStart = scn.text.indexOf("name", scn.text.indexOf("messages"))
        val hasVar = infos.any {
            it.forcedTextAttributesKey == SmartAppTextAttributes.JINJA_VAR &&
                it.startOffset == nameStart && it.endOffset == nameStart + "name".length
        }
        assertTrue("JINJA_VAR на 'name' в элементе массива", hasVar)
    }

    // ---- автодополнение -------------------------------------------------

    fun testFieldCompletionInsideJinja() {
        val items = completeAt(
            "static/references/scenarios/comp.json",
            """{ "comp": { "type": "form_filling", "form": "hello_form", "g": "{{ main_form.<caret> }}" } }""",
        )
        assertTrue("expected field names, got: $items", items.contains("name"))
        assertTrue("expected age, got: $items", items.contains("age"))
    }

    fun testNoFieldCompletionWithoutForm() {
        // Сценарий без `form` — targetFormOf null, completion полей пуст.
        val items = completeAt(
            "static/references/scenarios/noform.json",
            """{ "noform": { "type": "form_filling", "g": "{{ main_form.<caret> }}" } }""",
        )
        assertFalse(items.contains("name"))
    }

    fun testNoFieldCompletionInsideStatementTag() {
        // Каретка внутри {% %} — statement-тег, completion полей не активируется
        // и не падает (regression: ранее выбрасывал исключение).
        val items = completeAt(
            "static/references/scenarios/stmt.json",
            """{ "stmt": { "type": "form_filling", "form": "hello_form", "g": "{% set x = main_form.<caret> %}" } }""",
        )
        assertFalse("statement-тег не должен давать completion полей", items.contains("name"))
    }

    fun testNoFieldCompletionAfterClosedInterpolation() {
        // Каретка после закрытой интерполяции — вне {{ }}; completion полей
        // не активируется (regression: ранее активировался по последнему {{).
        val items = completeAt(
            "static/references/scenarios/after.json",
            """{ "after": { "type": "form_filling", "form": "hello_form", "g": "{{ main_form.name }}.<caret>" } }""",
        )
        assertFalse(items.contains("name"))
    }

    fun testFieldCompletionInArrayValue() {
        // Jinja в элементе массива: completion полей работает и без JsonProperty.
        val items = completeAt(
            "static/references/scenarios/arrcomp.json",
            """{ "arrcomp": { "type": "form_filling", "form": "hello_form", "messages": ["{{ main_form.<caret> }}"] } }""",
        )
        assertTrue("completion полей в элементе массива: $items", items.contains("name"))
        assertTrue(items.contains("age"))
    }

    fun testNoFieldCompletionInsideFilterStringArgument() {
        // Каретка внутри строкового аргумента фильтра: текст '{{ main_form.' —
        // содержимое Jinja-строки (STRING-токен), completion полей не активируется.
        val items = completeAt(
            "static/references/scenarios/fstr.json",
            """{ "fstr": { "type": "form_filling", "form": "hello_form", "g": "{{ main_form.name | default('{{ main_form.<caret>') }}" } }""",
        )
        assertFalse("внутри Jinja-строки нет completion полей", items.contains("name"))
        assertFalse(items.contains("age"))
    }

    fun testFieldCompletionInUnclosedInterpolation() {
        // В момент набора закрывающего }} ещё нет: completion полей работает
        // и в незакрытой интерполяции (контекст достраивается синтетическим }}).
        val items = completeAt(
            "static/references/scenarios/unclosed.json",
            """{ "unclosed": { "type": "form_filling", "form": "hello_form", "g": "{{ main_form.<caret>" } }""",
        )
        assertTrue("completion полей в незакрытой интерполяции: $items", items.contains("name"))
        assertTrue(items.contains("age"))
    }

    fun testFieldCompletionAfterSpaceAfterDot() {
        // {{ main_form. <caret> }} — пробел после точки допустим регексом;
        // prefix нормализуется, поля предлагаются.
        val items = completeAt(
            "static/references/scenarios/spaced.json",
            """{ "spaced": { "type": "form_filling", "form": "hello_form", "g": "{{ main_form. <caret> }}" } }""",
        )
        assertTrue("completion полей после пробела за точкой: $items", items.contains("name"))
        assertTrue(items.contains("age"))
    }

    fun testNoFieldCompletionInSubobjectChain() {
        // {{ main_form.x.<caret> }} — позиция цепочки: резолв для main_form.x.y
        // отключён, completion не должен предлагать заведомо битые варианты.
        val items = completeAt(
            "static/references/scenarios/subchain.json",
            """{ "subchain": { "type": "form_filling", "form": "hello_form", "g": "{{ main_form.x.<caret> }}" } }""",
        )
        assertFalse("в позиции цепочки нет completion полей", items.contains("name"))
        assertFalse(items.contains("age"))
    }

    fun testNoFieldCompletionAfterNonIdentifierStart() {
        // {{ main_form.2<caret> }} — частичное имя не является идентификатором:
        // completion полей не активируется.
        val items = completeAt(
            "static/references/scenarios/digit.json",
            """{ "digit": { "type": "form_filling", "form": "hello_form", "g": "{{ main_form.2<caret> }}" } }""",
        )
        assertFalse("после не-identifier начала нет completion полей", items.contains("name"))
        assertFalse(items.contains("age"))
    }

    fun testCompletionSkipsNonIdentifierFieldNames() {
        // Поле с пунктуацией в имени (contact.email) лексер резолвить не способен —
        // completion его не предлагает; обычные поля предлагаются.
        myFixture.addFileToProject(
            "static/references/forms/punct.json",
            """{ "punct_form": { "type": "form", "fields": { "contact.email": { "type": "question" }, "ok": { "type": "question" } } } }""",
        )
        val items = completeAt(
            "static/references/scenarios/punct.json",
            """{ "punct": { "type": "form_filling", "form": "punct_form", "g": "{{ main_form.<caret> }}" } }""",
        )
        assertTrue("identifier-поле предлагается: $items", items.contains("ok"))
        assertFalse("не-identifier поле отфильтровано: $items", items.contains("contact.email"))
    }

    // ---- unresolved WARNING --------------------------------------------

    fun testUnresolvedFieldIsWarning() {
        val scn = addScenario("s8", """"g": "{{ main_form.unknown }}"""")
        myFixture.openFileInEditor(scn.virtualFile)
        val warnings = myFixture.doHighlighting(HighlightSeverity.WARNING)
        assertTrue(
            "expected unresolved-field warning",
            warnings.any { it.description?.contains("Не удаётся разрешить поле") == true },
        )
    }

    fun testNoWarningForDynamicForm() {
        myFixture.addFileToProject(
            "static/references/scenarios/dyn2.json",
            """{ "dyn2": { "type": "form_filling", "form": "{{ main_form.name }}" } }""",
        )
        val file = findFile("static/references/scenarios/dyn2.json")
        myFixture.openFileInEditor(file.virtualFile)
        val warnings = myFixture.doHighlighting(HighlightSeverity.WARNING)
        assertFalse(
            "динамическая форма не даёт WARNING о поле",
            warnings.any { it.description?.contains("Не удаётся разрешить поле") == true },
        )
    }

    // ---- подсветка Jinja-разметки --------------------------------------

    fun testJinjaAttributesAreApplied() {
        // Одинарные кавычки внутри Jinja: двойные сломали бы JSON-строку.
        val scn = addScenario("s9", """"g": "{{ main_form.name | default('x') }}"""")
        myFixture.openFileInEditor(scn.virtualFile)
        val infos = myFixture.doHighlighting()
        val attrs = infos.mapNotNull { it.forcedTextAttributesKey }.toSet()
        assertTrue("JINJA_DELIM: $attrs", attrs.contains(SmartAppTextAttributes.JINJA_DELIM))
        assertTrue("JINJA_VAR: $attrs", attrs.contains(SmartAppTextAttributes.JINJA_VAR))
        assertTrue("JINJA_OP: $attrs", attrs.contains(SmartAppTextAttributes.JINJA_OP))
        assertTrue("JINJA_FILTER: $attrs", attrs.contains(SmartAppTextAttributes.JINJA_FILTER))
        assertTrue("JINJA_STRING: $attrs", attrs.contains(SmartAppTextAttributes.JINJA_STRING))
    }

    fun testFilterNameRangeHasJinjaFilterAttribute() {
        // Имя фильтра `default` должно подсвечиваться JINJA_FILTER (как и `|`),
        // а не JINJA_VAR. Проверяем точный диапазон слова default.
        val scn = addScenario("s_fn", """"g": "{{ main_form.name | default('x') }}"""")
        myFixture.openFileInEditor(scn.virtualFile)
        val infos = myFixture.doHighlighting()
        val literal = findLiteral(scn, "g", "{{ main_form.name | default('x') }}")
        // Находим диапазон "default" внутри литерала.
        val defaultText = "default"
        val defaultRel = literal.value.indexOf(defaultText)
        assertTrue("default должен быть в literal", defaultRel >= 0)
        val defaultAbsStart = literal.textOffset + 1 + defaultRel
        val defaultAbsEnd = defaultAbsStart + defaultText.length
        val hasFilter = infos.any {
            it.forcedTextAttributesKey == SmartAppTextAttributes.JINJA_FILTER &&
                it.startOffset == defaultAbsStart && it.endOffset == defaultAbsEnd
        }
        assertTrue("JINJA_FILTER должен быть на диапазоне 'default'", hasFilter)
    }

    fun testJinjaTokenRangesArePrecise() {
        // Точные raw-диапазоны атрибутов DELIM/VAR/DOT/STRING на демо-строке,
        // а не просто наличие атрибута где-либо в файле.
        val scn = addScenario("s_rng", """"g": "{{ main_form.name | default('x') }}"""")
        myFixture.openFileInEditor(scn.virtualFile)
        val infos = myFixture.doHighlighting()
        val fileText = scn.text

        fun hasRange(attr: TextAttributesKey, start: Int, end: Int) = infos.any {
            it.forcedTextAttributesKey == attr && it.startOffset == start && it.endOffset == end
        }

        val interpOpen = fileText.indexOf("{{")
        assertTrue("DELIM '{{'", hasRange(SmartAppTextAttributes.JINJA_DELIM, interpOpen, interpOpen + 2))
        val interpClose = fileText.indexOf("}}")
        assertTrue("DELIM '}}'", hasRange(SmartAppTextAttributes.JINJA_DELIM, interpClose, interpClose + 2))
        val mainForm = fileText.indexOf("main_form")
        assertTrue("VAR 'main_form'", hasRange(SmartAppTextAttributes.JINJA_VAR, mainForm, mainForm + "main_form".length))
        val dot = fileText.indexOf('.', mainForm)
        assertTrue("DOT", hasRange(SmartAppTextAttributes.JINJA_OP, dot, dot + 1))
        val string = fileText.indexOf("'x'")
        assertTrue("STRING 'x'", hasRange(SmartAppTextAttributes.JINJA_STRING, string, string + 3))
    }

    fun testJsonEscapedDoubleQuotedJinjaStringHighlighting() {
        // Raw-файл содержит \"y\"; STRING-атрибут покрывает raw-диапазон \"y\"
        // целиком (decoded-диапазон строки транслирован через decodedToRaw).
        val scn = myFixture.addFileToProject(
            "static/references/scenarios/eschl.json",
            """{ "eschl": { "type": "form_filling", "form": "hello_form", "g": "{{ main_form.name | default(\"y\") }}" } }""",
        )
        myFixture.openFileInEditor(scn.virtualFile)
        val infos = myFixture.doHighlighting()
        val stringStart = scn.text.indexOf("\\\"y\\\"")
        assertTrue("raw-файл содержит \\\"y\\\"", stringStart >= 0)
        val hasString = infos.any {
            it.forcedTextAttributesKey == SmartAppTextAttributes.JINJA_STRING &&
                it.startOffset == stringStart && it.endOffset == stringStart + "\\\"y\\\"".length
        }
        assertTrue("JINJA_STRING покрывает raw-диапазон \\\"y\\\"", hasString)
    }

    // ---- dumb mode ------------------------------------------------------

    fun testDumbModeResolveSilent() {
        val scn = addScenario("s10", """"g": "{{ main_form.name }}"""")
        val ref = fieldReference(scn, "g", "{{ main_form.name }}")
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            assertEquals("резолв поля тих в dumb mode", 0, ref.multiResolve(false).size)
        }
    }

    // ---- scope-изоляция -------------------------------------------------

    fun testScopeIsolatesFieldSets() {
        myFixture.addFileToProject(
            "projA/static/references/forms/f.json",
            """{ "f": { "type": "form", "fields": { "x": { "type": "question" } } } }""",
        )
        myFixture.addFileToProject(
            "projB/static/references/forms/f.json",
            """{ "f": { "type": "form", "fields": { "y": { "type": "question" } } } }""",
        )
        myFixture.addFileToProject(
            "projA/static/references/scenarios/a.json",
            """{ "a": { "type": "form_filling", "form": "f", "g": "{{ main_form.x }}" } }""",
        )
        val file = findFile("projA/static/references/scenarios/a.json")
        val ref = fieldReference(file, "g", "{{ main_form.x }}")
        assertEquals("scope ограничен projA", 1, ref.multiResolve(false).size)
    }

    // ---- битый FORM-файл ------------------------------------------------

    fun testBrokenFormFileDoesNotCorruptValidForm() {
        myFixture.addFileToProject("static/references/forms/broken.json", """{ "fields": """)
        // Валидная форма hello_form по-прежнему резолвится.
        val scn = addScenario("s11", """"g": "{{ main_form.name }}"""")
        val ref = fieldReference(scn, "g", "{{ main_form.name }}")
        assertEquals(1, ref.multiResolve(false).size)
    }

    fun testBrokenFormFileDoesNotIndexOwnFields() {
        // Битой считается форма с полноценной структурой fields.phantom_field,
        // но с trailing garbage после `}`. Guard JsonPsi.hasError должен отбросить
        // файл целиком — phantom_field не попадает ни в resolve, ни в completion.
        myFixture.addFileToProject(
            "static/references/forms/bad.json",
            """{ "bad_form": { "type": "form", "fields": { "phantom_field": { "type": "question" } } } } garbage""",
        )
        // Completion по форме bad_form не должен предложить phantom_field.
        val items = completeAt(
            "static/references/scenarios/badscn.json",
            """{ "badscn": { "type": "form_filling", "form": "bad_form", "g": "{{ main_form.<caret> }}" } }""",
        )
        assertFalse("битый form-файл не должен индексировать свои поля", items.contains("phantom_field"))
        // И resolve: ссылка на phantom_field создаётся, но ничего не находит.
        val scn = myFixture.addFileToProject(
            "static/references/scenarios/badscn2.json",
            """{ "badscn2": { "type": "form_filling", "form": "bad_form", "g": "{{ main_form.phantom_field }}" } }""",
        )
        val ref = fieldReference(scn, "g", "{{ main_form.phantom_field }}")
        assertEquals("битый form-файл не даёт resolve своих полей", 0, ref.multiResolve(false).size)
    }

    // ---- Find Usages поля ----------------------------------------------

    fun testFieldFindUsagesAndDescription() {
        // Поиск использований имени поля формы из Jinja.
        myFixture.addFileToProject(
            "static/references/scenarios/usage.json",
            """{ "usage": { "type": "form_filling", "form": "hello_form", "g": "{{ main_form.name }}" } }""",
        )
        val nameFieldProp = findFieldProperty("static/references/forms/forms.json", "name")
        // Подписи элемента.
        assertEquals("field", descriptionProvider.getElementDescription(nameFieldProp, UsageViewTypeLocation.INSTANCE))
        assertEquals(
            "hello_form.name",
            descriptionProvider.getElementDescription(nameFieldProp, UsageViewLongNameLocation.INSTANCE),
        )
        // Интеграционный Find Usages.
        val usages = myFixture.findUsages(nameFieldProp)
        assertTrue(
            "ожидался usage поля 'name' в сценарии usage.json",
            usages.any { it.element?.containingFile?.name == "usage.json" },
        )
    }

    // ---- regression: существующие Jinja-тесты остаются корректны -------

    fun testPlainJinjaValueNoFieldReference() {
        // `a_{{ x }}_b` без main_form — ссылок на поле нет.
        val scn = addScenario("s12", """"g": "a_{{ x }}_b"""")
        val literal = findLiteral(scn, "g", "a_{{ x }}_b")
        assertTrue(literal.references.filterIsInstance<SmartAppFieldReference>().isEmpty())
    }

    // ---- вспомогательные методы -----------------------------------------

    private fun addScenario(name: String, body: String): PsiFile {
        val content = """{ "$name": { "type": "form_filling", "form": "hello_form", $body } }"""
        return myFixture.addFileToProject("static/references/scenarios/$name.json", content)
    }

    private fun findFile(path: String): PsiFile {
        val vFile = myFixture.findFileInTempDir(path)
        return myFixture.psiManager.findFile(vFile)!!
    }

    private fun findLiteral(file: PsiFile, propName: String, value: String): JsonStringLiteral {
        val match = PsiTreeUtil.findChildrenOfType(file, JsonProperty::class.java)
            .firstOrNull { it.name == propName && (it.value as? JsonStringLiteral)?.value == value }
            ?: error("no property '$propName' with value '$value' in ${file.name}")
        return match.value as JsonStringLiteral
    }

    private fun findArrayLiteral(file: PsiFile, value: String): JsonStringLiteral =
        PsiTreeUtil.findChildrenOfType(file, JsonStringLiteral::class.java)
            .firstOrNull { it.value == value && it.parent is JsonArray }
            ?: error("no array literal '$value' in ${file.name}")

    private fun findFieldProperty(path: String, fieldName: String): JsonProperty {
        val file = findFile(path)
        return PsiTreeUtil.findChildrenOfType(file, JsonProperty::class.java)
            .first { it.name == fieldName && it.parent.parent is JsonProperty && (it.parent.parent as JsonProperty).name == "fields" }
    }

    private fun fieldReference(file: PsiFile, propName: String, value: String): SmartAppFieldReference =
        findLiteral(file, propName, value).references.filterIsInstance<SmartAppFieldReference>().firstOrNull()
            ?: error("no SmartAppFieldReference on '$value'")

    private fun completeAt(path: String, contentWithCaret: String): List<String> {
        val caret = contentWithCaret.indexOf("<caret>")
        check(caret >= 0) { "no <caret> marker" }
        val clean = contentWithCaret.replace("<caret>", "")
        val file = myFixture.addFileToProject(path, clean)
        myFixture.openFileInEditor(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(caret)
        // completeBasic может не активировать BASIC-contributors в середине
        // непустого строкового значения; явный BASIC-вызов надёжнее.
        myFixture.complete(com.intellij.codeInsight.completion.CompletionType.BASIC)
        return myFixture.lookupElementStrings ?: emptyList()
    }
}
