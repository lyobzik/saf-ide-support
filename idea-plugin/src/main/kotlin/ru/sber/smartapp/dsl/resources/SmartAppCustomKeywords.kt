package ru.sber.smartapp.dsl.resources

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import ru.sber.smartapp.dsl.SmartAppFiles
import ru.sber.smartapp.dsl.contract.PathSpec
import ru.sber.smartapp.dsl.contract.ResourceScanSpec
import ru.sber.smartapp.dsl.resources.SmartAppResourceResolver.CustomKeyword

/**
 * Ключевые слова, зарегистрированные приложением, для файлов его набора
 * `static/references`.
 *
 * Индекса здесь нет намеренно. Цепочка от `RESOURCES` — это два-три файла, и
 * читать их по VFS дешевле, чем городить `FileBasedIndex`, которому пришлось бы
 * хранить структуру модуля целиком. Побочный выигрыш: чтение не запрещено в
 * dumb mode, поэтому кастомные слова работают во время индексации так же, как
 * фреймворковые. Пересборка — целиком по приложению (правка базового класса
 * меняет словарь производного), а инвалидацию кэша обеспечивает платформа.
 */
object SmartAppCustomKeywords {

    private val KEY: Key<CachedValue<List<CustomKeyword>>> =
        Key.create("smartapp.dsl.custom.keywords")

    /** Слова приложения, которому принадлежит [file]; пустой список — нечего предложить. */
    fun of(file: PsiFile): List<CustomKeyword> {
        val virtualFile = file.virtualFile ?: return emptyList()
        val appRoot = applicationRoot(virtualFile) ?: return emptyList()
        return ofRoot(file.project, appRoot)
    }

    /** Слова приложения с корнем [appRoot]. */
    fun ofRoot(project: Project, appRoot: VirtualFile): List<CustomKeyword> {
        val directory = PsiManager.getInstance(project).findDirectory(appRoot) ?: return emptyList()

        // Провайдер не должен удерживать PSI (платформа проверяет это явно),
        // поэтому он замыкает только проект и каталог приложения.
        return CachedValuesManager.getManager(project)
            .getCachedValue(directory, KEY, Provider(project, appRoot), false)
    }

    private class Provider(
        private val project: Project,
        private val appRoot: VirtualFile,
    ) : CachedValueProvider<List<CustomKeyword>> {
        override fun compute(): CachedValueProvider.Result<List<CustomKeyword>> =
            CachedValueProvider.Result.create(
                compute(project, appRoot),
                PsiModificationTracker.MODIFICATION_COUNT,
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
            )
    }

    /** Корень приложения: каталог, содержащий `static/references` файла [file]. */
    fun applicationRoot(file: VirtualFile): VirtualFile? {
        var root = SmartAppFiles.referencesRoot(file) ?: return null
        repeat(PathSpec.rootSegments.size) { root = root.parent ?: return null }
        return root
    }

    /**
     * Корень приложения для **любого** файла — не только DSL-файла: ближайший
     * каталог-предок, внутри которого лежит набор `static/references`. То же
     * правило, что у [ownedBy]: вложенный `subapp` — отдельное приложение.
     */
    fun ownerApplicationRoot(file: VirtualFile): VirtualFile? {
        var directory = if (file.isDirectory) file else file.parent
        while (directory != null) {
            if (directory.findFileByRelativePath(REFERENCES_PATH) != null) return directory
            directory = directory.parent
        }
        return null
    }

    /**
     * Лежит ли [file] внутри исключённого каталога приложения (`venv`,
     * `site-packages`, …). Проверяются сегменты **ниже** [appRoot]: путь до
     * самого приложения — дело пользователя, а не признак зависимости.
     */
    fun hasExcludedSegment(file: VirtualFile, appRoot: VirtualFile): Boolean {
        var directory = file.parent
        while (directory != null && directory != appRoot) {
            if (directory.name in ResourceScanSpec.excludedDirs) return true
            directory = directory.parent
        }
        return false
    }

    /**
     * Регистрации приложения, которому принадлежит [dslFile], как элементы для
     * навигации и поиска использований.
     *
     * Элементы создаются на каждый запрос: сам словарь кэширован платформой, а
     * fake-элемент — тонкая обёртка над записью словаря, удерживать её в кэше
     * значило бы удерживать PSI.
     */
    fun registrations(dslFile: PsiFile): List<SmartAppRegistrationElement> {
        val virtualFile = dslFile.virtualFile ?: return emptyList()
        val appRoot = applicationRoot(virtualFile) ?: return emptyList()
        return registrationsOfRoot(dslFile.project, appRoot)
    }

    /**
     * Регистрации приложения с корнем [appRoot]. Отдельно от [registrations]
     * нужны направлению «каретка в Python-файле»: DSL-файла под рукой там нет,
     * а корень приложения известен.
     */
    fun registrationsOfRoot(project: Project, appRoot: VirtualFile): List<SmartAppRegistrationElement> =
        elementsOf(project, appRoot, ofRoot(project, appRoot))

    private fun elementsOf(
        project: Project,
        appRoot: VirtualFile,
        keywords: List<CustomKeyword>,
    ): List<SmartAppRegistrationElement> {
        val manager = PsiManager.getInstance(project)
        val files = HashMap<String, PsiFile?>()
        return keywords.mapNotNull { keyword ->
            val file = files.getOrPut(keyword.file) {
                appRoot.findFileByRelativePath(keyword.file)?.let { manager.findFile(it) }
            } ?: return@mapNotNull null
            SmartAppRegistrationElement(file, keyword)
        }
    }

    private val REFERENCES_PATH: String = PathSpec.rootSegments.joinToString("/")

    private fun compute(project: Project, appRoot: VirtualFile): List<CustomKeyword> =
        SmartAppResourceResolver.customKeywords(
            object : SmartAppResourceResolver.AppFiles {
                override fun read(relativePath: String): String? {
                    val file = appRoot.findFileByRelativePath(relativePath) ?: return null
                    if (file.isDirectory || !ownedBy(file, appRoot)) return null
                    return readText(project, file)
                }

                /**
                 * Модуль или пакет приложения: файл `<path>.py` либо хотя бы один
                 * `.py` внутри каталога `<path>`. Тот же ответ обязана давать
                 * реализация в ядре расширения — иначе «пропавший модуль» и
                 * «библиотечная база» поменяются местами.
                 */
                override fun exists(path: String): Boolean {
                    val module = appRoot.findFileByRelativePath(path + ResourceScanSpec.fileExtension)
                    if (module != null && !module.isDirectory && ownedBy(module, appRoot)) return true
                    val directory = appRoot.findFileByRelativePath(path) ?: return false
                    return directory.isDirectory && containsOwnedModule(directory, appRoot)
                }
            },
        )

    /**
     * Файл принадлежит именно этому приложению: ближайший корень с
     * `static/references` — [appRoot]. Вложенное приложение (`subapp`) — чужое,
     * его ресурсы в словарь внешнего не идут.
     */
    private fun ownedBy(file: VirtualFile, appRoot: VirtualFile): Boolean {
        var directory = file.parent
        while (directory != null) {
            if (directory.findFileByRelativePath(REFERENCES_PATH) != null) {
                return directory == appRoot
            }
            if (directory == appRoot) return true
            directory = directory.parent
        }
        return false
    }

    /**
     * Есть ли внутри [directory] хоть один Python-файл приложения. Обход
     * прерывается на первом попадании, исключённые каталоги (venv и прочие
     * зависимости) не обходятся вовсе.
     */
    private fun containsOwnedModule(directory: VirtualFile, appRoot: VirtualFile): Boolean {
        if (directory.name in ResourceScanSpec.excludedDirs) return false
        for (child in directory.children) {
            ProgressManager.checkCanceled()
            if (child.isDirectory) {
                if (containsOwnedModule(child, appRoot)) return true
                continue
            }
            if (child.name.endsWith(ResourceScanSpec.fileExtension) && ownedBy(child, appRoot)) {
                return true
            }
        }
        return false
    }

    /** Текст файла: сначала документ редактора (правки видны до сохранения), затем диск. */
    private fun readText(project: Project, file: VirtualFile): String? {
        FileDocumentManager.getInstance().getDocument(file)?.let { return it.text }
        return runCatching { VfsUtilCore.loadText(file) }.getOrNull()
    }
}
