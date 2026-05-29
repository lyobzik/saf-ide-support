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

    private fun load(): Map<String, Set<String>> {
        val stream = SmartAppKeywords::class.java.getResourceAsStream(RESOURCE)
            ?: return emptyMap()
        stream.use { input ->
            val root = InputStreamReader(input, StandardCharsets.UTF_8).use { reader ->
                JsonParser.parseReader(reader)
            }
            val categories = root.asJsonObject.getAsJsonObject("categories") ?: return emptyMap()
            val result = HashMap<String, Set<String>>()
            for ((category, values) in categories.entrySet()) {
                val set = values.asJsonArray.mapTo(LinkedHashSet()) { it.asString }
                result[category] = set
            }
            return result
        }
    }
}
