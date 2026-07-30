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
import ru.sber.smartapp.dsl.reference.SmartAppJinjaTokenType.INTERP_OPEN

/**
 * Навешивает ссылки на строковые JSON-литералы в ссылочной позиции SmartApp:
 *  - [SmartAppReference] — обычные кросс-ссылки (значения свойств `form`,
 *    `scenario`, `filler`, …);
 *  - [SmartAppFieldReference] — семантические ссылки на поля формы внутри
 *    интерполяции `{{ main_form.<field> }}` (statement-теги `{% … %}` ссылок не
 *    порождают — только подсветка).
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

                    // Только значения свойств, не ключи.
                    val property = literal.parent
                    if (property != null && property !is com.intellij.json.psi.JsonProperty) return PsiReference.EMPTY_ARRAY
                    if (property is com.intellij.json.psi.JsonProperty && property.value !== literal) return PsiReference.EMPTY_ARRAY

                    // Сначала — обычная ссылочная позиция (без Jinja).
                    if (!isJinja(literal.value)) {
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
     * Навешивает [SmartAppFieldReference] на каждое вхождение `main_form.<id>`
     * внутри интерполяций `{{ … }}`. Целевая форма — из
     * [SmartAppFieldRef.targetFormOf]; если неизвестна (динамический `form`) —
     * ссылка не создаётся (нет ложного WARNING). Диапазон ссылки — только на
     * имя поля, в координатах элемента (decoded→raw→absolute).
     */
    private fun fieldReferences(literal: JsonStringLiteral): Array<PsiReference> {
        val form = SmartAppFieldRef.targetFormOf(literal) ?: return PsiReference.EMPTY_ARRAY
        val raw = rawText(literal) ?: return PsiReference.EMPTY_ARRAY
        val decoded = JsonStringLiteralDecoder.decode(raw)

        val refs = ArrayList<PsiReference>()
        for (candidate in SmartAppJinjaLexer.fieldCandidates(decoded.text)) {
            val rawStart = decoded.decodedToRaw[candidate.fieldRange.startOffset]
            val rawEnd = decoded.decodedToRaw[candidate.fieldRange.endOffset]
            // В координатах элемента: +1 за открывающую JSON-кавычку.
            val rangeInElement = TextRange(rawStart + 1, rawEnd + 1)
            refs.add(SmartAppFieldReference(literal, rangeInElement, FormFieldRef(form, candidate.field)))
        }
        return if (refs.isEmpty()) PsiReference.EMPTY_ARRAY else refs.toTypedArray()
    }

    companion object {
        fun isJinja(text: String): Boolean = text.contains("{{") || text.contains("{%")

        /**
         * Raw-содержимое литерала (без крайних JSON-кавычек). Делегирует в
         * [JsonStringLiteralDecoder.rawText]; сохранено для обратной совместимости
         * caller'ов.
         */
        internal fun rawText(literal: JsonStringLiteral): String? =
            JsonStringLiteralDecoder.rawText(literal)
    }
}
