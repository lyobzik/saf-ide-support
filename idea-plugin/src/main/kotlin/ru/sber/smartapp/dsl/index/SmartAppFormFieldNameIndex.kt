package ru.sber.smartapp.dsl.index

import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
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
import ru.sber.smartapp.dsl.contract.FieldAccessSpec
import ru.sber.smartapp.dsl.SmartAppRefKind
import java.io.DataInput
import java.io.DataOutput

/**
 * Сопоставляет имя формы со списком имён её полей — для автодополнения имён полей
 * внутри Jinja `{{ main_form.<caret> }}`. Ключ — имя формы, значение —
 * дедуплицированный список имён полей (через `LinkedHashSet`, порядок первого
 * вхождения сохраняется).
 *
 * Аналог [SmartAppNameIndex] для полей: один ключ на форму → перечисление полей
 * через `processValues` без `getAllKeys`-скана.
 */
class SmartAppFormFieldNameIndex : FileBasedIndexExtension<String, List<String>>() {

    override fun getName(): ID<String, List<String>> = NAME

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer(): DataExternalizer<List<String>> = FieldNamesExternalizer

    override fun getVersion(): Int = 1 + CONTENT_VERSION

    override fun dependsOnFileContent(): Boolean = true

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        FileBasedIndex.InputFilter { file -> SmartAppFiles.kindOf(file) == SmartAppRefKind.FORM }

    override fun getIndexer(): DataIndexer<String, List<String>, FileContent> =
        DataIndexer { inputData ->
            try {
                val jsonFile = inputData.psiFile as? JsonFile ?: return@DataIndexer emptyMap()
                if (JsonPsi.hasError(jsonFile)) return@DataIndexer emptyMap()
                val root = jsonFile.topLevelValue as? JsonObject ?: return@DataIndexer emptyMap()

                val byForm = LinkedHashMap<String, LinkedHashSet<String>>()
                for (formProp in root.propertyList) {
                    val formObj = formProp.value as? JsonObject ?: continue
                    val fieldsProp = formObj.findProperty(FieldAccessSpec.fieldsProperty) ?: continue
                    val fieldsObj = fieldsProp.value as? JsonObject ?: continue
                    val names = byForm.getOrPut(formProp.name) { LinkedHashSet() }
                    for (fieldProp in fieldsObj.propertyList) {
                        names.add(fieldProp.name)
                    }
                }
                byForm.entries.associate { (form, names) -> form to names.toList() }
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                emptyMap()
            }
        }

    companion object {
        /**
         * Версия алгоритма индексирования (не формата сериализации). Повышается при
         * изменении того, какие данные попадают в индекс.
         */
        private const val CONTENT_VERSION = 1

        val NAME: ID<String, List<String>> =
            ID.create("ru.sber.smartapp.dsl.SmartAppFormFieldNameIndex")

        /** Все имена полей формы [form] в области [scope] (для автодополнения). */
        fun allNames(
            project: Project,
            form: String,
            scope: GlobalSearchScope = GlobalSearchScope.projectScope(project),
        ): List<String> {
            val names = LinkedHashSet<String>()
            FileBasedIndex.getInstance().processValues(
                NAME, form, null,
                { _, value -> names.addAll(value); true },
                scope,
            )
            return names.toList()
        }
    }

    /** Формат хранения списка имён полей: var-int количество, затем UTF-строки. */
    private object FieldNamesExternalizer : DataExternalizer<List<String>> {
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
