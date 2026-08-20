package ru.sber.smartapp.dsl.contract

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.sber.smartapp.dsl.SmartAppKeywords
import ru.sber.smartapp.dsl.SmartAppRefKind
import java.io.File

/**
 * Проверяет общие артефакты контракта, которые читает VS Code-расширение.
 *
 * Актуальность снимка `rules.json` проверяет таск `exportRules --check`; здесь
 * проверяется то, что таск проверить не может: согласованность версии с
 * Kotlin-константой, наличие всех видов сущностей в снимке и непустота словаря
 * ключевых слов (fail-closed вместо тихой деградации).
 */
class SmartAppContractTest {

    private val sharedDir: File
        get() = File(
            System.getProperty("smartapp.shared")
                ?: error("system property 'smartapp.shared' is not set by the build"),
        )

    @Test
    fun contractVersionMatchesKotlinConstant() {
        val meta = rulesJson().getAsJsonObject("_meta")
        assertEquals(
            "версия снимка обязана совпадать с SmartAppContract.VERSION",
            SmartAppContract.VERSION,
            meta.get("contractVersion").asInt,
        )
        assertTrue(
            "снимок обязан быть помечен как генерируемый",
            meta.get("generated").asBoolean,
        )
    }

    @Test
    fun snapshotListsEveryKindWithItsDirectory() {
        val kinds = rulesJson().getAsJsonArray("kinds")
            .associate { it.asJsonObject.get("kind").asString to it.asJsonObject.get("dirName").asString }
        assertEquals(
            "снимок обязан перечислять все виды сущностей",
            SmartAppRefKind.entries.associate { it.name to it.dirName },
            kinds,
        )
    }

    @Test
    fun keywordDictionaryIsPresentAndNotEmpty() {
        assertTrue(
            "словарь ключевых слов не должен быть пустым — иначе подсветка молча отключена",
            SmartAppKeywords.allKeywords.isNotEmpty(),
        )
    }

    @Test
    fun sharedKeywordsFileIsTheOneBundledIntoResources() {
        val shared = File(sharedDir, "keywords/keywords.json")
        assertTrue("общий словарь обязан лежать в shared/keywords/", shared.isFile)

        val sharedCategories = JsonParser.parseString(shared.readText())
            .asJsonObject.getAsJsonObject("categories")
            .entrySet().associate { (name, values) ->
                name to values.asJsonArray.map { it.asString }.toSet()
            }
        for ((category, values) in sharedCategories) {
            assertEquals(
                "категория '$category' в ресурсах плагина обязана совпадать с shared/",
                values,
                SmartAppKeywords.all(category),
            )
        }
    }

    private fun rulesJson() =
        JsonParser.parseString(File(sharedDir, "rules/rules.json").readText()).asJsonObject
}
