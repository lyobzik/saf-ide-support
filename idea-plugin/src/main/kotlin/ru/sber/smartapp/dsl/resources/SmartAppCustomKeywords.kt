package ru.sber.smartapp.dsl.resources

import com.intellij.openapi.fileEditor.FileDocumentManager
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
        val project = file.project
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

    private fun compute(project: Project, appRoot: VirtualFile): List<CustomKeyword> =
        SmartAppResourceResolver.customKeywords(
            object : SmartAppResourceResolver.AppFiles {
                override fun read(relativePath: String): String? {
                    val file = appRoot.findFileByRelativePath(relativePath) ?: return null
                    if (file.isDirectory || !ownedBy(file, appRoot)) return null
                    return readText(project, file)
                }

                override fun exists(path: String): Boolean {
                    val file = appRoot.findFileByRelativePath(path) ?: return false
                    return ownedBy(file, appRoot)
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
            if (directory.findFileByRelativePath(PathSpec.rootSegments.joinToString("/")) != null) {
                return directory == appRoot
            }
            if (directory == appRoot) return true
            directory = directory.parent
        }
        return false
    }

    /** Текст файла: сначала документ редактора (правки видны до сохранения), затем диск. */
    private fun readText(project: Project, file: VirtualFile): String? {
        FileDocumentManager.getInstance().getDocument(file)?.let { return it.text }
        return runCatching { VfsUtilCore.loadText(file) }.getOrNull()
    }
}
