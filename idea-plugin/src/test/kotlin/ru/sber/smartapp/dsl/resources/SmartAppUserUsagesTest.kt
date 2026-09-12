package ru.sber.smartapp.dsl.resources

import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.usageView.UsageInfo
import com.intellij.util.IncorrectOperationException
import com.intellij.util.Processor
import ru.sber.smartapp.dsl.findusages.SmartAppFindUsagesHandlerFactory
import ru.sber.smartapp.dsl.findusages.SmartAppUserFieldUsagesHandler
import ru.sber.smartapp.dsl.reference.SmartAppUserFieldReference
import ru.sber.smartapp.dsl.reference.SmartAppUserVariableReference
import java.util.concurrent.TimeUnit

/**
 * Поиск использований атрибута модели пользователя: состав вхождений, их
 * диапазон, границы приложения и отказ для класса.
 *
 * Та же таблица входов прогоняется в ядре расширения (`userSemantics.test.ts`).
 */
class SmartAppUserUsagesTest : BasePlatformTestCase() {

    private val userPy =
        "from scenarios.user.user_model import User\n\n\n" +
            "class CustomUser(User):\n" +
            "    @property\n" +
            "    def fields(self):\n" +
            "        return super().fields + [Field(\"smart_geo\", Geo)]\n" +
            "\n" +
            "    def __init__(self):\n" +
            "        super().__init__()\n" +
            "        self.smart_geo = None\n"

    private val parametrizerPy =
        "from scenarios.user.parametrizer import Parametrizer\n\n\n" +
            "class CustomParametrizer(Parametrizer):\n" +
            "    def _get_user_data(self, tpr=None):\n" +
            "        data = super()._get_user_data(tpr)\n" +
            "        data[\"user\"] = self._user\n" +
            "        data[\"me\"] = self._user\n" +
            "        return data\n"

    override fun setUp() {
        super.setUp()
        application("")
    }

    private fun application(root: String) {
        val prefix = if (root.isEmpty()) "" else "$root/"
        myFixture.addFileToProject(
            "${prefix}app_config.py",
            "from app.user.user import CustomUser\n" +
                "from app.user.parametrizer import CustomParametrizer\n\n" +
                "USER = CustomUser\nPARAMETRIZER = CustomParametrizer\n",
        )
        myFixture.addFileToProject("${prefix}app/user/user.py", userPy)
        myFixture.addFileToProject("${prefix}app/user/parametrizer.py", parametrizerPy)
    }

    private fun dsl(path: String, value: String): PsiFile = myFixture.addFileToProject(
        path,
        """{ "x": { "text": "$value" } }""",
    )

    /** Цель — тем же путём, каким её получает Alt+F7: ссылка в позиции каретки. */
    private fun targetIn(file: PsiFile, name: String): SmartAppUserFieldElement {
        val literal = PsiTreeUtil.findChildrenOfType(file, JsonProperty::class.java)
            .firstNotNullOfOrNull { it.value as? JsonStringLiteral }
            ?: error("нет строкового значения")
        val reference = literal.references.filterIsInstance<SmartAppUserFieldReference>()
            .first { it.rangeInElement.substring(literal.text) == name }
        return reference.multiResolve(false).first().element as SmartAppUserFieldElement
    }

    private fun handlerFor(target: SmartAppUserFieldElement): SmartAppUserFieldUsagesHandler =
        SmartAppFindUsagesHandlerFactory().createFindUsagesHandler(target, false)
            as SmartAppUserFieldUsagesHandler

    private fun usages(
        target: SmartAppUserFieldElement,
        scope: SearchScope? = null,
    ): List<UsageInfo> {
        val handler = handlerFor(target)
        val options = FindUsagesOptions(project).apply {
            isUsages = true
            if (scope != null) searchScope = scope
        }
        val found = ArrayList<UsageInfo>()
        handler.processElementUsages(target, Processor { found.add(it); true }, options)
        return found
    }

    private fun fileNames(found: List<UsageInfo>): List<String> =
        found.mapNotNull { it.file?.name }.sorted()

    /**
     * Поиск так, как его запускает платформа: пуловый поток **без** read action
     * (`FindUsagesManager.createUsageSearcher`). Остальные тесты зовут
     * обработчик прямо из теста, то есть на EDT и под чтением, и потому не
     * видят, берёт ли он read action сам.
     */
    private fun usagesFromPooledThread(
        handler: FindUsagesHandler,
        target: PsiElement,
        options: FindUsagesOptions,
    ): List<UsageInfo> {
        val found = ArrayList<UsageInfo>()
        val processor = Processor<UsageInfo> { found.add(it); true }
        ApplicationManager.getApplication()
            .executeOnPooledThread<Boolean> { handler.processElementUsages(target, processor, options) }
            .get(1, TimeUnit.MINUTES)
        return found
    }

    // ---- состав вхождений ------------------------------------------------

    fun testUsagesAcrossApplicationFiles() {
        val first = dsl("static/references/behaviors/b.json", "{{ user.smart_geo }}")
        dsl("static/references/scenarios/s.json", "{{ user.smart_geo }}")
        assertEquals(listOf("b.json", "s.json"), fileNames(usages(targetIn(first, "smart_geo"))))
    }

    fun testSearchTakesItsOwnReadAction() {
        // В живой IDE обработчик падал здесь на первом же обращении к
        // `FileTypeIndex`: read access есть только внутри read action, а
        // платформа его не даёт. Поиск умирал вместе с потоком, и Alt+F7 не
        // показывал вообще ничего.
        val first = dsl("static/references/behaviors/b.json", "{{ user.smart_geo }}")
        val target = targetIn(first, "smart_geo")
        // Второй файл добавляется ПОСЛЕ разрешения цели: он сбрасывает кэш
        // корневого имени (зависимость — `PsiModificationTracker`), и поиск
        // считает его заново уже на пуловом потоке — как в живой IDE. Ассерта
        // на этом пути сегодня нет, так что зелёный тест read action вокруг
        // `SmartAppUserRoot` не доказывает; он держит обход индекса и PSI.
        dsl("static/references/scenarios/s.json", "{{ user.smart_geo }}")
        val options = FindUsagesOptions(project).apply { isUsages = true }

        val found = usagesFromPooledThread(handlerFor(target), target, options)

        assertEquals(listOf("b.json", "s.json"), fileNames(found))
    }

    fun testUsageRangeCoversOnlyTheName() {
        val file = dsl("static/references/behaviors/b.json", "Гео: {{ user.smart_geo }}")
        val usage = usages(targetIn(file, "smart_geo")).single()
        assertEquals("smart_geo", usage.element?.text?.substring(usage.rangeInElement!!.startOffset, usage.rangeInElement!!.endOffset))
    }

    fun testSecondRootNameIsAlsoAUsage() {
        // `user` и `me` связаны с одним `self._user`: оба — действующие корни.
        val file = dsl("static/references/behaviors/b.json", "{{ user.smart_geo }}")
        dsl("static/references/scenarios/s.json", "{{ me.smart_geo }}")
        assertEquals(listOf("b.json", "s.json"), fileNames(usages(targetIn(file, "smart_geo"))))
    }

    fun testForeignRootIsNotAUsage() {
        val file = dsl("static/references/behaviors/b.json", "{{ user.smart_geo }}")
        dsl("static/references/scenarios/s.json", "{{ other.smart_geo }}")
        assertEquals(listOf("b.json"), fileNames(usages(targetIn(file, "smart_geo"))))
    }

    fun testNestedApplicationIsForeign() {
        val file = dsl("static/references/behaviors/b.json", "{{ user.smart_geo }}")
        application("subapp")
        dsl("subapp/static/references/behaviors/n.json", "{{ user.smart_geo }}")
        assertEquals(listOf("b.json"), fileNames(usages(targetIn(file, "smart_geo"))))
    }

    fun testVendoredDependencyInsideSetIsSkipped() {
        val file = dsl("static/references/behaviors/b.json", "{{ user.smart_geo }}")
        dsl("static/references/behaviors/venv/vendored.json", "{{ user.smart_geo }}")
        assertEquals(listOf("b.json"), fileNames(usages(targetIn(file, "smart_geo"))))
    }

    fun testSetInsideNeighbourVenvIsForeign() {
        val file = dsl("static/references/behaviors/b.json", "{{ user.smart_geo }}")
        dsl("venv/lib/pkg/static/references/behaviors/o.json", "{{ user.smart_geo }}")
        assertEquals(listOf("b.json"), fileNames(usages(targetIn(file, "smart_geo"))))
    }

    fun testLocalScopeNarrowsTheSearch() {
        val first = dsl("static/references/behaviors/b.json", "{{ user.smart_geo }}")
        dsl("static/references/scenarios/s.json", "{{ user.smart_geo }}")
        val found = usages(targetIn(first, "smart_geo"), LocalSearchScope(first))
        assertEquals(listOf("b.json"), fileNames(found))
    }

    fun testLocalScopeNarrowsInsideOneFile() {
        // Область может покрывать часть файла, поэтому мало отобрать файлы:
        // каждое вхождение проверяется отдельно.
        val file = myFixture.addFileToProject(
            "static/references/behaviors/two.json",
            """{ "a": { "text": "{{ user.smart_geo }}" }, "b": { "text": "{{ user.smart_geo }}" } }""",
        )
        val properties = PsiTreeUtil.findChildrenOfType(file, JsonProperty::class.java)
            .filter { it.name == "text" }
        assertEquals(2, properties.size)
        assertEquals(2, usages(targetIn(file, "smart_geo")).size)
        assertEquals(1, usages(targetIn(file, "smart_geo"), LocalSearchScope(properties.first())).size)
    }

    // ---- объявления и класс ----------------------------------------------

    fun testAllDeclarationsArePrimaryElements() {
        // Имя объявлено дважды — списком `fields` и `self.smart_geo` в `__init__`;
        // обе строки платформа показывает при «Include declaration».
        val file = dsl("static/references/behaviors/b.json", "{{ user.smart_geo }}")
        val primary = handlerFor(targetIn(file, "smart_geo")).primaryElements
        assertEquals(2, primary.size)
        assertEquals(setOf("smart_geo"), primary.map { (it as SmartAppUserFieldElement).name }.toSet())
    }

    fun testSearchRunsOnceForAllPrimaryElements() {
        // Платформа обходит primary-элементы по одному; без защиты каждое
        // вхождение пришло бы столько раз, сколько у имени строк объявления.
        val file = dsl("static/references/behaviors/b.json", "{{ user.smart_geo }}")
        val target = targetIn(file, "smart_geo")
        val handler = handlerFor(target)
        val options = FindUsagesOptions(project).apply { isUsages = true }
        val found = ArrayList<UsageInfo>()
        assertEquals(2, handler.primaryElements.size)
        for (primary in handler.primaryElements) {
            handler.processElementUsages(primary, Processor { found.add(it); true }, options)
        }
        assertEquals(1, found.size)
    }

    fun testRenameOfDeclarationIsRejected() {
        // Имя живёт в Python-коде и в Jinja-выражениях всех файлов приложения:
        // переписать их согласованно мы не умеем, поэтому F2 обязан отказать, а
        // не переписать Python.
        val file = dsl("static/references/behaviors/b.json", "{{ user.smart_geo }}")
        assertThrows(IncorrectOperationException::class.java) {
            targetIn(file, "smart_geo").setName("renamed")
        }
    }

    fun testUserClassHasNoFindUsages() {
        // Имени класса в тексте DSL нет — там псевдоним из параметризатора,
        // поэтому вхождения `user` вхождениями `CustomUser` не являются.
        val file = dsl("static/references/behaviors/b.json", "{{ user.smart_geo }}")
        val literal = PsiTreeUtil.findChildrenOfType(file, JsonProperty::class.java)
            .firstNotNullOfOrNull { it.value as? JsonStringLiteral }!!
        val cls = literal.references.filterIsInstance<SmartAppUserVariableReference>()
            .single().multiResolve(false).single().element as SmartAppUserClassElement
        val factory = SmartAppFindUsagesHandlerFactory()
        assertFalse(factory.canFindUsages(cls))
        assertNull(factory.createFindUsagesHandler(cls, false) as FindUsagesHandler?)
        // Переименование класса тоже отказывает: корневое имя в тексте — это
        // псевдоним из параметризатора, а не имя класса.
        assertThrows(IncorrectOperationException::class.java) { cls.setName("Renamed") }
    }
}
