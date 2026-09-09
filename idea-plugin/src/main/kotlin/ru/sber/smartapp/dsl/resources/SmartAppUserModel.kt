package ru.sber.smartapp.dsl.resources

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker

/**
 * Словарь модели пользователя приложения, которому принадлежит DSL-файл.
 *
 * Индекса здесь нет — по тем же причинам, что у [SmartAppCustomKeywords]:
 * цепочка от `USER` это два-три файла, читать их по VFS дешевле, чем городить
 * `FileBasedIndex`, а побочным выигрышем чтение не запрещено в dumb mode, и
 * семантика модели работает во время индексации. Единица пересборки —
 * приложение целиком: правка базового класса меняет словарь производного.
 */
object SmartAppUserModel {

    private val KEY: Key<CachedValue<Result>> = Key.create("smartapp.dsl.user.model")

    /** `info == null` — словаря нет вовсе (см. [SmartAppUserModelResolver.of]). */
    data class Result(val info: SmartAppUserModelResolver.Info?)

    /** Словарь приложения, которому принадлежит [file]; `null` — словаря нет. */
    fun of(file: PsiFile): SmartAppUserModelResolver.Info? {
        val virtualFile = file.virtualFile ?: return null
        val appRoot = SmartAppCustomKeywords.applicationRoot(virtualFile) ?: return null
        return ofRoot(file.project, appRoot)
    }

    /** Словарь приложения с корнем [appRoot]. */
    fun ofRoot(project: Project, appRoot: VirtualFile): SmartAppUserModelResolver.Info? {
        val directory = PsiManager.getInstance(project).findDirectory(appRoot) ?: return null
        // Провайдер не должен удерживать PSI (платформа проверяет это явно),
        // поэтому он замыкает только проект и каталог приложения.
        return CachedValuesManager.getManager(project)
            .getCachedValue(directory, KEY, Provider(project, appRoot), false)
            .info
    }

    /**
     * Объявления атрибута [name] в Python-коде приложения, которому принадлежит
     * [dslFile]. Пустой список — имя пришло из снимка фреймворка либо словаря
     * нет вовсе: идти в коде проекта некуда.
     */
    fun declarations(dslFile: PsiFile, name: String): List<SmartAppUserFieldElement> {
        val virtualFile = dslFile.virtualFile ?: return emptyList()
        val appRoot = SmartAppCustomKeywords.applicationRoot(virtualFile) ?: return emptyList()
        return declarationsOfRoot(dslFile.project, appRoot, name)
    }

    /**
     * Объявления имени в приложении с корнем [appRoot]. Отдельно от
     * [declarations] нужны поиску использований: DSL-файла под рукой там нет, а
     * корень приложения известен.
     */
    fun declarationsOfRoot(
        project: Project,
        appRoot: VirtualFile,
        name: String,
    ): List<SmartAppUserFieldElement> {
        val attribute = ofRoot(project, appRoot)?.attributes?.get(name) ?: return emptyList()

        val manager = PsiManager.getInstance(project)
        val files = HashMap<String, PsiFile?>()
        return attribute.declarations.mapNotNull { site ->
            val file = files.getOrPut(site.file) {
                appRoot.findFileByRelativePath(site.file)?.let { manager.findFile(it) }
            } ?: return@mapNotNull null
            SmartAppUserFieldElement(file, name, site)
        }
    }

    /**
     * Класс приложения, назначенный `USER`, как цель перехода с корневой
     * переменной. `null` — активный класс библиотечный: переходить некуда.
     */
    fun userClass(dslFile: PsiFile): SmartAppUserClassElement? {
        val virtualFile = dslFile.virtualFile ?: return null
        val appRoot = SmartAppCustomKeywords.applicationRoot(virtualFile) ?: return null
        val site = ofRoot(dslFile.project, appRoot)?.userClass ?: return null
        val file = appRoot.findFileByRelativePath(site.file)
            ?.let { PsiManager.getInstance(dslFile.project).findFile(it) } ?: return null
        return SmartAppUserClassElement(file, site)
    }

    private class Provider(
        private val project: Project,
        private val appRoot: VirtualFile,
    ) : CachedValueProvider<Result> {
        override fun compute(): CachedValueProvider.Result<Result> =
            CachedValueProvider.Result.create(
                Result(SmartAppUserModelResolver.of(SmartAppCustomKeywords.appFiles(project, appRoot))),
                PsiModificationTracker.MODIFICATION_COUNT,
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
            )
    }
}
