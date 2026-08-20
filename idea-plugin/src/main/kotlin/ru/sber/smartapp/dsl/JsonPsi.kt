package ru.sber.smartapp.dsl

import com.intellij.json.psi.JsonFile
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * Общие помощники работы с JSON PSI для индексов.
 *
 * Платформенный контракт: JSON-парсер при ряде синтаксических ошибок строит
 * частичное дерево с [PsiErrorElement], а не бросает исключение, поэтому guard
 * `try/catch` в индексах сам по себе не спасает. Но не любая невалидность даёт
 * [PsiErrorElement]: **trailing-garbage после корневого значения парсер молча
 * игнорирует** (без PSI-ошибки), достраивая корневой объект и оставляя мусор
 * неприсоединённым suffix'ом. Например, файл `{ "a": {} } unexpected` не содержит
 * [PsiErrorElement] ни в корневом объекте, ни в файле, но синтаксически невалиден.
 * Поэтому [hasError] комбинирует две независимые проверки: на [PsiErrorElement] и
 * на значимый контент после корневого значения.
 */
object JsonPsi {

    /**
     * `true`, если файл содержит синтаксическую ошибку или trailing-контент после
     * корневого значения (что делает весь файл невалидным JSON). Именно по этому
     * предикату индексы должны отбрасывать файл до обхода `propertyList`.
     *
     * API сознательно принимает [JsonFile], а не `PsiElement`: trailing-проверка
     * осмысленна только для JSON-файла целиком, и передача сюда корневого
     * `JsonObject` снова молча отключила бы её, вернув исходную брешь.
     */
    fun hasError(file: JsonFile): Boolean = hasPsiError(file) || hasTrailingContent(file)

    private fun hasPsiError(file: JsonFile): Boolean =
        PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java) != null

    /**
     * `true`, если после корневого значения [JsonFile.getTopLevelValue] остался
     * значимый (не whitespace) контент. Парсер JSON молча игнорирует такой suffix
     * без создания [PsiErrorElement], поэтому без этой проверки валидный ключ из
     * `{ "a": {} } junk` всё равно попал бы в индекс.
     */
    private fun hasTrailingContent(file: JsonFile): Boolean {
        val root = file.topLevelValue ?: return false
        val text = file.text
        // Пропускаем trailing whitespace после корневого значения; любой иной
        // символ означает trailing-контент.
        for (i in root.textRange.endOffset until text.length) {
            if (!text[i].isWhitespace()) return true
        }
        return false
    }
}
