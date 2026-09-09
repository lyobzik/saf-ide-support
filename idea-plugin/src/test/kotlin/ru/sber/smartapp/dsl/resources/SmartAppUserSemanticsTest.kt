package ru.sber.smartapp.dsl.resources

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import ru.sber.smartapp.dsl.contract.SmartAppSpecs
import ru.sber.smartapp.dsl.reference.SmartAppUserFieldReference
import ru.sber.smartapp.dsl.reference.SmartAppUserVariableReference

/**
 * Семантика модели пользователя в Jinja: переход, диагностика и автодополнение
 * по `<корень>.<имя>`.
 *
 * Та же таблица входов прогоняется в ядре расширения (`userSemantics.test.ts`).
 * Словарь и корневое имя проверяются отдельно ([SmartAppUserModelTest],
 * [SmartAppUserRootTest]); здесь — то, что видно только в платформе.
 */
class SmartAppUserSemanticsTest : BasePlatformTestCase() {

    private val userPy =
        "from scenarios.user.user_model import User\n\n\n" +
            "class CustomUser(User):\n" +
            "    @property\n" +
            "    def fields(self):\n" +
            "        return super().fields + [Field(\"smart_geo\", Geo)]\n"

    /** Параметризатор, связавший `self._user` с корнем `user`. */
    private val parametrizerPy =
        "from scenarios.user.parametrizer import Parametrizer\n\n\n" +
            "class CustomParametrizer(Parametrizer):\n" +
            "    def _get_user_data(self, tpr=None):\n" +
            "        data = super()._get_user_data(tpr)\n" +
            "        data[\"user\"] = self._user\n" +
            "        return data\n"

    /** Тот же параметризатор без привязки: корень дефолтный, утверждать нельзя. */
    private val unprovenPy = parametrizerPy.replace("        data[\"user\"] = self._user\n", "")

    private var autocompleteSingleVariant = true

    override fun setUp() {
        super.setUp()
        application(parametrizerPy)
        // Единственный вариант платформа вставляет сразу, и проверять было бы
        // нечего: тот же приём, что в раннере корпуса.
        val settings = CodeInsightSettings.getInstance()
        autocompleteSingleVariant = settings.AUTOCOMPLETE_ON_CODE_COMPLETION
        settings.AUTOCOMPLETE_ON_CODE_COMPLETION = false
    }

    override fun tearDown() {
        try {
            CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = autocompleteSingleVariant
        } finally {
            super.tearDown()
        }
    }

    private fun application(parametrizer: String) {
        myFixture.addFileToProject(
            "app_config.py",
            "from app.user.user import CustomUser\n" +
                "from app.user.parametrizer import CustomParametrizer\n\n" +
                "USER = CustomUser\n" +
                "PARAMETRIZER = CustomParametrizer\n",
        )
        myFixture.addFileToProject("app/user/user.py", userPy)
        myFixture.addFileToProject("app/user/parametrizer.py", parametrizer)
    }

    private var behaviors = 0

    /** Каждый вызов кладёт свой файл: одно имя дважды платформа не принимает. */
    private fun behavior(value: String): PsiFile = myFixture.addFileToProject(
        "static/references/behaviors/b${behaviors++}.json",
        """{ "b": { "text": ${'"'}${value.replace("\"", "\\\"")}${'"'} } }""",
    )

    private fun literalOf(file: PsiFile): JsonStringLiteral =
        PsiTreeUtil.findChildrenOfType(file, JsonProperty::class.java)
            .firstNotNullOfOrNull { it.value as? JsonStringLiteral }
            ?: error("нет строкового значения в ${file.name}")

    // ---- переход ---------------------------------------------------------

    fun testAttributeResolvesToDeclaration() {
        val literal = literalOf(behavior("Гео: {{ user.smart_geo }}"))
        val reference = literal.references.filterIsInstance<SmartAppUserFieldReference>().single()
        val target = reference.multiResolve(false).single().element as SmartAppUserFieldElement
        assertEquals("user.py", target.containingFile.name)
        assertEquals("smart_geo", target.attributeName)
        assertEquals(
            "smart_geo",
            target.containingFile.text.substring(target.textRange.startOffset, target.textRange.endOffset),
        )
    }

    fun testRootVariableResolvesToUserClass() {
        val literal = literalOf(behavior("{{ user.smart_geo }}"))
        val reference = literal.references.filterIsInstance<SmartAppUserVariableReference>().single()
        val target = reference.multiResolve(false).single().element as SmartAppUserClassElement
        assertEquals("CustomUser", target.name)
        assertEquals("user.py", target.containingFile.name)
    }

    fun testSnapshotNameHasNoDeclarations() {
        // `variables` приходит из снимка: в коде проекта такой строки нет.
        val literal = literalOf(behavior("{{ user.variables }}"))
        val reference = literal.references.filterIsInstance<SmartAppUserFieldReference>().single()
        assertEmpty(reference.multiResolve(false).toList())
    }

    fun testWorksInEveryKindOfDslFile() {
        // Словарь параметров шаблона один и тот же на любой рендер, поэтому
        // сужать контракт до подмножества видов было бы произволом (план, раздел 0).
        // Перечисление берётся из контракта: новый вид без обновления теста
        // красит его, а не оставляет тихую дыру.
        for (kind in SmartAppSpecs.kinds) {
            val file = myFixture.addFileToProject(
                "static/references/${kind.dirName}/x.json",
                """{ "x": { "text": "{{ user.smart_geo }}" } }""",
            )
            val literal = literalOf(file)
            val reference = literal.references.filterIsInstance<SmartAppUserFieldReference>()
                .singleOrNull() ?: error("нет ссылки в ${kind.dirName}")
            assertEquals(kind.dirName, 1, reference.multiResolve(false).size)
        }
    }

    fun testJsonKeyGetsNoSemantics() {
        val file = myFixture.addFileToProject(
            "static/references/behaviors/key.json",
            """{ "{{ user.smart_geo }}": { "type": "x" } }""",
        )
        val literal = PsiTreeUtil.findChildrenOfType(file, JsonStringLiteral::class.java)
            .first { it.value.contains("smart_geo") }
        assertEmpty(literal.references.filterIsInstance<SmartAppUserFieldReference>())
    }

    fun testForeignRootGetsNoSemantics() {
        val literal = literalOf(behavior("{{ other.smart_geo }}"))
        assertEmpty(literal.references.filterIsInstance<SmartAppUserFieldReference>())
    }

    // ---- диагностика -----------------------------------------------------

    private fun warningsFor(value: String): List<String> {
        val file = behavior(value)
        myFixture.openFileInEditor(file.virtualFile)
        return myFixture.doHighlighting(HighlightSeverity.WARNING).mapNotNull { it.description }
    }

    fun testUnknownAttributeIsWarned() {
        assertEquals(
            listOf("Не удаётся разрешить поле 'nope' модели пользователя"),
            warningsFor("{{ user.nope }}"),
        )
    }

    fun testKnownAttributeIsNotWarned() {
        assertEmpty(warningsFor("{{ user.smart_geo }}"))
        assertEmpty(warningsFor("{{ user.variables }}"))
    }

    fun testWithoutProvenRootNothingIsWarned() {
        // Под именем `user` может лежать что угодно — подчёркивать по нему
        // значит выдумывать ошибку.
        myFixture.addFileToProject("other/app_config.py", "PARAMETRIZER = X\n")
        val file = myFixture.addFileToProject(
            "other/static/references/behaviors/b.json",
            """{ "b": { "text": "{{ user.nope }}" } }""",
        )
        myFixture.openFileInEditor(file.virtualFile)
        assertEmpty(myFixture.doHighlighting(HighlightSeverity.WARNING).mapNotNull { it.description })
    }

    fun testWithBlockerNothingIsWarned() {
        // Словарь тогда заведомо неполон: имя может существовать, просто сканер
        // его не увидел.
        myFixture.addFileToProject(
            "unsafe/app_config.py",
            "from app.user.user import CustomUser\n" +
                "from app.user.parametrizer import CustomParametrizer\n\n" +
                "USER = CustomUser\nPARAMETRIZER = CustomParametrizer\n",
        )
        myFixture.addFileToProject(
            "unsafe/app/user/user.py",
            userPy + "\n    def __getattr__(self, name):\n        return None\n",
        )
        myFixture.addFileToProject("unsafe/app/user/parametrizer.py", parametrizerPy)
        val file = myFixture.addFileToProject(
            "unsafe/static/references/behaviors/b.json",
            """{ "b": { "text": "{{ user.nope }}" } }""",
        )
        myFixture.openFileInEditor(file.virtualFile)
        assertEmpty(myFixture.doHighlighting(HighlightSeverity.WARNING).mapNotNull { it.description })
    }

    fun testWarningRangeCoversOnlyTheName() {
        val file = behavior("{{ user.nope }}")
        myFixture.openFileInEditor(file.virtualFile)
        val warning = myFixture.doHighlighting(HighlightSeverity.WARNING).single()
        assertEquals("nope", file.text.substring(warning.startOffset, warning.endOffset))
    }

    // ---- автодополнение --------------------------------------------------

    private fun completeIn(value: String): List<String> {
        val content = """{ "b": { "text": "$value" } }"""
        val caret = content.indexOf("<caret>")
        val file = myFixture.addFileToProject(
            "static/references/behaviors/complete.json",
            content.replace("<caret>", ""),
        )
        myFixture.openFileInEditor(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(caret)
        myFixture.completeBasic()
        return myFixture.lookupElementStrings ?: emptyList()
    }

    fun testAttributesAreOfferedAfterRoot() {
        val items = completeIn("{{ user.<caret> }}")
        assertTrue("smart_geo" in items)
        assertTrue("variables" in items)
    }

    fun testRootVariableIsOfferedOnIdentifier() {
        assertTrue("user" in completeIn("{{ us<caret> }}"))
    }

    fun testStatementTagBehavesTheSame() {
        assertTrue("smart_geo" in completeIn("{% if user.<caret> %}"))
    }

    fun testCompletionWorksInDumbMode() {
        // Словарь модели читается по VFS, а не из индекса, поэтому атрибуты
        // предлагаются и во время индексации — в отличие от полей формы.
        val content = """{ "b": { "text": "{{ user.<caret> }}" } }"""
        val caret = content.indexOf("<caret>")
        val file = myFixture.addFileToProject(
            "static/references/behaviors/dumb.json",
            content.replace("<caret>", ""),
        )
        myFixture.openFileInEditor(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(caret)
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            myFixture.completeBasic()
            val items = myFixture.lookupElementStrings ?: emptyList()
            assertTrue("smart_geo" in items)
        }
    }

    fun testIdentifierGrammarIsShared() {
        // `µ` (U+00B5) — буква и валидное имя по контракту; `×` (U+00D7) буквой
        // не является. `isJavaIdentifier*` решала иначе: принимала `$` и знаки
        // валют, а расширение теряло `µ` в приближении диапазонами.
        myFixture.addFileToProject(
            "static/references/forms/f.json",
            """{ "hello_form": { "type": "form", "fields": { "µ": {}, "a×b": {} } } }""",
        )
        val content = """{ "s": { "form": "hello_form", "answer": "{{ main_form.<caret> }}" } }"""
        val caret = content.indexOf("<caret>")
        val file = myFixture.addFileToProject(
            "static/references/scenarios/s.json",
            content.replace("<caret>", ""),
        )
        myFixture.openFileInEditor(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(caret)
        myFixture.completeBasic()
        assertEquals(listOf("µ"), myFixture.lookupElementStrings)
    }

    fun testNonIdentifierCharacterClosesTheContext() {
        // `$` идентификатором по контракту не является, и `main_form.name$` —
        // не обращение к полю: лексер видит там имя и текст. Обе прежние
        // грамматики (диапазоны расширения и `isJavaIdentifier*`) принимали `$`.
        myFixture.addFileToProject(
            "static/references/forms/f2.json",
            """{ "hello_form": { "type": "form", "fields": { "name": {} } } }""",
        )
        val content = """{ "s": { "form": "hello_form", "answer": "{{ main_form.$<caret> }}" } }"""
        val caret = content.indexOf("<caret>")
        val file = myFixture.addFileToProject(
            "static/references/scenarios/s2.json",
            content.replace("<caret>", ""),
        )
        myFixture.openFileInEditor(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(caret)
        myFixture.completeBasic()
        // Обращения к полю здесь нет: `$` закрыл контекст поля, и каретка стоит
        // на пустом идентификаторе — предлагается сама переменная. Прежние
        // грамматики считали `$` частью идентификатора, поле-контекст оставался
        // открытым, и не предлагалось вообще ничего.
        // В этом приложении корней два: переменная формы и корень модели.
        assertEquals(listOf("main_form", "user"), myFixture.lookupElementStrings)
    }

    fun testFilterNamePositionOffersNothing() {
        assertEmpty(completeIn("{{ x | user.<caret> }}"))
    }
}
