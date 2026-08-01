package ru.sber.smartapp.dsl.highlight

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey

/**
 * Ключи текстовых атрибутов, используемые аннотатором SmartApp DSL. Значения по
 * умолчанию наследуются от стандартных языковых цветов, чтобы подсветка вписалась
 * в любую тему; пользователь может переопределить их на странице цветов
 * «SmartApp DSL».
 */
object SmartAppTextAttributes {

    val KEYWORD: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "SMARTAPP_KEYWORD",
        DefaultLanguageHighlighterColors.KEYWORD,
    )

    val FIELD: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "SMARTAPP_FIELD",
        DefaultLanguageHighlighterColors.INSTANCE_FIELD,
    )

    /** `{{`, `}}`, `{%`, `%}` — разделители Jinja-выражений. */
    val JINJA_DELIM: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "SMARTAPP_JINJA_DELIM",
        DefaultLanguageHighlighterColors.BRACES,
    )

    /** Переменная/идентификатор внутри Jinja (`main_form`, имя поля). */
    val JINJA_VAR: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "SMARTAPP_JINJA_VAR",
        DefaultLanguageHighlighterColors.IDENTIFIER,
    )

    /** Оператор `.` (доступ к полю объекта) внутри Jinja. */
    val JINJA_OP: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "SMARTAPP_JINJA_OP",
        DefaultLanguageHighlighterColors.DOT,
    )

    /** Оператор фильтра `|` и имя фильтра внутри Jinja. */
    val JINJA_FILTER: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "SMARTAPP_JINJA_FILTER",
        DefaultLanguageHighlighterColors.OPERATION_SIGN,
    )

    /** Строковый литерал внутри Jinja (`"…"`) с escape-последовательностями. */
    val JINJA_STRING: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "SMARTAPP_JINJA_STRING",
        DefaultLanguageHighlighterColors.STRING,
    )
}
