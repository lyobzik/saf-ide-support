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
}
