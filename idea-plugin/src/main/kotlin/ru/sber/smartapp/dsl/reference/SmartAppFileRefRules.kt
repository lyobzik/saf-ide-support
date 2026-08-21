package ru.sber.smartapp.dsl.reference

import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.vfs.VirtualFile
import ru.sber.smartapp.dsl.SmartAppFiles
import ru.sber.smartapp.dsl.contract.SmartAppSpecs
import ru.sber.smartapp.dsl.contract.TypeContextSpec

/**
 * Применяет таблицу файловых ссылок [SmartAppSpecs.fileRefRules] к PSI: значение
 * свойства `file` в объекте `type: unified_template` называет файл шаблона в
 * каталоге `templates` того же набора `references`.
 *
 * Отличие от [SmartAppRefRules]: цель — файл, найденный по пути, а не top-level
 * ключ, найденный по индексу. Поэтому индекс здесь не нужен и dumb-guard тоже.
 *
 * Происхождение правила описано в контракте ([SmartAppSpecs.fileRefRules]):
 * оно выведено из раскладки эталонного приложения, а не из исходников фреймворка.
 */
object SmartAppFileRefRules {

    /**
     * Каталоги поиска для [literal], если он стоит в позиции файловой ссылки,
     * иначе пустой список.
     */
    fun searchDirs(literal: JsonStringLiteral): List<String> {
        val property = literal.parent as? JsonProperty ?: return emptyList()
        if (property.value !== literal) return emptyList()
        val owner = property.parent as? JsonObject ?: return emptyList()

        val rule = SmartAppSpecs.fileRefRules.firstOrNull { it.property == property.name }
            ?: return emptyList()
        if (rule.ownerTypes != null && ownerType(owner) !in rule.ownerTypes) return emptyList()
        return rule.searchDirs
    }

    /** True, если [literal] стоит в позиции файловой ссылки. */
    fun isFileReference(literal: JsonStringLiteral): Boolean = searchDirs(literal).isNotEmpty()

    /**
     * Пути-кандидаты относительно корня набора `references` — по одному на
     * каталог поиска, в порядке правила. Пустой список означает «цель не
     * определена», и это не то же самое, что «файл не найден».
     *
     * Значение — путь вместе с расширением: имя файла контракт не достраивает.
     * Вложенные пути допускаются, выход за пределы каталога (`..`, ведущий `/`,
     * обратный слэш) — нет: чем такое значение является, из имеющихся данных не
     * следует, и молчание честнее ложной ошибки. Динамическое имя (Jinja)
     * кандидатов не даёт — как и везде.
     */
    fun candidatePaths(literal: JsonStringLiteral): List<String> {
        val dirs = searchDirs(literal)
        if (dirs.isEmpty()) return emptyList()
        val value = literal.value
        if (!isSafeRelativePath(value) || SmartAppReferenceContributor.isJinja(value)) return emptyList()
        return dirs.map { "$it/$value" }
    }

    /** Файл, на который указывает [literal], или `null`. */
    fun resolve(literal: JsonStringLiteral): VirtualFile? {
        val candidates = candidatePaths(literal)
        if (candidates.isEmpty()) return null
        val root = SmartAppFiles.referencesRoot(literal.containingFile?.virtualFile) ?: return null
        for (path in candidates) {
            val found = root.findFileByRelativePath(path)
            if (found != null && !found.isDirectory) return found
        }
        return null
    }

    private fun isSafeRelativePath(value: String): Boolean {
        if (value.isEmpty() || value.startsWith("/") || value.contains('\\')) return false
        return value.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    private fun ownerType(owner: JsonObject): String? {
        val typeValue = owner.findProperty(TypeContextSpec.typeProperty)?.value as? JsonStringLiteral
        return typeValue?.value
    }
}
