package ru.sber.smartapp.dsl

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile

/**
 * Определяет, является ли JSON-файл reference-файлом SmartApp DSL, и если да —
 * к какому [SmartAppRefKind] он относится.
 *
 * Файл подходит, если его путь содержит подряд идущие сегменты
 * `static` -> `references` -> `<kind-dir>`, где `<kind-dir>` — один из известных
 * reference-каталогов. После каталога вида допускаются произвольные вложенные
 * директории (например, `static/references/scenarios/sub/a.json`).
 *
 * Проверка пути дешёвая, поэтому в v1 результаты не кэшируются.
 */
object SmartAppFiles {

    fun kindOf(file: VirtualFile?): SmartAppRefKind? {
        if (file == null || file.isDirectory) return null
        if (!file.name.endsWith(".json", ignoreCase = true)) return null
        return kindFromSegments(pathSegments(file))
    }

    fun kindOf(psiFile: PsiFile?): SmartAppRefKind? = kindOf(psiFile?.virtualFile)

    fun isDslFile(file: VirtualFile?): Boolean = kindOf(file) != null

    /**
     * Каталог `references` (родитель каталогов видов) для [file], если файл
     * лежит внутри `static/references/<kind>/...`. Нужен, чтобы ограничивать
     * межфайловый резолв одним набором SmartApp-определений.
     */
    fun referencesRoot(file: VirtualFile?): VirtualFile? {
        if (file == null) return null
        var current: VirtualFile? = if (file.isDirectory) file else file.parent
        while (current != null) {
            val parent = current.parent
            if (current.name == "references" && parent?.name == "static") return current
            current = parent
        }
        return null
    }

    /** Собирает сегменты пути от корня VFS вниз до [file] включительно. */
    private fun pathSegments(file: VirtualFile): List<String> {
        val segments = ArrayList<String>()
        var current: VirtualFile? = file
        while (current != null) {
            segments.add(current.name)
            current = current.parent
        }
        segments.reverse()
        return segments
    }

    /**
     * Находит подряд идущие `static` -> `references` -> `<kind>` в [segments] и
     * возвращает соответствующий вид, если он есть.
     */
    private fun kindFromSegments(segments: List<String>): SmartAppRefKind? {
        // Минимум static/references/<kind>/файл = 4 хвостовых сегмента,
        // но каталог вида может находиться на любом уровне выше файла.
        for (i in 0..segments.size - 3) {
            if (segments[i] == "static" && segments[i + 1] == "references") {
                val kind = SmartAppRefKind.forDir(segments[i + 2])
                if (kind != null) return kind
            }
        }
        return null
    }
}
