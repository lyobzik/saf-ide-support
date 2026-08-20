package ru.sber.smartapp.dsl.reference

import com.intellij.util.io.KeyDescriptor
import com.intellij.util.io.IOUtil
import java.io.DataInput
import java.io.DataOutput

/**
 * Однозначная сериализация [FormFieldRef] в file-based индекс: две length-prefixed
 * UTF-строки. Length-prefix устраняет неоднозначность разделителя: имена форм и
 * полей могут содержать `:`, `.` и прочие символы, поэтому строковая конкатенация
 * с разделителем коллидировала бы (например, `FormFieldRef("a", "b:c")` и
 * `FormFieldRef("a:b", "c")`).
 */
object FormFieldRefDescriptor : KeyDescriptor<FormFieldRef> {

    override fun save(out: DataOutput, value: FormFieldRef) {
        IOUtil.writeUTF(out, value.form)
        IOUtil.writeUTF(out, value.field)
    }

    override fun read(input: DataInput): FormFieldRef =
        FormFieldRef(form = IOUtil.readUTF(input), field = IOUtil.readUTF(input))

    override fun getHashCode(value: FormFieldRef): Int = value.hashCode()

    override fun isEqual(val1: FormFieldRef?, val2: FormFieldRef?): Boolean = val1 == val2
}
