package ru.sber.smartapp.dsl.index

import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.DataInputOutputUtil
import java.io.DataInput
import java.io.DataOutput

/**
 * Формат хранения [SmartAppDefinitionValue]: var-int количество, за ним столько
 * же var-int смещений. [SERIALIZATION_VERSION] входит в версию индекса, поэтому
 * изменение формата принудительно пересобирает индекс.
 */
object SmartAppDefinitionExternalizer : DataExternalizer<SmartAppDefinitionValue> {

    const val SERIALIZATION_VERSION = 1

    override fun save(out: DataOutput, value: SmartAppDefinitionValue) {
        DataInputOutputUtil.writeINT(out, value.offsets.size)
        for (offset in value.offsets) {
            DataInputOutputUtil.writeINT(out, offset)
        }
    }

    override fun read(input: DataInput): SmartAppDefinitionValue {
        val size = DataInputOutputUtil.readINT(input)
        val offsets = ArrayList<Int>(size)
        repeat(size) {
            offsets.add(DataInputOutputUtil.readINT(input))
        }
        return SmartAppDefinitionValue(offsets)
    }
}
