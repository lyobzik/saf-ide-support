package ru.sber.smartapp.dsl.index

import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.KeyDescriptor
import ru.sber.smartapp.dsl.JsonPsi
import ru.sber.smartapp.dsl.SmartAppFiles
import ru.sber.smartapp.dsl.contract.FieldAccessSpec
import ru.sber.smartapp.dsl.SmartAppRefKind
import ru.sber.smartapp.dsl.reference.FormFieldRef
import ru.sber.smartapp.dsl.reference.FormFieldRefDescriptor

/**
 * Сопоставляет `FormFieldRef(form, field)` со смещениями имён свойств-полей формы
 * (`forms.<form>.fields.<field>`). В отличие от [SmartAppDefinitionIndex],
 * индексирует не top-level ключи, а поля второго уровня — внутри объекта `fields`
 * каждой top-level формы.
 *
 * Значение — [SmartAppDefinitionValue] (список смещений), переиспользуется, как и
 * externalizer: формат тот же (var-int offsets). Список, а не скаляр, чтобы дубли
 * полей в одной форме попадали в один ключ (по аналогии с top-level дублями).
 */
class SmartAppFormFieldIndex :
    FileBasedIndexExtension<FormFieldRef, SmartAppDefinitionValue>() {

    override fun getName(): ID<FormFieldRef, SmartAppDefinitionValue> = NAME

    override fun getKeyDescriptor(): KeyDescriptor<FormFieldRef> = FormFieldRefDescriptor

    override fun getValueExternalizer(): DataExternalizer<SmartAppDefinitionValue> =
        SmartAppDefinitionExternalizer

    override fun getVersion(): Int =
        1 + SmartAppDefinitionExternalizer.SERIALIZATION_VERSION + CONTENT_VERSION

    override fun dependsOnFileContent(): Boolean = true

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        FileBasedIndex.InputFilter { file -> SmartAppFiles.kindOf(file) == SmartAppRefKind.FORM }

    override fun getIndexer(): DataIndexer<FormFieldRef, SmartAppDefinitionValue, FileContent> =
        DataIndexer { inputData ->
            try {
                val jsonFile = inputData.psiFile as? JsonFile ?: return@DataIndexer emptyMap()
                // Битый/частичный JSON не индексируем (см. JsonPsi.hasError).
                if (JsonPsi.hasError(jsonFile)) return@DataIndexer emptyMap()
                val root = jsonFile.topLevelValue as? JsonObject ?: return@DataIndexer emptyMap()

                // Обходим все top-level формы и их `fields`. Используем propertyList,
                // а не findProperty, чтобы сохранить дубли полей в одной форме.
                val grouped = HashMap<FormFieldRef, MutableList<Int>>()
                for (formProp in root.propertyList) {
                    val formName = formProp.name
                    val fieldsObj = fieldsObjectOf(formProp) ?: continue
                    for (fieldProp in fieldsObj.propertyList) {
                        val ref = FormFieldRef(form = formName, field = fieldProp.name)
                        val offset = fieldProp.nameElement.textRange.startOffset
                        grouped.getOrPut(ref) { ArrayList() }.add(offset)
                    }
                }
                grouped.entries.associate { (ref, offsets) -> ref to SmartAppDefinitionValue(offsets) }
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

        val NAME: ID<FormFieldRef, SmartAppDefinitionValue> =
            ID.create("ru.sber.smartapp.dsl.SmartAppFormFieldIndex")

        /** Свойство `fields` top-level формы как объект, иначе `null`. */
        private fun fieldsObjectOf(formProp: JsonProperty): JsonObject? {
            val formObj = formProp.value as? JsonObject ?: return null
            val fieldsProp = formObj.findProperty(FieldAccessSpec.fieldsProperty) ?: return null
            return fieldsProp.value as? JsonObject
        }

        /**
         * Резолвит все определения поля [field] формы [form] в элементы [JsonProperty].
         * Вызывающий обязан убедиться, что индексация не идёт (dumb mode), прежде чем
         * вызывать этот метод.
         */
        fun findFields(
            project: Project,
            form: String,
            field: String,
            scope: GlobalSearchScope = GlobalSearchScope.allScope(project),
        ): List<JsonProperty> {
            val fbi = FileBasedIndex.getInstance()
            val psiManager = PsiManager.getInstance(project)
            val results = ArrayList<JsonProperty>()
            fbi.processValues(NAME, FormFieldRef(form, field), null, { file, value ->
                val psiFile = psiManager.findFile(file) as? JsonFile
                if (psiFile != null) {
                    for (offset in value.offsets) {
                        val property = propertyAtOffset(psiFile, offset)
                        if (property != null) results.add(property)
                    }
                }
                true
            }, scope)
            return results
        }

        private fun propertyAtOffset(file: JsonFile, offset: Int): JsonProperty? {
            val literal = PsiTreeUtil.findElementOfClassAtOffset(
                file, offset, JsonStringLiteral::class.java, true,
            )
            return literal?.parent as? JsonProperty
        }
    }
}
