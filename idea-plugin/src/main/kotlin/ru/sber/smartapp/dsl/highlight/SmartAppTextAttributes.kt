package ru.sber.smartapp.dsl.highlight

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey

/**
 * Ключи текстовых атрибутов, используемые аннотатором SmartApp DSL.
 *
 * Собственные цвета по умолчанию поставляются схемами
 * `resources/colorSchemes/SmartApp{Default,Darcula}.xml` (см.
 * `additionalTextAttributes` в `plugin.xml`) — на одном наследовании подсветка
 * не видна: `BRACES`, `DOT` и `OPERATION_SIGN` собственного цвета в схемах
 * платформы не имеют, а silent-аннотация при этом перекрывает цвет строки JSON.
 *
 * Fallback-ключи ниже работают в темах, которые не наследуют наши схемы,
 * поэтому среди них не должно быть бесцветных. Пользователь может переопределить
 * любой ключ на странице цветов «SmartApp DSL».
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
        DefaultLanguageHighlighterColors.KEYWORD,
    )

    /**
     * Переменная/идентификатор внутри Jinja (`main_form`, имя поля). Fallback —
     * не `IDENTIFIER`: его цвет в схемах платформы совпадает с цветом обычного
     * текста, и переменная переставала отличаться от строки, в которой стоит.
     */
    val JINJA_VAR: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "SMARTAPP_JINJA_VAR",
        DefaultLanguageHighlighterColors.INSTANCE_FIELD,
    )

    /** Оператор `.` (доступ к полю объекта) внутри Jinja. */
    val JINJA_OP: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "SMARTAPP_JINJA_OP",
        DefaultLanguageHighlighterColors.KEYWORD,
    )

    /** Оператор фильтра `|` и имя фильтра внутри Jinja. */
    val JINJA_FILTER: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "SMARTAPP_JINJA_FILTER",
        DefaultLanguageHighlighterColors.METADATA,
    )

    /** Строковый литерал внутри Jinja (`"…"`) с escape-последовательностями. */
    val JINJA_STRING: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "SMARTAPP_JINJA_STRING",
        DefaultLanguageHighlighterColors.STRING,
    )
}
