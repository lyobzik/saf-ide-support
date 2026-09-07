package ru.sber.smartapp.dsl.reference

/**
 * Грамматика идентификатора Jinja — единственное определение на весь плагин.
 *
 * Зовут её двое: лексер ([SmartAppJinjaLexer]) при токенизации выражения и
 * фильтр имён модели пользователя. Две копии разошлись бы — и уже расходились:
 * плагин звал `Character.isJavaIdentifierStart/Part`, а порт в расширении
 * приближал их диапазонами `[A-Za-z_$À-￿]`, по которым `×` (U+00D7),
 * `÷` (U+00F7) и одиночный суррогат оказывались идентификаторами.
 *
 * Определение — через **категории Unicode**, а не через удобные методы
 * платформы: они у платформ разные (`str.isdigit()` в Python принимает `²`
 * категории `No`, а `Character.isDigit` и `\p{Nd}` — нет), и `$` в Java
 * идентификатор, хотя в именах Python-атрибутов не встречается.
 *
 * Область определения — **только BMP**. Символ вне BMP (`𐐀`, U+10400)
 * идентификатором не является по контракту: обход идёт по [Char], то есть по
 * UTF-16-единицам, и половинки суррогатной пары (категория `Cs`) не проходят
 * ни одну проверку — как и в порте расширения.
 */
object SmartAppIdentifiers {

    private val LETTERS = setOf(
        CharCategory.UPPERCASE_LETTER,
        CharCategory.LOWERCASE_LETTER,
        CharCategory.TITLECASE_LETTER,
        CharCategory.MODIFIER_LETTER,
        CharCategory.OTHER_LETTER,
    )

    /** Начало идентификатора: `_` или буква (категория `L*`). */
    fun isIdentifierStart(c: Char): Boolean = c == '_' || c.category in LETTERS

    /** Продолжение идентификатора: начало плюс десятичная цифра (категория `Nd`). */
    fun isIdentifierPart(c: Char): Boolean =
        isIdentifierStart(c) || c.category == CharCategory.DECIMAL_DIGIT_NUMBER

    /**
     * Адресуемо ли [name] как один сегмент Jinja (`user.<name>`).
     *
     * Обход — по [Char], как в лексере: имя из символов вне BMP адресуемым не
     * считается.
     */
    fun isAddressableName(name: String): Boolean {
        if (name.isEmpty()) return false
        if (!isIdentifierStart(name[0])) return false
        for (i in 1 until name.length) {
            if (!isIdentifierPart(name[i])) return false
        }
        return true
    }
}
