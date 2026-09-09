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
 * Корневое имя пользователя для приложения, которому принадлежит DSL-файл.
 *
 * Кэш и способ чтения — те же, что у [SmartAppUserModel]: индекса нет, файлы
 * берутся по VFS, единица пересборки — приложение целиком. Объекты раздельные
 * намеренно: цепочка `parametrizerChain` от `PARAMETRIZER` и цепочка
 * `userModelChain` от `USER` отказывают по разным причинам и с разными
 * последствиями, и смешивать их состояния нельзя.
 */
object SmartAppUserRoot {

    private val KEY: Key<CachedValue<SmartAppUserRootResolver.Root>> =
        Key.create("smartapp.dsl.user.root")

    /**
     * Корневое имя приложения, которому принадлежит [file]; `null` — файл вне
     * приложения. Отсутствие доказательства отказом не является: у него свой
     * исход внутри [SmartAppUserRootResolver.Root].
     */
    fun of(file: PsiFile): SmartAppUserRootResolver.Root? {
        val virtualFile = file.virtualFile ?: return null
        val appRoot = SmartAppCustomKeywords.applicationRoot(virtualFile) ?: return null
        return ofRoot(file.project, appRoot)
    }

    /** Корневое имя приложения с корнем [appRoot]. */
    fun ofRoot(project: Project, appRoot: VirtualFile): SmartAppUserRootResolver.Root? {
        val directory = PsiManager.getInstance(project).findDirectory(appRoot) ?: return null
        // Провайдер не должен удерживать PSI (платформа проверяет это явно),
        // поэтому он замыкает только проект и каталог приложения.
        return CachedValuesManager.getManager(project)
            .getCachedValue(directory, KEY, Provider(project, appRoot), false)
    }

    private class Provider(
        private val project: Project,
        private val appRoot: VirtualFile,
    ) : CachedValueProvider<SmartAppUserRootResolver.Root> {
        override fun compute(): CachedValueProvider.Result<SmartAppUserRootResolver.Root> =
            CachedValueProvider.Result.create(
                SmartAppUserRootResolver.of(SmartAppCustomKeywords.appFiles(project, appRoot)),
                PsiModificationTracker.MODIFICATION_COUNT,
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
            )
    }
}
