package ru.sber.smartapp.dsl

import com.google.gson.JsonParser
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Лениво загружает сгенерированный словарь ключевых слов из
 * `resources/keywords/keywords.json` и предоставляет запросы принадлежности по
 * категориям.
 *
 * Ресурс создаётся скриптом `tools/generate_keywords.py` из
 * `smart_kit/resources/__init__.py` фреймворка. Категории соответствуют ключам
 * объекта `categories` в JSON (например, `action`, `requirement`, `filler`, ...).
 */
object SmartAppKeywords {

    private const val RESOURCE = "/keywords/keywords.json"

    private val byCategory: Map<String, Set<String>> by lazy { load() }

    /** Объединение всех ключевых слов по всем категориям. */
    val allKeywords: Set<String> by lazy { byCategory.values.flatten().toHashSet() }

    fun all(category: String): Set<String> = byCategory[category] ?: emptySet()

    fun isKeyword(category: String, value: String): Boolean = value in all(category)

    /** True, если [value] — ключевое слово в любой категории. */
    fun isAnyKeyword(value: String): Boolean = value in allKeywords

    /**
     * Загружает словарь, **падая громко** при отсутствии или пустоте ресурса.
     *
     * Раньше здесь возвращался `emptyMap()`: неверно настроенный `processResources`
     * (словарь живёт в общем каталоге `shared/keywords/` и копируется в ресурсы на
     * сборке) молча отключал бы всю подсветку ключевых слов — ошибка, не заметная
     * ни в одном тесте. Fail-closed предпочтительнее тихой деградации.
     */
    private fun load(): Map<String, Set<String>> {
        val stream = SmartAppKeywords::class.java.getResourceAsStream(RESOURCE)
            ?: error("SmartApp DSL keyword dictionary is missing from the plugin classpath: $RESOURCE")
        stream.use { input ->
            val root = InputStreamReader(input, StandardCharsets.UTF_8).use { reader ->
                JsonParser.parseReader(reader)
            }
            val categories = root.asJsonObject.getAsJsonObject("categories")
                ?: error("SmartApp DSL keyword dictionary has no 'categories' object: $RESOURCE")
            val result = HashMap<String, Set<String>>()
            for ((category, values) in categories.entrySet()) {
                val set = values.asJsonArray.mapTo(LinkedHashSet()) { it.asString }
                result[category] = set
            }
            if (result.isEmpty()) {
                error("SmartApp DSL keyword dictionary is empty: $RESOURCE")
            }
            return result
        }
    }
}
