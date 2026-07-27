package ru.sber.smartapp.dsl.index

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Round-trip [SmartAppDefinitionExternalizer] без платформы: проверка, что
 * `save`/`read` сохраняют состав смещений и их порядок, включая var-int
 * кодировку значений > 127 (`DataInputOutputUtil.writeINT`).
 *
 * Приватный `SmartAppNameIndex.NamesExternalizer` недоступен для прямого
 * unit-теста и покрывается косвенно в `SmartAppIndexTest.testNameIndexRoundTripViaPlatform`.
 */
class SmartAppDefinitionExternalizerTest {

    @Test
    fun testExternalizerRoundTripEmpty() {
        assertRoundTrip(SmartAppDefinitionValue(emptyList()))
    }

    @Test
    fun testExternalizerRoundTripSingleOffset() {
        assertRoundTrip(SmartAppDefinitionValue(listOf(42)))
    }

    @Test
    fun testExternalizerRoundTripMultipleOffsets() {
        // 0, 128, 65536 — значения, проверяющие var-int кодировку (одно- и
        // многобайтовые формы DataInputOutputUtil.writeINT).
        assertRoundTrip(SmartAppDefinitionValue(listOf(0, 128, 65536)))
    }

    @Test
    fun testExternalizerRoundTripPreservesOrder() {
        assertRoundTrip(SmartAppDefinitionValue(listOf(10, 5, 20)))
    }

    private fun assertRoundTrip(value: SmartAppDefinitionValue) {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { SmartAppDefinitionExternalizer.save(it, value) }
        val bais = ByteArrayInputStream(baos.toByteArray())
        val restored = DataInputStream(bais).use { SmartAppDefinitionExternalizer.read(it) }
        assertEquals("round-trip должен сохранять значение", value, restored)
    }
}
