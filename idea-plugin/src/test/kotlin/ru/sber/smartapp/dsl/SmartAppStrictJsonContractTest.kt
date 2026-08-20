package ru.sber.smartapp.dsl

import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import ru.sber.smartapp.dsl.index.SmartAppDefinitionIndex

/**
 * Характеризация строгости JSON: что именно платформа считает индексируемым.
 *
 * Это **контракт для VS Code-расширения**, а не пожелание. Ожидания здесь
 * получены измерением фактического поведения `JsonPsi.hasError` поверх JSON PSI,
 * а не выведены из стандарта JSON: парсер IDEA толерантен к комментариям и
 * висячим запятым (нарушение стандарта отмечает инспекция, а не
 * `PsiErrorElement`), поэтому такие файлы **индексируются**.
 *
 * Отсюда следует настройка парсера на стороне VS Code: `jsonc-parser` обязан
 * вызываться с `allowTrailingComma = true` и без запрета комментариев — иначе
 * две IDE разойдутся на реальных файлах пользователей.
 *
 * Невалидность, которая **исключает** файл из индекса (оборванный JSON,
 * не-объектный корень, мусор после корневого значения), покрыта
 * [SmartAppIndexTest].
 */
class SmartAppStrictJsonContractTest : BasePlatformTestCase() {

    fun testLineCommentDoesNotBlockIndexing() {
        assertIndexed(
            "line_comment",
            "lc_form",
            """
            {
              // комментарий вне стандарта JSON
              "lc_form": { "type": "form" }
            }
            """.trimIndent(),
        )
    }

    fun testBlockCommentDoesNotBlockIndexing() {
        assertIndexed(
            "block_comment",
            "bc_form",
            """
            {
              /* комментарий вне стандарта JSON */
              "bc_form": { "type": "form" }
            }
            """.trimIndent(),
        )
    }

    fun testTrailingCommaAfterTopLevelEntryDoesNotBlockIndexing() {
        assertIndexed(
            "trailing_comma_object",
            "tco_form",
            """
            {
              "tco_form": { "type": "form" },
            }
            """.trimIndent(),
        )
    }

    fun testTrailingCommaInsideNestedObjectDoesNotBlockIndexing() {
        assertIndexed(
            "trailing_comma_nested",
            "tcn_form",
            """
            {
              "tcn_form": { "type": "form", }
            }
            """.trimIndent(),
        )
    }

    fun testTrailingCommaInsideArrayDoesNotBlockIndexing() {
        assertIndexed(
            "trailing_comma_array",
            "tca_form",
            """
            {
              "tca_form": { "type": "form", "items": [1, 2,] }
            }
            """.trimIndent(),
        )
    }

    private fun assertIndexed(fileName: String, definition: String, text: String) {
        myFixture.addFileToProject("static/references/forms/$fileName.json", text)
        val defs = SmartAppDefinitionIndex.findDefinitions(
            project,
            definition,
            listOf(SmartAppRefKind.FORM),
            GlobalSearchScope.projectScope(project),
        )
        assertEquals(
            "'$definition' обязан индексироваться: платформа не считает эту конструкцию ошибкой",
            1,
            defs.size,
        )
    }
}
