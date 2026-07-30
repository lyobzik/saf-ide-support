package ru.sber.smartapp.dsl.reference

import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.util.ProcessingContext
import ru.sber.smartapp.dsl.SmartAppFiles
import ru.sber.smartapp.dsl.reference.SmartAppJinjaTokenType.DELIM_CLOSE
import ru.sber.smartapp.dsl.reference.SmartAppJinjaTokenType.DELIM_OPEN
import ru.sber.smartapp.dsl.reference.SmartAppJinjaTokenType.STRING
import ru.sber.smartapp.dsl.reference.SmartAppJinjaTokenType.VAR

/**
 * Навешивает ссылки на строковые JSON-литералы в ссылочной позиции SmartApp:
 *  - [SmartAppReference] — обычные кросс-ссылки (значения свойств `form`,
 *    `scenario`, `filler`, …);
 *  - [SmartAppFieldReference] — семантические ссылки на поля формы внутри Jinja
 *    `{{ main_form.<field> }}`.
 *
 * Jinja-значения для **обычных** ссылочных позиций по-прежнему пропускаются
 * (`{{ main_form.name }}` в `"form"` не резолвится как форма — это динамический
 * выбор, см. SmartAppFieldRef.targetFormOf). Но если в значении встречается
 * `main_form.<id>`, на имя поля навешивается отдельная поддиапазонная ссылка.
 * Прочие Jinja-конструкции (фильтры, циклы, `{% %}`, `"main_form.x"` строкой
 * внутри `{{ }}`) ссылок не порождают.
 */
class SmartAppReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(JsonStringLiteral::class.java),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(
                    element: PsiElement,
                    context: ProcessingContext,
                ): Array<PsiReference> {
                    val literal = element as? JsonStringLiteral ?: return PsiReference.EMPTY_ARRAY
                    if (SmartAppFiles.kindOf(literal.containingFile) == null) return PsiReference.EMPTY_ARRAY

                    // Сначала — обычная ссылочная позиция (без Jinja).
                    if (!isJinja(literal.value)) {
                        if (!SmartAppRefRules.isReference(literal)) return PsiReference.EMPTY_ARRAY
                        return arrayOf(SmartAppReference(literal))
                    }

                    // Иначе — ищем семантические ссылки на поля формы внутри Jinja.
                    return fieldReferences(literal)
                }
            },
        )
    }

    /**
     * Находит все вхождения `main_form.<id>` внутри парных `{{ … }}` (вне
     * строковых литералов Jinja) и навешивает на имя поля [SmartAppFieldReference]
     * с диапазоном только на имя поля. Целевая форма берётся из
     * [SmartAppFieldRef.targetFormOf]; если форма неизвестна — ссылка не
     * создаётся (нет ложного WARNING).
     */
    private fun fieldReferences(literal: JsonStringLiteral): Array<PsiReference> {
        val form = SmartAppFieldRef.targetFormOf(literal) ?: return PsiReference.EMPTY_ARRAY
        val raw = rawText(literal) ?: return PsiReference.EMPTY_ARRAY
        val tokens = SmartAppJinjaLexer.tokenize(raw)
        val refs = ArrayList<PsiReference>()

        var insideExpr = false
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            when (t.type) {
                DELIM_OPEN -> insideExpr = true
                DELIM_CLOSE -> insideExpr = false
                VAR -> {
                    // Шаблон: VAR(main_form) DOT VAR(<id>) внутри выражения.
                    if (insideExpr && raw.substring(t.range.startOffset, t.range.endOffset) == "main_form") {
                        val dot = tokens.getOrNull(i + 1)
                        val field = tokens.getOrNull(i + 2)
                        if (dot != null && field != null &&
                            dot.type == SmartAppJinjaTokenType.DOT && field.type == VAR
                        ) {
                            val fieldName = raw.substring(field.range.startOffset, field.range.endOffset)
                            val rangeInElement = shiftToElement(field.range)
                            refs.add(SmartAppFieldReference(literal, rangeInElement, FormFieldRef(form, fieldName)))
                            i += 2 // пропускаем DOT и VAR(field)
                        }
                    }
                }
                STRING -> Unit // идентификаторы внутри строк Jinja игнорируем
                else -> Unit
            }
            i++
        }
        return if (refs.isEmpty()) PsiReference.EMPTY_ARRAY else refs.toTypedArray()
    }

    companion object {
        fun isJinja(text: String): Boolean = text.contains("{{") || text.contains("{%")

        /**
         * Raw-содержимое литерала (без крайних JSON-кавычек) или `null`, если
         * литерал слишком короткий/нестандартный. Работает по исходному тексту,
         * включая escape-последовательности — см. план, шаг 5 (диапазоны по raw).
         */
        internal fun rawText(literal: JsonStringLiteral): String? {
            val text = literal.text
            if (text.length < 2) return null
            if (text.first() != '"' || text.last() != '"') return null
            return text.substring(1, text.length - 1)
        }

        /**
         * Перевод raw-диапазона (внутри литерала без кавычек) в координаты
         * элемента: +1 за открывающую JSON-кавычку.
         */
        internal fun shiftToElement(rawRange: TextRange): TextRange =
            TextRange(rawRange.startOffset + 1, rawRange.endOffset + 1)
    }
}
