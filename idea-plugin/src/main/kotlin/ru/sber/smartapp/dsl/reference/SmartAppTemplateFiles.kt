package ru.sber.smartapp.dsl.reference

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VirtualFile

/**
 * Файлы каталогов файловой ссылки — источник вариантов автодополнения для
 * значения `"file"` при `"type": "unified_template"`.
 *
 * Индекса для таких файлов нет (резолв идёт по [VirtualFile]), поэтому список
 * собирается обходом VFS. Расширение отвечает тем же набором путей, но берёт их
 * из реестра не-DSL файлов набора — совпадение наборов фиксирует корпус.
 */
object SmartAppTemplateFiles {

    /**
     * Пути файлов внутри `<root>/<dir>` для каждого dir из [dirs], относительно
     * самого dir: объединение каталогов, одинаковый путь — один вариант.
     *
     * Список каталогов приходит параметром, а не берётся из правила: так
     * многокаталожный случай проверяется тестом, не подделывая данные контракта.
     * Приоритета у результата нет — какой из одноимённых файлов откроется,
     * решает резолв ([SmartAppFileRefRules.resolve]); здесь важно лишь, что
     * каждый путь резолвится.
     *
     * Гарантий по размеру каталога ветка не даёт (осознанно: усечённый список
     * навсегда спрятал бы существующий файл от префиксного поиска), поэтому
     * обход отменяем на каждом шаге — [ProgressManager.checkCanceled].
     * Символические ссылки следуются, как и в резолве, но каталог, чей
     * канонический путь уже пройден, повторно не обходится: `templates/loop`,
     * указывающий на `templates`, иначе дал бы бесконечную рекурсию.
     */
    fun pathsIn(root: VirtualFile, dirs: List<String>): List<String> {
        val paths = LinkedHashSet<String>()
        for (dir in dirs) {
            val base = root.findChild(dir) ?: continue
            // Регистр сверяем так же, как резолв: каталог с другим регистром имени
            // на регистронезависимой ФС — не наш каталог.
            if (!base.isDirectory || base.name != dir) continue
            collectInto(base, "", paths, HashSet())
        }
        return paths.toList()
    }

    private fun collectInto(
        dir: VirtualFile,
        prefix: String,
        paths: MutableSet<String>,
        visited: MutableSet<String>,
    ) {
        if (!visited.add(dir.canonicalPath ?: dir.path)) return
        for (child in dir.children) {
            ProgressManager.checkCanceled()
            val path = if (prefix.isEmpty()) child.name else "$prefix/" + child.name
            // Каталог целью не является: значение обязано указывать на файл.
            if (child.isDirectory) collectInto(child, path, paths, visited) else paths.add(path)
        }
    }
}
