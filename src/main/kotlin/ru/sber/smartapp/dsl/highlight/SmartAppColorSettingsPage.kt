package ru.sber.smartapp.dsl.highlight

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.intellij.json.JsonLanguage
import javax.swing.Icon

/**
 * Регистрирует страницу «SmartApp DSL» в Settings -> Editor -> Color Scheme,
 * чтобы пользователь мог настроить цвета ключевых слов и структурных ключей.
 * Демо-текст переиспользует встроенный JSON-подсветчик и добавляет наши теги.
 */
class SmartAppColorSettingsPage : ColorSettingsPage {

    override fun getDisplayName(): String = "SmartApp DSL"

    override fun getIcon(): Icon? = null

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    override fun getHighlighter(): SyntaxHighlighter =
        SyntaxHighlighterFactory.getSyntaxHighlighter(JsonLanguage.INSTANCE, null, null)

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> =
        mapOf(
            "kw" to SmartAppTextAttributes.KEYWORD,
            "field" to SmartAppTextAttributes.FIELD,
            "delim" to SmartAppTextAttributes.JINJA_DELIM,
            "var" to SmartAppTextAttributes.JINJA_VAR,
            "op" to SmartAppTextAttributes.JINJA_OP,
            "filter" to SmartAppTextAttributes.JINJA_FILTER,
            "str" to SmartAppTextAttributes.JINJA_STRING,
        )

    override fun getDemoText(): String = """
        {
          "hello_scenario": {
            "<field>type</field>": "<kw>form_filling</kw>",
            "<field>form</field>": "hello_form",
            "<field>actions</field>": [
              {
                "<field>type</field>": "<kw>external</kw>",
                "<field>action</field>": "say_hello",
                "value": "<delim>{{</delim> <var>main_form</var><op>.</op><var>name</var> <filter>|</filter> <filter>default</filter>(<str>''</str>) <delim>}}</delim>"
              }
            ]
          }
        }
    """.trimIndent()

    private companion object {
        val DESCRIPTORS = arrayOf(
            AttributesDescriptor("Ключевое слово (значение type)", SmartAppTextAttributes.KEYWORD),
            AttributesDescriptor("Структурный ключ", SmartAppTextAttributes.FIELD),
            AttributesDescriptor("Jinja:: разделитель", SmartAppTextAttributes.JINJA_DELIM),
            AttributesDescriptor("Jinja:: переменная", SmartAppTextAttributes.JINJA_VAR),
            AttributesDescriptor("Jinja:: оператор (.)", SmartAppTextAttributes.JINJA_OP),
            AttributesDescriptor("Jinja:: фильтр (| и имя)", SmartAppTextAttributes.JINJA_FILTER),
            AttributesDescriptor("Jinja:: строка", SmartAppTextAttributes.JINJA_STRING),
        )
    }
}
