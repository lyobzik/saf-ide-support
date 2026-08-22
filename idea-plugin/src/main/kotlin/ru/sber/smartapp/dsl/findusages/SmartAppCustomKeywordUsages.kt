package ru.sber.smartapp.dsl.findusages

import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.json.JsonFileType
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopeUtil
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.PsiSearchScopeUtil
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor
import ru.sber.smartapp.dsl.SmartAppFiles
import ru.sber.smartapp.dsl.SmartAppRefKind
import ru.sber.smartapp.dsl.SmartAppScopes
import ru.sber.smartapp.dsl.SmartAppTypeContext
import ru.sber.smartapp.dsl.contract.ResourceScanSpec
import ru.sber.smartapp.dsl.contract.TypeContextSpec
import ru.sber.smartapp.dsl.reference.JsonStringLiteralDecoder
import ru.sber.smartapp.dsl.reference.SmartAppCustomKeywordReference
import ru.sber.smartapp.dsl.resources.SmartAppCustomKeywords
import ru.sber.smartapp.dsl.resources.SmartAppRegistrationElement

/**
 * Ключевое слово приложения как цель поиска использований: имя, категории, в
 * которых его ищут, и приложение, которому оно принадлежит.
 *
 * Категорий может быть несколько: в позиции с нераспознанной категорией ссылка
 * поли-вариантна и ведёт во все одноимённые регистрации — использованиями тогда
 * считаются вхождения любой из них.
 */
data class SmartAppCustomKeywordTarget(
    val name: String,
    val categories: Set<String>,
    val appRoot: VirtualFile,
    /**
     * Действующие регистрации — **все**, а не первая. При `includeDeclaration`
     * объявлением считается каждая из них: это те же строки, что отдаёт ядро
     * расширения, и корпус обязан видеть одинаковый список с обеих сторон.
     *
     * Сегодня их всегда не больше одной: резолвер оставляет по паре
     * «категория + имя» одну действующую, а у позиции в DSL-файле категория
     * определена всегда. Список нужен там, где категория не определена, —
     * молча брать первую в этом случае значило бы объявить не то слово.
     */
    val declarations: List<SmartAppRegistrationElement>,
) {
    init {
        require(declarations.isNotEmpty()) { "target without registrations" }
    }

    /** Элемент, который платформа показывает как цель поиска. */
    val declaration: SmartAppRegistrationElement get() = declarations.first()
}

/**
 * Определяет цель поиска по элементу под кареткой. Разбираются все случаи,
 * перечисленные в плане: сам fake-элемент регистрации, значение `type` в JSON
 * (Alt+F7 прямо на слове) и элемент Python-PSI, попавший в диапазон регистрации.
 */
object SmartAppCustomKeywordTargets {

    fun of(element: PsiElement): SmartAppCustomKeywordTarget? = when (element) {
        is SmartAppRegistrationElement -> ofRegistration(element)
        is JsonStringLiteral -> ofRegistrations(SmartAppCustomKeywordReference.registrationsAt(element))
        is JsonProperty -> ofProperty(element)
        else -> ofPythonElement(element)
    }

    private fun ofRegistration(element: SmartAppRegistrationElement): SmartAppCustomKeywordTarget? {
        val virtualFile = element.containingFile?.virtualFile ?: return null
        val appRoot = SmartAppCustomKeywords.ownerApplicationRoot(virtualFile) ?: return null
        return SmartAppCustomKeywordTarget(
            name = element.keyword.name,
            categories = setOf(element.keyword.category),
            appRoot = appRoot,
            declarations = listOf(element),
        )
    }

    /**
     * Каретка на свойстве `type` целиком: так элемент приходит из корпуса и из
     * тестов, которые берут `JsonProperty` под кареткой.
     */
    private fun ofProperty(property: JsonProperty): SmartAppCustomKeywordTarget? {
        if (property.name != TypeContextSpec.typeProperty) return null
        val literal = property.value as? JsonStringLiteral ?: return null
        return ofRegistrations(SmartAppCustomKeywordReference.registrationsAt(literal))
    }

    private fun ofRegistrations(
        registrations: List<SmartAppRegistrationElement>,
    ): SmartAppCustomKeywordTarget? {
        val declaration = registrations.firstOrNull() ?: return null
        val virtualFile = declaration.containingFile?.virtualFile ?: return null
        val appRoot = SmartAppCustomKeywords.ownerApplicationRoot(virtualFile) ?: return null
        return SmartAppCustomKeywordTarget(
            name = declaration.keyword.name,
            categories = registrations.map { it.keyword.category }.toSet(),
            appRoot = appRoot,
            declarations = registrations,
        )
    }

    /**
     * Каретка внутри Python-файла. Работает только там, где у `.py` есть
     * настоящее PSI (установлен плагин Python): без него под кареткой лежит
     * файл целиком, и различить регистрацию нечем — платформа в этом случае до
     * фабрики и не доходит.
     *
     * Неоднозначность — тоже отказ: если диапазон элемента накрывает несколько
     * регистраций, выбрать одну наугад значило бы искать вхождения не того
     * слова.
     */
    private fun ofPythonElement(element: PsiElement): SmartAppCustomKeywordTarget? {
        if (element is PsiFile) return null
        val file = element.containingFile ?: return null
        val virtualFile = file.virtualFile ?: return null
        if (!virtualFile.name.endsWith(ResourceScanSpec.fileExtension)) return null

        val appRoot = SmartAppCustomKeywords.ownerApplicationRoot(virtualFile) ?: return null
        val relativePath = VfsUtilCore.getRelativePath(virtualFile, appRoot) ?: return null
        val range = element.textRange ?: return null

        val matching = SmartAppCustomKeywords.registrationsOfRoot(element.project, appRoot)
            .filter { it.keyword.file == relativePath }
            .filter { it.keyword.nameStart < range.endOffset && range.startOffset < it.keyword.nameEnd }
        val single = matching.singleOrNull() ?: return null
        return ofRegistration(single)
    }
}

/**
 * Ищет вхождения ключевого слова приложения в JSON-файлах этого же приложения.
 *
 * Обратного индекса использований в плагине нет, и заводить пятый
 * `FileBasedIndex` ради явного действия пользователя не будем: обход ограничен
 * каталогом приложения и прерывается по `checkCanceled`. Позицию распознаёт тот
 * же [SmartAppTypeContext], что и подсветка, — источник правды один.
 */
class SmartAppCustomKeywordUsagesHandler(
    private val target: SmartAppCustomKeywordTarget,
) : FindUsagesHandler(target.declaration) {

    override fun processElementUsages(
        element: PsiElement,
        processor: Processor<in UsageInfo>,
        options: FindUsagesOptions,
    ): Boolean {
        if (!options.isUsages) return true
        val project = target.declaration.project
        // Обход опирается на FileTypeIndex; в dumb mode он недоступен, а само
        // действие платформа в это время и не предлагает.
        if (DumbService.isDumb(project)) return true

        // Локальная область («в текущем файле», подсветка вхождений) ограничивает
        // не файлы, а элементы, поэтому она проверяется дважды: файлы — через
        // приведение к глобальной, конкретное вхождение — по диапазону.
        val local = options.searchScope as? LocalSearchScope
        val manager = PsiManager.getInstance(project)
        for (virtualFile in FileTypeIndex.getFiles(JsonFileType.INSTANCE, candidateScope(options))) {
            ProgressManager.checkCanceled()
            // Вид файла определяется до всякого разбора PSI: каталог приложения
            // рекурсивен, и обычный `config.json` рядом с кодом в него тоже
            // попадает. Он же нужен для категории позиции — второй проверки
            // ниже нет, иначе мутация одной из них осталась бы незамеченной.
            val fileKind = SmartAppFiles.kindOf(virtualFile) ?: continue
            if (!isOwnFile(virtualFile)) continue
            val psiFile = manager.findFile(virtualFile) ?: continue
            if (!processFile(psiFile, fileKind, local, processor)) return false
        }
        return true
    }

    /**
     * Файл принадлежит именно этому приложению и не лежит в зависимости: в
     * каталог приложения входят и набор вложенного приложения, и вендоренная
     * внутрь набора библиотека.
     */
    private fun isOwnFile(virtualFile: VirtualFile): Boolean {
        // Владение проверяется первым: только после него известно, что путь
        // действительно проходит через корень приложения, и обход сегментов
        // заведомо на нём остановится.
        if (SmartAppCustomKeywords.applicationRoot(virtualFile) != target.appRoot) return false
        return !SmartAppCustomKeywords.hasExcludedSegment(virtualFile, target.appRoot)
    }

    /**
     * Обход одного файла. Дерево обходится посетителем, а не собирается в
     * список: размер файла ничем не ограничен, а `checkCanceled` на каждом узле
     * позволяет прервать поиск в любой момент.
     */
    private fun processFile(
        psiFile: PsiFile,
        fileKind: SmartAppRefKind,
        local: LocalSearchScope?,
        processor: Processor<in UsageInfo>,
    ): Boolean {
        var proceed = true
        psiFile.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                ProgressManager.checkCanceled()
                if (element is JsonProperty && !processProperty(element, fileKind, local, processor)) {
                    proceed = false
                    stopWalking()
                    return
                }
                super.visitElement(element)
            }
        })
        return proceed
    }

    /** `false` — потребитель попросил остановиться. */
    private fun processProperty(
        property: JsonProperty,
        fileKind: SmartAppRefKind,
        local: LocalSearchScope?,
        processor: Processor<in UsageInfo>,
    ): Boolean {
        if (property.name != TypeContextSpec.typeProperty) return true
        val literal = property.value as? JsonStringLiteral ?: return true
        if (literal.value != target.name) return true
        // Локальная область может покрывать часть файла — проверяем сам элемент.
        if (local != null && !PsiSearchScopeUtil.isInScope(local, literal)) return true

        // Категория позиции сужает поиск: одноимённое слово другой категории —
        // другое слово. Нераспознанная категория совпадает с любой целью, как и
        // при резолве.
        val category = SmartAppTypeContext.categoryFor(property, fileKind)
        if (category != null && category !in target.categories) return true

        val raw = JsonStringLiteralDecoder.rawText(literal) ?: return true
        // Диапазон — имя без кавычек: тот же контракт, что у всех ссылок.
        return processor.process(UsageInfo(literal, 1, 1 + raw.length, false))
    }

    /**
     * Файлы-кандидаты: каталог приложения, суженный запрошенной областью.
     * Запрошенная область обязана сужать и перебор — иначе поиск «в текущем
     * файле» читал бы всё приложение и только потом выбрасывал лишнее.
     * Локальная область (набор элементов) сводится к их файлам, а точные
     * границы проверяются уже на каждом вхождении.
     *
     * Публичный метод, потому что это проверяемая граница поведения, а не
     * деталь: перебор по всему приложению виден только здесь.
     */
    fun candidateScope(options: FindUsagesOptions): GlobalSearchScope {
        val project = target.declaration.project
        val application = SmartAppScopes.applicationScope(project, target.appRoot)
        return when (val requested = options.searchScope) {
            is GlobalSearchScope -> application.intersectWith(requested)
            is LocalSearchScope ->
                application.intersectWith(GlobalSearchScopeUtil.toGlobalSearchScope(requested, project))
            else -> application
        }
    }
}
