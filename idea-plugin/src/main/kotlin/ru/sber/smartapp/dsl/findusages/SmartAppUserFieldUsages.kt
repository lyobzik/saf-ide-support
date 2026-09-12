package ru.sber.smartapp.dsl.findusages

import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.json.JsonFileType
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopeUtil
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.PsiSearchScopeUtil
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor
import ru.sber.smartapp.dsl.SmartAppFiles
import ru.sber.smartapp.dsl.SmartAppScopes
import ru.sber.smartapp.dsl.reference.JsonStringLiteralDecoder
import ru.sber.smartapp.dsl.reference.SmartAppJinjaLexer
import ru.sber.smartapp.dsl.reference.SmartAppReferenceContributor
import ru.sber.smartapp.dsl.resources.SmartAppCustomKeywords
import ru.sber.smartapp.dsl.resources.SmartAppUserFieldElement
import ru.sber.smartapp.dsl.resources.SmartAppUserModel
import ru.sber.smartapp.dsl.resources.SmartAppUserRoot

/**
 * Атрибут модели пользователя как цель поиска использований: имя, приложение и
 * все строки его объявления.
 *
 * Объявлений бывает несколько: одно имя может прийти и из списка `fields`, и из
 * `self.<имя> = …` в `__init__`, и обе строки — действующие цели. При
 * `includeDeclaration` показываются все — тот же список, что отдаёт переход.
 */
data class SmartAppUserFieldTarget(
    val name: String,
    val appRoot: VirtualFile,
    val declarations: List<SmartAppUserFieldElement>,
) {
    init {
        require(declarations.isNotEmpty()) { "target without declarations" }
    }

    val declaration: SmartAppUserFieldElement get() = declarations.first()
}

/**
 * Цель поиска по элементу под кареткой.
 *
 * Разбирается ровно один случай — сам fake-элемент объявления: до него
 * платформа доходит, разрешив ссылку в позиции каретки. Литерал целиком целью
 * быть не может: в одной Jinja-строке обращений несколько, и выбрать из них
 * одно без смещения каретки нельзя.
 *
 * Класса пользователя в этом списке нет намеренно: имени класса в тексте DSL не
 * существует, там стоит псевдоним из параметризатора, поэтому вхождения `user`
 * вхождениями `CustomUser` не являются (план, раздел 9).
 */
object SmartAppUserFieldTargets {

    fun of(element: PsiElement): SmartAppUserFieldTarget? {
        val declaration = element as? SmartAppUserFieldElement ?: return null
        val virtualFile = declaration.containingFile?.virtualFile ?: return null
        val appRoot = SmartAppCustomKeywords.ownerApplicationRoot(virtualFile) ?: return null
        // Все объявления этого имени, а не только то, на котором стоит каретка:
        // список обязан совпасть с целями перехода.
        val siblings = SmartAppUserModel.declarationsOfRoot(
            declaration.project, appRoot, declaration.attributeName,
        )
        return SmartAppUserFieldTarget(
            name = declaration.attributeName,
            appRoot = appRoot,
            declarations = siblings.ifEmpty { listOf(declaration) },
        )
    }
}

/**
 * Ищет вхождения атрибута модели пользователя в JSON-файлах этого же
 * приложения.
 *
 * Устроен как [SmartAppCustomKeywordUsagesHandler] и по тем же причинам:
 * обратного индекса в плагине нет, обход ограничен каталогом приложения и
 * прерывается по `checkCanceled`. Вхождения ищет тот же лексер, что и ссылки, —
 * источник правды один.
 */
class SmartAppUserFieldUsagesHandler(
    private val target: SmartAppUserFieldTarget,
) : FindUsagesHandler(target.declaration) {

    /**
     * Объявлениями считаются **все** строки имени: платформа показывает их при
     * «Include declaration», и список обязан совпасть с целями перехода.
     *
     * Цена этого — [processElementUsages] вызывается на каждый элемент списка,
     * поэтому поиск там выполняется ровно один раз (см. ниже).
     */
    override fun getPrimaryElements(): Array<PsiElement> = target.declarations.toTypedArray()

    override fun processElementUsages(
        element: PsiElement,
        processor: Processor<in UsageInfo>,
        options: FindUsagesOptions,
    ): Boolean {
        if (!options.isUsages) return true
        // Платформа зовёт метод на каждый primary-элемент, а объявлений у имени
        // бывает несколько (список `fields` и `self.<имя>` в `__init__`). Ищем
        // по первому: иначе каждое вхождение приедет в список столько раз,
        // сколько у имени строк объявления, и приложение обошлось бы дважды.
        if (element != target.declaration) return true
        val project = target.declaration.project

        // Платформа зовёт метод на пуловом потоке и **без** read action, поэтому
        // каждое обращение к индексу, кэшу и PSI берёт его само. Read action на
        // файл, а не один на весь обход: под длинным чтением не может начаться
        // запись, и редактор встаёт на всё время поиска.
        val roots = runReadAction {
            // Обход опирается на FileTypeIndex; в dumb mode он недоступен, а само
            // действие платформа в это время и не предлагает.
            if (DumbService.isDumb(project)) emptyList()
            else SmartAppUserRoot.ofRoot(project, target.appRoot)?.names.orEmpty()
        }
        if (roots.isEmpty()) return true

        val files = runReadAction {
            FileTypeIndex.getFiles(JsonFileType.INSTANCE, candidateScope(options)).toList()
        }

        // Локальная область («в текущем файле», подсветка вхождений) ограничивает
        // не файлы, а элементы, поэтому она проверяется дважды: файлы — через
        // приведение к глобальной, конкретное вхождение — по диапазону.
        val local = options.searchScope as? LocalSearchScope
        val manager = PsiManager.getInstance(project)
        for (virtualFile in files) {
            ProgressManager.checkCanceled()
            val proceed = runReadAction {
                // Список собран под другим read action: файл мог быть удалён в
                // промежутке, а `findFile` на невалидном файле бросает.
                if (!virtualFile.isValid) return@runReadAction true
                // Вид файла — до всякого разбора PSI: каталог приложения рекурсивен,
                // и обычный `config.json` рядом с кодом в него тоже попадает.
                if (SmartAppFiles.kindOf(virtualFile) == null) return@runReadAction true
                if (!isOwnFile(virtualFile)) return@runReadAction true
                val psiFile = manager.findFile(virtualFile) ?: return@runReadAction true
                processFile(psiFile, roots, local, processor)
            }
            if (!proceed) return false
        }
        return true
    }

    /**
     * Файл принадлежит именно этому приложению и не лежит в зависимости: в
     * каталог приложения входят и набор вложенного приложения, и вендоренная
     * внутрь набора библиотека.
     */
    private fun isOwnFile(virtualFile: VirtualFile): Boolean {
        if (SmartAppCustomKeywords.applicationRoot(virtualFile) != target.appRoot) return false
        return !SmartAppCustomKeywords.hasExcludedSegment(virtualFile, target.appRoot)
    }

    private fun processFile(
        psiFile: PsiFile,
        roots: List<String>,
        local: LocalSearchScope?,
        processor: Processor<in UsageInfo>,
    ): Boolean {
        var proceed = true
        psiFile.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                ProgressManager.checkCanceled()
                if (element is JsonStringLiteral && !processLiteral(element, roots, local, processor)) {
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
    private fun processLiteral(
        literal: JsonStringLiteral,
        roots: List<String>,
        local: LocalSearchScope?,
        processor: Processor<in UsageInfo>,
    ): Boolean {
        // Семантика — только у значений JSON, как и у ссылок.
        if (!SmartAppReferenceContributor.isJsonValue(literal)) return true
        if (!SmartAppReferenceContributor.isJinja(literal.value)) return true
        // Локальная область может покрывать часть файла — проверяем сам элемент.
        if (local != null && !PsiSearchScopeUtil.isInScope(local, literal)) return true

        val raw = JsonStringLiteralDecoder.rawText(literal) ?: return true
        val decoded = JsonStringLiteralDecoder.decode(raw)
        for (access in SmartAppJinjaLexer.rootAccesses(decoded.text)) {
            if (access.root !in roots || access.member != target.name) continue
            val range = access.memberRange ?: continue
            // Диапазон — имя без корня и без кавычек: тот же контракт, что у ссылок.
            val start = decoded.decodedToRaw[range.startOffset] + 1
            val end = decoded.decodedToRaw[range.endOffset] + 1
            if (!processor.process(UsageInfo(literal, start, end, false))) return false
        }
        return true
    }

    /**
     * Файлы-кандидаты: каталог приложения, суженный запрошенной областью.
     * Запрошенная область обязана сужать и перебор — иначе поиск «в текущем
     * файле» читал бы всё приложение и только потом выбрасывал лишнее.
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
