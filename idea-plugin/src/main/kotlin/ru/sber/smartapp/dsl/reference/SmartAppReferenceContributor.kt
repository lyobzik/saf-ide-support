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
import ru.sber.smartapp.dsl.reference.JsonStringLiteralDecoder.rawText

/**
 * Навешивает ссылки на строковые JSON-литералы в ссылочной позиции SmartApp:
 *  - [SmartAppReference] — обычные кросс-ссылки (значения свойств `form`,
 *    `scenario`, `filler`, …);
 *  - [SmartAppFieldReference] — семантические ссылки на поля формы внутри
 *    выражений Jinja: и в интерполяциях `{{ … }}`, и в statement-тегах
 *    `{% … %}`;
 *  - [SmartAppFormVariableReference] — ссылка с самой переменной `main_form`
 *    на определение целевой формы.
 *
 * Семантические ссылки создаются только для **значений** свойств (не для
 * JSON-ключей): `"value": "{{ main_form.name }}"` резолвится, а
 * `"{{ main_form.name }}": x` — нет.
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
                    // Только значения JSON (property value или элемент массива),
                    // не ключи свойств.
                    if (!isJsonValue(literal)) return PsiReference.EMPTY_ARRAY

                    // Сначала — обычная ссылочная позиция (без Jinja).
                    if (!isJinja(literal.value)) {
                        // Ссылка на файл шаблона: цель — файл, а не имя сущности.
                        if (SmartAppFileRefRules.isFileReference(literal)) {
                            return arrayOf(SmartAppFileReference(literal))
                        }
                        if (!SmartAppRefRules.isReference(literal)) return PsiReference.EMPTY_ARRAY
                        return arrayOf(SmartAppReference(literal))
                    }

                    // Иначе — семантические ссылки на поля формы внутри интерполяции.
                    return fieldReferences(literal)
                }
            },
        )
    }

    /**
     * Навешивает [SmartAppFormVariableReference] на каждое вхождение
     * `main_form` и [SmartAppFieldReference] — на каждое `main_form.<id>`
     * внутри выражений Jinja (и `{{ … }}`, и `{% … %}`).
     *
     * Целевая форма — из [SmartAppFieldRef.targetFormOf]; если неизвестна
     * (динамический `form`) — ссылки не создаются (нет ложного WARNING).
     * Диапазон ссылки на поле — только имя поля, в координатах элемента
     * (decoded→raw→absolute).
     */
    private fun fieldReferences(literal: JsonStringLiteral): Array<PsiReference> {
        val form = SmartAppFieldRef.targetFormOf(literal) ?: return PsiReference.EMPTY_ARRAY
        val raw = rawText(literal) ?: return PsiReference.EMPTY_ARRAY
        val decoded = JsonStringLiteralDecoder.decode(raw)

        val refs = ArrayList<PsiReference>()
        // Сама переменная `main_form` — тоже ссылка: на определение целевой формы.
        for (range in SmartAppJinjaLexer.formVariableRanges(decoded.text)) {
            refs.add(SmartAppFormVariableReference(literal, elementRange(decoded, range), form))
        }
        for (candidate in SmartAppJinjaLexer.fieldCandidates(decoded.text)) {
            val rangeInElement = elementRange(decoded, candidate.fieldRange)
            refs.add(SmartAppFieldReference(literal, rangeInElement, FormFieldRef(form, candidate.field)))
        }
        return if (refs.isEmpty()) PsiReference.EMPTY_ARRAY else refs.toTypedArray()
    }

    /** Диапазон из decoded-координат в координаты элемента (+1 за кавычку). */
    private fun elementRange(decoded: JsonStringLiteralDecoder.Decoded, range: TextRange): TextRange {
        val rawStart = decoded.decodedToRaw[range.startOffset]
        val rawEnd = decoded.decodedToRaw[range.endOffset]
        return TextRange(rawStart + 1, rawEnd + 1)
    }

    companion object {
        fun isJinja(text: String): Boolean = text.contains("{{") || text.contains("{%")

        /**
         * `true`, если [literal] — значение JSON (значение свойства или элемент
         * массива), а не ключ свойства. Семантика полей и обычные ссылки
         * применяются только к значениям: ключ `"{{ main_form.name }}"` не должен
         * получать ссылку.
         */
        internal fun isJsonValue(literal: JsonStringLiteral): Boolean {
            val parent = literal.parent ?: return true
            // Если literal — ключ свойства (nameElement), это не значение.
            if (parent is com.intellij.json.psi.JsonProperty) {
                return parent.nameElement !== literal
            }
            // Элемент массива и прочие позиции — значения.
            return true
        }

        /**
         * Raw-содержимое литерала (без крайних JSON-кавычек). Делегирует в
         * [JsonStringLiteralDecoder.rawText]; сохранено для обратной совместимости
         * caller'ов.
         */
        internal fun rawText(literal: JsonStringLiteral): String? =
            JsonStringLiteralDecoder.rawText(literal)
    }
}
