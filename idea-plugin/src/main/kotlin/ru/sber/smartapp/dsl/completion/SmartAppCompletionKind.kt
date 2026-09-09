package ru.sber.smartapp.dsl.completion

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.openapi.util.Key

/**
 * Вид предложенного варианта — категория, а не подпись в списке.
 *
 * Категория входит в общий контракт поведения (`kind` в `shared/fixtures`):
 * в одной и той же позиции обе реализации обязаны предлагать варианты одного
 * вида, иначе «поле» и «имя сущности» с совпадающей меткой означают разное.
 * В расширении вид возвращает `completionAt`, здесь его негде вернуть — поэтому
 * он висит на самом элементе.
 */
enum class SmartAppCompletionKind {
    KEYWORD,
    NAME,
    FIELD,
    FILE,
    VARIABLE,
    USER_FIELD,
    ;

    companion object {
        val KEY: Key<SmartAppCompletionKind> = Key.create("smartapp.dsl.completion.kind")

        /** Вид варианта [element], если его предложил этот плагин. */
        fun of(element: LookupElement): SmartAppCompletionKind? = element.getUserData(KEY)
    }
}
