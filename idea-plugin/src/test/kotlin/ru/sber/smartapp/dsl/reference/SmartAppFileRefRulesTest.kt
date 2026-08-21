package ru.sber.smartapp.dsl.reference

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * Поиск файла шаблона по относительному пути — на **настоящей** файловой системе.
 *
 * Фикстуры `myFixture` живут в in-memory ФС, которая регистрозависима всегда,
 * поэтому кейс корпуса фиксирует контракт, но регрессию поймать не может:
 * расхождение видно лишь там, где ФС регистронезависима (macOS).
 */
class SmartAppFileRefRulesTest : BasePlatformTestCase() {

    private lateinit var dir: File

    override fun setUp() {
        super.setUp()
        dir = FileUtil.createTempDirectory("smartapp-file-refs", null)
        File(dir, "templates/nested").mkdirs()
        File(dir, "templates/items.jinja2").writeText("")
        File(dir, "templates/nested/deep.jinja2").writeText("")
    }

    override fun tearDown() {
        try {
            FileUtil.delete(dir)
        } finally {
            super.tearDown()
        }
    }

    private fun root(): VirtualFile =
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir)
            ?: error("temp directory is not visible in VFS")

    fun testExactPathResolves() {
        assertEquals(
            "items.jinja2",
            SmartAppFileRefRules.findByPath(root(), "templates/items.jinja2")?.name,
        )
        assertEquals(
            "deep.jinja2",
            SmartAppFileRefRules.findByPath(root(), "templates/nested/deep.jinja2")?.name,
        )
    }

    fun testWrongCaseInFileNameDoesNotResolve() {
        assertNull(SmartAppFileRefRules.findByPath(root(), "templates/Items.JINJA2"))
    }

    fun testWrongCaseInDirectorySegmentDoesNotResolve() {
        // Отдельный кейс: сверка только последнего сегмента прошла бы предыдущий
        // тест и провалила бы этот.
        assertNull(SmartAppFileRefRules.findByPath(root(), "templates/Nested/deep.jinja2"))
    }

    fun testDirectoryIsNotATarget() {
        assertNull(SmartAppFileRefRules.findByPath(root(), "templates/nested"))
    }

    fun testMissingFileDoesNotResolve() {
        assertNull(SmartAppFileRefRules.findByPath(root(), "templates/absent.jinja2"))
    }

    /**
     * Характеризация платформы: зачем нужна собственная сверка имён. На
     * регистронезависимой ФС `findFileByRelativePath` находит файл по другому
     * регистру — и без [SmartAppFileRefRules.findByPath] диагностика зависела бы
     * от машины разработчика.
     */
    fun testPlatformLookupFollowsFileSystemCaseSensitivity() {
        val found = root().findFileByRelativePath("templates/Items.JINJA2")
        if (found != null) assertEquals("items.jinja2", found.name)
    }
}
