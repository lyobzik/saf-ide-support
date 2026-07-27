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
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import ru.sber.smartapp.dsl.JsonPsi
import ru.sber.smartapp.dsl.SmartAppFiles
import ru.sber.smartapp.dsl.SmartAppRefKind

/**
 * Сопоставляет составные ключи `"<KIND>:<name>"` со смещениями имён
 * верхнеуровневых свойств, определяющих каждую сущность. Индексация идёт по PSI
 * (файл — это [JsonFile]); [JsonObject.getPropertyList] сохраняет
 * повторяющиеся top-level ключи, поэтому два одноимённых определения в одном
 * файле оба попадают в индекс (object-model парсеры их схлопнули бы).
 */
class SmartAppDefinitionIndex :
    FileBasedIndexExtension<String, SmartAppDefinitionValue>() {

    override fun getName(): ID<String, SmartAppDefinitionValue> = NAME

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer(): DataExternalizer<SmartAppDefinitionValue> =
        SmartAppDefinitionExternalizer

    override fun getVersion(): Int =
        1 + SmartAppDefinitionExternalizer.SERIALIZATION_VERSION + CONTENT_VERSION

    override fun dependsOnFileContent(): Boolean = true

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        FileBasedIndex.InputFilter { file -> SmartAppFiles.isDslFile(file) }

    override fun getIndexer(): DataIndexer<String, SmartAppDefinitionValue, FileContent> =
        DataIndexer { inputData ->
            try {
                val kind = SmartAppFiles.kindOf(inputData.file) ?: return@DataIndexer emptyMap()
                val jsonFile = inputData.psiFile as? JsonFile ?: return@DataIndexer emptyMap()
                // Отбрасываем невалидный JSON до обхода propertyList. Часть ошибок
                // парсер отмечает PsiErrorElement, но trailing-garbage после `}`
                // (например `{ "a": {} } junk`) он молча игнорирует, без PSI-ошибки,
                // достраивая корневой объект. Поэтому JsonPsi.hasError проверяет и
                // PsiErrorElement, и значимый хвост после корневого значения — иначе
                // валидный ключ из такого файла попал бы в индекс.
                if (JsonPsi.hasError(jsonFile)) return@DataIndexer emptyMap()
                val root = jsonFile.topLevelValue as? JsonObject
                    ?: return@DataIndexer emptyMap()

                val grouped = LinkedHashMap<String, MutableList<Int>>()
                for (property in root.propertyList) {
                    val offset = property.nameElement.textRange.startOffset
                    grouped.getOrPut(property.name) { ArrayList() }.add(offset)
                }

                grouped.entries.associate { (name, offsets) ->
                    "${kind.name}:$name" to SmartAppDefinitionValue(offsets)
                }
            } catch (e: ProcessCanceledException) {
                // Отмену индексации нельзя глотать — пробрасываем платформе.
                throw e
            } catch (e: Exception) {
                // Битый JSON/PSI: не валим индексацию проекта, просто пропускаем файл.
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

        val NAME: ID<String, SmartAppDefinitionValue> =
            ID.create("ru.sber.smartapp.dsl.SmartAppDefinitionIndex")

        private fun key(kind: SmartAppRefKind, name: String) = "${kind.name}:$name"

        /**
         * Резолвит все определения [name] для любого из [kinds] в
         * соответствующие элементы [JsonProperty]. Вызывающий обязан убедиться,
         * что индексация не идёт (dumb mode), прежде чем вызывать этот метод.
         */
        fun findDefinitions(
            project: Project,
            name: String,
            kinds: List<SmartAppRefKind>,
            scope: GlobalSearchScope = GlobalSearchScope.allScope(project),
        ): List<JsonProperty> {
            val fbi = FileBasedIndex.getInstance()
            val psiManager = PsiManager.getInstance(project)
            val results = ArrayList<JsonProperty>()
            for (kind in kinds) {
                fbi.processValues(NAME, key(kind, name), null, { file, value ->
                    val psiFile = psiManager.findFile(file) as? JsonFile
                    if (psiFile != null) {
                        for (offset in value.offsets) {
                            val property = propertyAtOffset(psiFile, offset)
                            if (property != null && isTopLevelDefinition(property)) {
                                results.add(property)
                            }
                        }
                    }
                    true
                }, scope)
            }
            return results
        }

        private fun propertyAtOffset(file: JsonFile, offset: Int): JsonProperty? {
            val literal = PsiTreeUtil.findElementOfClassAtOffset(
                file, offset, JsonStringLiteral::class.java, true,
            )
            return literal?.parent as? JsonProperty
        }

        private fun isTopLevelDefinition(property: JsonProperty): Boolean {
            val owner = property.parent as? JsonObject ?: return false
            return owner.parent is JsonFile
        }
    }
}
