package ru.sber.smartapp.dsl

import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import ru.sber.smartapp.dsl.index.SmartAppDefinitionIndex
import ru.sber.smartapp.dsl.index.SmartAppNameIndex

/**
 * Edge-case'ы индексов на невалидном/частичном JSON и изоляцию сбоя.
 *
 * Платформенный контракт: JSON PSI при синтаксической ошибке строит частичное
 * дерево с [com.intellij.psi.PsiErrorElement], а не бросает исключение. Поэтому
 * guard `try/catch` в индексах сам по себе не спасает, и обрывочный top-level
 * ключ (например, `a` из `{ "a":`) без guard'а [JsonPsi.hasError] попал бы в
 * индекс. Эти тесты верифицируют именно guard.
 */
class SmartAppIndexTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "static/references/forms/forms.json",
            """{ "hello_form": { "type": "form" }, "second_form": { "type": "base" } }""",
        )
    }

    // ---- J1: битый (оборванный) JSON не даёт обрывочного определения ----

    fun testDefinitionIndexSkipsBrokenJson() {
        myFixture.addFileToProject("static/references/forms/broken.json", """{ "a": """)

        // Обрывочный ключ 'a' не должен появиться ни в списке имён, ни в
        // резолве; валидные формы из setUp остаются доступными.
        assertFormsEqualBaseline()
        assertNoFormDefinition("a")
    }

    // ---- J2: верхний уровень — массив ----------------------------------

    fun testDefinitionIndexSkipsArrayTopLevel() {
        myFixture.addFileToProject("static/references/forms/arr.json", """[1, 2, 3]""")
        // Массив не даёт top-level ключей: полный список имён форм совпадает с
        // baseline из setUp, и по любому имени из добавленного файла ничего не
        // резолвится.
        assertFormsEqualBaseline()
        assertNoFormDefinition("arr_key")
    }

    // ---- J3: верхний уровень — строка ----------------------------------

    fun testDefinitionIndexSkipsStringTopLevel() {
        myFixture.addFileToProject("static/references/forms/str.json", """"hi"""")
        assertFormsEqualBaseline()
        assertNoFormDefinition("hi")
    }

    // ---- J4: пустой файл -----------------------------------------------

    fun testDefinitionIndexSkipsEmptyFile() {
        myFixture.addFileToProject("static/references/forms/empty.json", "")
        assertFormsEqualBaseline()
        assertNoFormDefinition("anything")
    }

    // ---- J5: пустой объект ---------------------------------------------

    fun testDefinitionIndexEmptyJsonObjectProducesNoNames() {
        myFixture.addFileToProject("static/references/forms/blank.json", "{}")
        // Пустой объект не добавляет имён: список форм совпадает с baseline.
        assertFormsEqualBaseline()
    }

    // ---- J6: битый файл не роняет валидный в том же каталоге -----------

    fun testBrokenFileDoesNotCorruptValidFiles() {
        myFixture.addFileToProject("static/references/forms/broken.json", """{ "a": """)

        val defs = SmartAppDefinitionIndex.findDefinitions(
            project, "hello_form", listOf(SmartAppRefKind.FORM), GlobalSearchScope.projectScope(project),
        )
        assertEquals(
            "валидная форма hello_form должна резолвиться, несмотря на битый соседний файл",
            1,
            defs.size,
        )
    }

    // ---- regression: мусор после корректного корневого объекта ----------

    fun testTrailingGarbageAfterObjectIsRejected() {
        // Корневой объект синтаксически валиден, но весь файл — нет: после `}`
        // идёт мусор. JSON-парсер молча игнорирует этот suffix без PsiErrorElement,
        // достраивая корневой объект, поэтому одного PsiErrorElement-guard'а
        // недостаточно — нужна raw-проверка хвоста (JsonPsi.hasTrailingContent).
        myFixture.addFileToProject(
            "static/references/forms/trailing.json",
            """{ "phantom_form": { "type": "form" } } unexpected""",
        )
        assertFormsEqualBaseline()
        assertNoFormDefinition("phantom_form")
    }

    // ---- J11: косвенный round-trip приватного NamesExternalizer --------

    fun testNameIndexRoundTripViaPlatform() {
        // forms.json из setUp содержит два top-level ключа в порядке
        // hello_form, second_form. allNames сохраняет порядок первого вхождения
        // (LinkedHashSet) — это покрывает и save, и read приватного externalizer.
        val names = SmartAppNameIndex.allNames(project, SmartAppRefKind.FORM)
        assertEquals(listOf("hello_form", "second_form"), names)
    }

    // ---- вспомогательные методы ----------------------------------------

    /**
     * Имена форм из фикстуры setUp — baseline, с которым сравниваются edge-case'ы:
     * добавление битого/пустого/не-объектного файла не должно менять этот список.
     */
    private val formsBaseline = listOf("hello_form", "second_form")

    private fun assertFormsEqualBaseline() {
        val names = SmartAppNameIndex.allNames(project, SmartAppRefKind.FORM)
        assertEquals(
            "список имён форм должен совпадать с baseline (битый файл ничего не добавил)",
            formsBaseline,
            names,
        )
    }

    private fun assertNoFormDefinition(name: String) {
        val defs = SmartAppDefinitionIndex.findDefinitions(
            project, name, listOf(SmartAppRefKind.FORM), GlobalSearchScope.projectScope(project),
        )
        assertTrue("'$name' не должен резолвиться в определение", defs.isEmpty())
    }
}
