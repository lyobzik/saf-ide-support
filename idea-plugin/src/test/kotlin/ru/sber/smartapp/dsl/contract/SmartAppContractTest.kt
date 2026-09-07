package ru.sber.smartapp.dsl.contract

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.sber.smartapp.dsl.SmartAppKeywords
import ru.sber.smartapp.dsl.SmartAppRefKind
import ru.sber.smartapp.dsl.resources.SmartAppUserFields
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
    fun snapshotCarriesUserModelTableValues() {
        // exportRules --check сверяет снимок с собственным выводом экспортёра и
        // потому не заметит, что экспортёр положил не то значение (скажем,
        // formVariable вместо userVariableDefault). Здесь снимок сверяется с
        // самими таблицами.
        val jinja = rulesJson().getAsJsonObject("jinja")
        assertEquals(JinjaSpec.formVariable, jinja.get("formVariable").asString)
        assertEquals(JinjaSpec.userVariableDefault, jinja.get("userVariableDefault").asString)

        val userModel = rulesJson().getAsJsonObject("userModel")
        assertEquals(UserModelSpec.configVariable, userModel.get("configVariable").asString)
        assertEquals(UserModelSpec.defaultClass, userModel.get("defaultClass").asString)
        assertEquals(UserModelSpec.fieldsProperty, userModel.get("fieldsProperty").asString)
        assertEquals(UserModelSpec.fieldFactory, userModel.get("fieldFactory").asString)
        assertEquals(
            UserModelSpec.parametrizerVariable,
            userModel.get("parametrizerVariable").asString,
        )
        assertEquals(
            UserModelSpec.parametrizerDefaultClass,
            userModel.get("parametrizerDefaultClass").asString,
        )
        assertEquals(UserModelSpec.parametrizerMethod, userModel.get("parametrizerMethod").asString)
        assertEquals(
            UserModelSpec.userValueExpression,
            userModel.get("userValueExpression").asString,
        )
        assertEquals(
            UserModelSpec.blockerTokens.toList(),
            userModel.getAsJsonArray("blockerTokens").map { it.asString },
        )
        assertEquals(
            UserModelSpec.blockerConstructs.toList(),
            userModel.getAsJsonArray("blockerConstructs").map { it.asString },
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

    @Test
    fun userFieldSnapshotIsBundledAndMatchesShared() {
        val shared = File(sharedDir, "keywords/user_fields.json")
        assertTrue("снимок модели пользователя обязан лежать в shared/keywords/", shared.isFile)

        val sharedClasses = JsonParser.parseString(shared.readText())
            .asJsonObject.getAsJsonObject("classes")
        assertTrue("снимок не должен быть пустым", sharedClasses.size() > 0)

        for ((dotted, value) in sharedClasses.entrySet()) {
            val spec = value.asJsonObject
            val loaded = SmartAppUserFields.byClass[dotted]
            assertEquals(
                "класс '$dotted' обязан попасть в ресурсы плагина",
                spec.getAsJsonArray("fields").map { it.asString }.toSet(),
                loaded?.fields,
            )
            assertEquals(
                "атрибуты '$dotted' обязаны совпадать с shared/",
                spec.getAsJsonArray("attributes").map { it.asString }.toSet(),
                loaded?.attributes,
            )
            assertEquals(spec.get("diagnosticsSafe").asBoolean, loaded?.diagnosticsSafe)
        }
    }

    @Test
    fun userFieldSnapshotCarriesLibraryFloor() {
        // Пол — не абстракция: на типовом приложении именно отсюда приходят все
        // имена, включая `variables` из примера задачи. Пустой или урезанный
        // снимок молча выключил бы семантику модели пользователя.
        val user = SmartAppUserFields.of(UserModelSpec.defaultClass)
        assertTrue(
            "класс по умолчанию '${UserModelSpec.defaultClass}' обязан быть в снимке",
            user != null,
        )
        assertTrue("поле 'variables' обязано быть в полу", user!!.fields.contains("variables"))
        assertTrue("поле 'forms' обязано быть в полу", user.fields.contains("forms"))
        assertTrue("атрибут 'message' обязан быть в полу", user.attributes.contains("message"))
        assertTrue("приватные имена в пол не попадают", user.attributes.none { it.startsWith("_") })
    }

    private fun rulesJson() =
        JsonParser.parseString(File(sharedDir, "rules/rules.json").readText()).asJsonObject
}
