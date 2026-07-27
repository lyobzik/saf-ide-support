package ru.sber.smartapp.dsl.index

import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.DataInputOutputUtil
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.IOUtil
import com.intellij.util.io.KeyDescriptor
import ru.sber.smartapp.dsl.JsonPsi
import ru.sber.smartapp.dsl.SmartAppFiles
import ru.sber.smartapp.dsl.SmartAppRefKind
import java.io.DataInput
import java.io.DataOutput

/**
 * Сопоставляет вид сущности ([SmartAppRefKind.name]) со списком имён
 * верхнеуровневых определений в каждом DSL-файле этого вида. В отличие от
 * [SmartAppDefinitionIndex] (составной ключ `"<KIND>:<name>"`), здесь ключ —
 * один на вид, поэтому перечисление имён для автодополнения делается через
 * `processValues` по единственному ключу, без дорогого `getAllKeys`-скана всего
 * проекта.
 */
class SmartAppNameIndex : FileBasedIndexExtension<String, List<String>>() {

    override fun getName(): ID<String, List<String>> = NAME

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer(): DataExternalizer<List<String>> = NamesExternalizer

    override fun getVersion(): Int = 1 + CONTENT_VERSION

    override fun dependsOnFileContent(): Boolean = true

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        FileBasedIndex.InputFilter { file -> SmartAppFiles.isDslFile(file) }

    override fun getIndexer(): DataIndexer<String, List<String>, FileContent> =
        DataIndexer { inputData ->
            try {
                val kind = SmartAppFiles.kindOf(inputData.file) ?: return@DataIndexer emptyMap()
                val jsonFile = inputData.psiFile as? JsonFile ?: return@DataIndexer emptyMap()
                // Отбрасываем невалидный JSON до обхода propertyList. Часть ошибок
                // парсер отмечает PsiErrorElement, но trailing-garbage после `}`
                // (например `{ "a": {} } junk`) он молча игнорирует, без PSI-ошибки,
                // достраивая корневой объект. Поэтому JsonPsi.hasError проверяет и
                // PsiErrorElement, и значимый хвост после корневого значения — иначе
                // валидный ключ из такого файла попал бы в индекс имён.
                if (JsonPsi.hasError(jsonFile)) return@DataIndexer emptyMap()
                val root = jsonFile.topLevelValue as? JsonObject
                    ?: return@DataIndexer emptyMap()

                val names = LinkedHashSet<String>()
                for (property in root.propertyList) {
                    names.add(property.name)
                }
                if (names.isEmpty()) emptyMap() else mapOf(kind.name to names.toList())
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                emptyMap()
            }
        }

    companion object {
        /**
         * Версия алгоритма индексирования (не формата сериализации). Повышается при
         * изменении того, какие данные попадают в индекс. Сейчас учитывает ввод guard'а
         * [JsonPsi.hasError]: без повышения версии persistent-индекс сохранил бы
         * обрывочные ключи из битых файлов до их повторного изменения.
         */
        private const val CONTENT_VERSION = 1

        val NAME: ID<String, List<String>> =
            ID.create("ru.sber.smartapp.dsl.SmartAppNameIndex")

        /** Все имена сущностей вида [kind] в области [scope] (для автодополнения). */
        fun allNames(
            project: Project,
            kind: SmartAppRefKind,
            scope: GlobalSearchScope = GlobalSearchScope.projectScope(project),
        ): List<String> {
            val names = LinkedHashSet<String>()
            FileBasedIndex.getInstance().processValues(
                NAME, kind.name, null,
                { _, value -> names.addAll(value); true },
                scope,
            )
            return names.toList()
        }
    }

    /** Формат хранения списка имён: var-int количество, затем UTF-строки. */
    private object NamesExternalizer : DataExternalizer<List<String>> {
        override fun save(out: DataOutput, value: List<String>) {
            DataInputOutputUtil.writeINT(out, value.size)
            for (name in value) {
                IOUtil.writeUTF(out, name)
            }
        }

        override fun read(input: DataInput): List<String> {
            val size = DataInputOutputUtil.readINT(input)
            val names = ArrayList<String>(size)
            repeat(size) {
                names.add(IOUtil.readUTF(input))
            }
            return names
        }
    }
}
