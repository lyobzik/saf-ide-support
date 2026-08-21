package ru.sber.smartapp.dsl.highlight

import com.intellij.openapi.editor.colors.TextAttributesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Контракт видимости подсветки: у каждого ключа [SmartAppTextAttributes] должен
 * быть собственный цвет в обеих поставляемых схемах.
 *
 * Проверка «атрибут навешен» уже есть в тестах аннотатора, и её мало: атрибут,
 * унаследованный от бесцветного ключа платформы, рисуется цветом обычного текста
 * и вдобавок перекрывает цвет строки JSON — подсветка исчезает, а тесты остаются
 * зелёными. Здесь фиксируется то, что действительно проверяемо автоматически:
 * цвет задан явно и не потерян при добавлении нового ключа.
 */
class SmartAppColorSchemesTest {

    @Test
    fun everyKeyHasForegroundInBothSchemes() {
        val keys = declaredKeys().map { it.externalName }.sorted()
        assertTrue("ключи атрибутов не найдены — сломалась рефлексия теста", keys.isNotEmpty())

        for ((_, scheme) in SCHEMES) {
            val foregrounds = foregroundsOf(scheme)
            assertEquals(
                "набор ключей в $scheme разошёлся с SmartAppTextAttributes",
                keys,
                foregrounds.keys.sorted(),
            )
            for ((key, value) in foregrounds) {
                assertTrue(
                    "$key в $scheme: FOREGROUND '$value' не похож на цвет",
                    value.matches(Regex("[0-9a-fA-F]{1,6}")),
                )
            }
        }
    }

    /** Ключи объекта [SmartAppTextAttributes] — через рефлексию, чтобы новый ключ не забыли. */
    private fun declaredKeys(): List<TextAttributesKey> =
        SmartAppTextAttributes::class.java.declaredFields
            .filter { TextAttributesKey::class.java.isAssignableFrom(it.type) }
            .map { field ->
                field.isAccessible = true
                field.get(SmartAppTextAttributes) as TextAttributesKey
            }

    /** `externalName` → значение FOREGROUND из файла схемы. */
    private fun foregroundsOf(resource: String): Map<String, String> {
        val stream = javaClass.getResourceAsStream(resource)
        assertNotNull("схема $resource не попала в ресурсы плагина", stream)

        val document = stream!!.use {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it)
        }
        val result = LinkedHashMap<String, String>()
        val options = document.getElementsByTagName("option")
        for (i in 0 until options.length) {
            val option = options.item(i) as Element
            val name = option.getAttribute("name")
            // Внешний option — это ключ атрибута; цвет лежит во вложенном <value>.
            val values = option.getElementsByTagName("option")
            for (j in 0 until values.length) {
                val inner = values.item(j) as Element
                if (inner.getAttribute("name") == "FOREGROUND") {
                    result[name] = inner.getAttribute("value")
                }
            }
        }
        return result
    }

    /**
     * Файл схемы сам по себе ничего не красит: без `<additionalTextAttributes>`
     * в дескрипторе платформа его не увидит, тесты останутся зелёными, а
     * подсветка в IDE снова пропадёт.
     */
    @Test
    fun everySchemeIsRegisteredInPluginXml() {
        val descriptor = javaClass.getResourceAsStream("/META-INF/plugin.xml")
        assertNotNull("plugin.xml не найден в ресурсах", descriptor)
        val document = descriptor!!.use {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it)
        }

        val registered = HashMap<String, String>()
        val nodes = document.getElementsByTagName("additionalTextAttributes")
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as Element
            registered[element.getAttribute("scheme")] = element.getAttribute("file")
        }

        for ((scheme, resource) in SCHEMES) {
            assertEquals(
                "схема '$scheme' не подключена через additionalTextAttributes",
                resource.removePrefix("/"),
                registered[scheme],
            )
        }
    }

    private companion object {
        /** Схема платформы -> ресурс с нашими цветами для неё. */
        val SCHEMES = listOf(
            "Default" to "/colorSchemes/SmartAppDefault.xml",
            "Darcula" to "/colorSchemes/SmartAppDarcula.xml",
        )
    }
}
