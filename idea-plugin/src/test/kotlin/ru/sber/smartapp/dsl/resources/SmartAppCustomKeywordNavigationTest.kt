package ru.sber.smartapp.dsl.resources

import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.usageView.UsageInfo
import com.intellij.util.IncorrectOperationException
import com.intellij.util.Processor
import ru.sber.smartapp.dsl.findusages.SmartAppCustomKeywordTargets
import ru.sber.smartapp.dsl.findusages.SmartAppCustomKeywordUsagesHandler
import ru.sber.smartapp.dsl.findusages.SmartAppFindUsagesHandlerFactory
import ru.sber.smartapp.dsl.reference.SmartAppCustomKeywordReference

/**
 * Переход со значения `type` на строку регистрации в Python-коде приложения и
 * поиск использований такого слова.
 *
 * Словарь приложения и разрешение цепочки проверяются отдельно
 * ([SmartAppCustomKeywordsTest], [SmartAppResourceResolverTest]); здесь — то,
 * что видно только в платформе: ссылка, фабрика Find Usages, отказ
 * переименования и работа в dumb mode.
 */
class SmartAppCustomKeywordNavigationTest : BasePlatformTestCase() {

    private val resourcesText =
        "from smart_kit.resources import SmartAppResources\n" +
            "\n" +
            "class CustomAppResources(SmartAppResources):\n" +
            "    def init_actions(self):\n" +
            "        actions[\"custom_action\"] = CustomAction\n" +
            "        actions[\"shared\"] = SharedAction\n" +
            "\n" +
            "    def init_requirements(self):\n" +
            "        requirements[\"shared\"] = SharedRequirement\n"

    override fun setUp() {
        super.setUp()
        application("")
        myFixture.addFileToProject(
            "static/references/actions/actions.json",
            """{ "a": { "type": "custom_action" }, "b": { "type": "shared" } }""",
        )
        myFixture.addFileToProject(
            "static/references/scenarios/main.json",
            """{ "s": { "actions": [ { "type": "custom_action" } ], "requirement": { "type": "shared" } } }""",
        )
    }

    private fun application(root: String, action: String = "custom_action") {
        val prefix = if (root.isEmpty()) "" else "$root/"
        myFixture.addFileToProject(
            "${prefix}app_config.py",
            "from app.resources.custom_app_resources import CustomAppResources\n" +
                "RESOURCES = CustomAppResources\n",
        )
        val text = if (root.isEmpty()) {
            resourcesText
        } else {
            "from smart_kit.resources import SmartAppResources\n\n" +
                "class CustomAppResources(SmartAppResources):\n" +
                "    def init_actions(self):\n" +
                "        actions[\"$action\"] = C\n"
        }
        myFixture.addFileToProject("${prefix}app/resources/custom_app_resources.py", text)
    }

    // ---- переход к регистрации ------------------------------------------

    fun testTypeValueResolvesToRegistration() {
        val target = singleTarget("static/references/actions/actions.json", "custom_action")
        assertEquals("custom_app_resources.py", target.containingFile.name)
        assertEquals("action", target.keyword.category)
        // Диапазон — имя без кавычек, каретка перехода стоит в его начале.
        assertEquals("custom_action", textOf(target))
        assertEquals(target.textRange.startOffset, target.textOffset)
    }

    fun testCategoryPicksRegistrationAmongSameNamed() {
        // Файл действий: категория позиции — action.
        assertEquals(
            "SharedAction",
            singleTarget("static/references/actions/actions.json", "shared").keyword.className,
        )
        // Тот же `shared` внутри `requirement` сценария — уже другое слово.
        assertEquals(
            "SharedRequirement",
            singleTarget("static/references/scenarios/main.json", "shared").keyword.className,
        )
    }

    fun testFrameworkKeywordResolvesToNothing() {
        val file = myFixture.addFileToProject(
            "static/references/actions/framework.json",
            """{ "f": { "type": "sdk_answer" } }""",
        )
        val literal = typeLiteral(file, "sdk_answer")
        val reference = literal.references.filterIsInstance<SmartAppCustomKeywordReference>().single()
        assertEmpty(reference.multiResolve(false).toList())
    }

    fun testEscapedNameNavigatesToRawRange() {
        // Имя сравнивается со значением JSON в декодированном виде, а диапазон
        // остаётся сырым: каретка обязана встать на литерал в Python, а не на
        // придуманную позицию декодированной строки.
        myFixture.addFileToProject(
            "escaped/app_config.py",
            "from app.resources.custom_app_resources import CustomAppResources\n" +
                "RESOURCES = CustomAppResources\n",
        )
        myFixture.addFileToProject(
            "escaped/app/resources/custom_app_resources.py",
            "from smart_kit.resources import SmartAppResources\n\n" +
                "class CustomAppResources(SmartAppResources):\n" +
                "    def init_actions(self):\n" +
                "        actions[\"custom\\u0041\"] = C\n",
        )
        myFixture.addFileToProject(
            "escaped/static/references/actions/actions.json",
            """{ "e": { "type": "customA" } }""",
        )

        val target = singleTarget("escaped/static/references/actions/actions.json", "customA")
        assertEquals("customA", target.name)
        assertEquals("custom\\u0041", textOf(target))
        assertEquals("custom\\u0041", target.text)
    }

    fun testResolveWorksInDumbMode() {
        // Словарь приложения читается по VFS, а не из индекса, поэтому переход
        // к регистрации доступен и во время индексации — в отличие от ссылок
        // на сущности DSL.
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            val target = singleTarget("static/references/actions/actions.json", "custom_action")
            assertEquals("custom_app_resources.py", target.containingFile.name)
        }
    }

    // ---- поиск использований --------------------------------------------

    fun testFindUsagesFromJsonValue() {
        val literal = typeLiteral(fileAt("static/references/actions/actions.json"), "custom_action")
        val usages = myFixture.findUsages(literal)
        assertEquals(
            listOf("actions.json" to "custom_action", "main.json" to "custom_action"),
            describe(usages),
        )
    }

    fun testCaretOnTypeValueTargetsRegistration() {
        // Полный путь Alt+F7 и Ctrl+Click: платформа сама выбирает элемент под
        // кареткой (TargetElementUtil резолвит ссылку), и только потом зовёт
        // фабрику. Прямая передача литерала этот шаг пропускает.
        val path = "static/references/actions/actions.json"
        myFixture.configureFromTempProjectFile(path)
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("custom_action"))

        val element = myFixture.elementAtCaret
        assertTrue(
            "под кареткой ожидалась регистрация, получено: ${element.javaClass.name}",
            element is SmartAppRegistrationElement,
        )
        assertEquals(
            listOf("actions.json" to "custom_action", "main.json" to "custom_action"),
            describe(myFixture.findUsages(element)),
        )
    }

    fun testFindUsagesFromRegistrationElement() {
        val target = singleTarget("static/references/actions/actions.json", "custom_action")
        val factory = FindUsagesHandlerFactory.EP_NAME.getExtensions(project)
            .filterIsInstance<SmartAppFindUsagesHandlerFactory>()
            .single()
        assertTrue(factory.canFindUsages(target))
        assertEquals(
            listOf("actions.json" to "custom_action", "main.json" to "custom_action"),
            describe(myFixture.findUsages(target)),
        )
    }

    fun testLocalSearchScopeLimitsUsagesToItsFile() {
        // «Найти в текущем файле» — это LocalSearchScope; область обязана сужать
        // поиск, а не игнорироваться.
        val target = singleTarget("static/references/actions/actions.json", "custom_action")
        val scope = LocalSearchScope(fileAt("static/references/scenarios/main.json"))
        assertEquals(listOf("main.json" to "custom_action"), usagesInScope(target, scope))

        // Область сужает и сам перебор файлов, а не только отбор вхождений:
        // иначе «в текущем файле» читало бы весь каталог приложения.
        val handler = handlerFor(target)
        val candidates = handler.candidateScope(handler.findUsagesOptions.also { it.searchScope = scope })
        assertTrue(candidates.contains(myFixture.findFileInTempDir("static/references/scenarios/main.json")))
        assertFalse(candidates.contains(myFixture.findFileInTempDir("static/references/actions/actions.json")))
    }

    fun testLocalSearchScopeLimitsUsagesToItsElement() {
        // Локальная область может покрывать часть файла: два вхождения в одном
        // файле, в области — только одно.
        val file = myFixture.addFileToProject(
            "static/references/actions/pair.json",
            """{ "first": { "type": "custom_action" }, "second": { "type": "custom_action" } }""",
        )
        val first = PsiTreeUtil.findChildrenOfType(file, JsonProperty::class.java)
            .first { it.name == "first" }

        val target = singleTarget("static/references/actions/actions.json", "custom_action")
        assertEquals(
            listOf("pair.json" to "custom_action"),
            usagesInScope(target, LocalSearchScope(first)),
        )
    }

    fun testUsagesOfSameNameInOtherCategoryAreNotMixed() {
        val literal = typeLiteral(fileAt("static/references/actions/actions.json"), "shared")
        assertEquals(listOf("actions.json" to "shared"), describe(myFixture.findUsages(literal)))
    }

    fun testNestedApplicationAndDependencyAreExcluded() {
        // Вложенное приложение: своё слово с тем же именем, свой корень.
        application("subapp", "custom_action")
        myFixture.addFileToProject(
            "subapp/static/references/actions/actions.json",
            """{ "n": { "type": "custom_action" } }""",
        )
        // Зависимость, вендоренная внутрь самого набора: приложение то же,
        // отсекает её только список исключённых каталогов.
        myFixture.addFileToProject(
            "static/references/actions/venv/vendored.json",
            """{ "v": { "type": "custom_action" } }""",
        )
        // Обычный JSON рядом с кодом: в каталог приложения он входит, DSL-файлом
        // не является.
        myFixture.addFileToProject("config.json", """{ "type": "custom_action" }""")
        // JSON внутри самого набора, но не в каталоге вида: набор тот же,
        // DSL-файлом он всё равно не является.
        myFixture.addFileToProject(
            "static/references/notes.json",
            """{ "n": { "type": "custom_action" } }""",
        )

        val literal = typeLiteral(fileAt("static/references/actions/actions.json"), "custom_action")
        val files = describe(myFixture.findUsages(literal)).map { it.first }
        assertEquals(listOf("actions.json", "main.json"), files)
    }

    fun testDeclarationsAreRegistrationsNotJsonProperty() {
        // Это данные, из которых корпус соберёт `includeDeclaration`: объявление
        // кастомного слова — строка в Python, а не свойство JSON под кареткой.
        // Ядро расширения при includeDeclaration отдаёт ровно их же.
        val literal = typeLiteral(fileAt("static/references/actions/actions.json"), "custom_action")
        val target = SmartAppCustomKeywordTargets.of(literal)!!
        assertEquals(
            listOf("custom_app_resources.py"),
            target.declarations.map { it.containingFile.name },
        )
        assertEquals(listOf("custom_action"), target.declarations.map { textOf(it) })
    }

    // ---- переименование --------------------------------------------------

    fun testRenameOfRegistrationIsRefused() {
        val target = singleTarget("static/references/actions/actions.json", "custom_action")
        val message = try {
            target.setName("renamed")
            fail("переименование регистрации обязано быть отклонено")
            return
        } catch (failure: IncorrectOperationException) {
            failure.message.orEmpty()
        }
        assertTrue(
            "ожидалось объяснение отказа, получено: '$message'",
            message.contains("переименование не поддерживается"),
        )
    }

    // ---- вспомогательные методы -----------------------------------------

    private fun fileAt(path: String): PsiFile =
        myFixture.psiManager.findFile(myFixture.findFileInTempDir(path))!!

    private fun typeLiteral(file: PsiFile, value: String): JsonStringLiteral =
        PsiTreeUtil.findChildrenOfType(file, JsonProperty::class.java)
            .firstNotNullOfOrNull { property ->
                (property.value as? JsonStringLiteral)?.takeIf {
                    property.name == "type" && it.value == value
                }
            }
            ?: error("нет значения type '$value' в ${file.name}")

    /**
     * Цель перехода — тем же путём, каким её получает Ctrl+Click: ссылка с
     * литерала, навешенная контрибьютором, и её резолв. Прямой вызов резолвера
     * пропустил бы обрыв в самом контрибьюторе.
     */
    private fun singleTarget(path: String, value: String): SmartAppRegistrationElement {
        val literal = typeLiteral(fileAt(path), value)
        val reference = literal.references.filterIsInstance<SmartAppCustomKeywordReference>()
            .singleOrNull()
            ?: error("на значении '$value' нет ссылки на регистрацию")
        val targets = reference.multiResolve(false).mapNotNull { it.element as? SmartAppRegistrationElement }
        assertEquals("ожидалась одна цель для '$value'", 1, targets.size)
        return targets.single()
    }

    private fun textOf(target: SmartAppRegistrationElement): String =
        target.containingFile.text.substring(target.textRange.startOffset, target.textRange.endOffset)

    /**
     * Вхождения, найденные handler'ом в заданной области. `myFixture.findUsages`
     * область не принимает, а проверять нужно именно её влияние.
     */
    private fun handlerFor(target: SmartAppRegistrationElement): SmartAppCustomKeywordUsagesHandler =
        SmartAppFindUsagesHandlerFactory().createFindUsagesHandler(target, false)
            as SmartAppCustomKeywordUsagesHandler

    private fun usagesInScope(
        target: SmartAppRegistrationElement,
        scope: SearchScope,
    ): List<Pair<String, String>> {
        val handler = handlerFor(target)
        // Опции берутся у самого handler'а: у пустых FindUsagesOptions isUsages
        // выключен, и поиск честно вернул бы ничего.
        val options = handler.findUsagesOptions.also { it.searchScope = scope }
        val found = ArrayList<UsageInfo>()
        handler.processElementUsages(target, Processor { found.add(it); true }, options)
        return describe(found)
    }

    /** Вхождения как пары «файл — текст диапазона», в стабильном порядке. */
    private fun describe(usages: Collection<UsageInfo>): List<Pair<String, String>> =
        usages.mapNotNull { usage ->
            val element: PsiElement = usage.element ?: return@mapNotNull null
            val range = usage.rangeInElement ?: return@mapNotNull null
            element.containingFile.name to element.text.substring(range.startOffset, range.endOffset)
        }.sortedWith(compareBy({ it.first }, { it.second }))
}
