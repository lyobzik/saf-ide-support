package ru.sber.smartapp.dsl.reference

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files

/**
 * Сбор путей файлов для автодополнения — на настоящей файловой системе:
 * симлинки и регистр каталогов на in-memory ФС фикстур не воспроизводятся.
 */
class SmartAppTemplateFilesTest : BasePlatformTestCase() {

    private lateinit var dir: File

    override fun setUp() {
        super.setUp()
        dir = FileUtil.createTempDirectory("smartapp-template-files", null)
        File(dir, "templates/nested").mkdirs()
        File(dir, "partials").mkdirs()
        File(dir, "templates/items.jinja2").writeText("")
        File(dir, "templates/nested/deep.jinja2").writeText("")
        File(dir, "templates/shared.jinja2").writeText("")
        File(dir, "partials/shared.jinja2").writeText("")
        File(dir, "partials/only_partials.jinja2").writeText("")
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

    fun testCollectsFilesWithNestedPaths() {
        val paths = SmartAppTemplateFiles.pathsIn(root(), listOf("templates"))
        assertEquals(
            listOf("items.jinja2", "nested/deep.jinja2", "shared.jinja2"),
            paths.sorted(),
        )
    }

    fun testSeveralDirectoriesAreUnitedAndDeduplicated() {
        val paths = SmartAppTemplateFiles.pathsIn(root(), listOf("templates", "partials"))
        assertEquals(
            listOf("items.jinja2", "nested/deep.jinja2", "only_partials.jinja2", "shared.jinja2"),
            paths.sorted(),
        )
        // Одинаковый путь есть в обоих каталогах — вариант обязан быть один.
        assertEquals(1, paths.count { it == "shared.jinja2" })
    }

    fun testEveryPathResolvesInOneOfTheDirectories() {
        // Инвариант вместо «побеждает первый каталог»: у пути в completion
        // приоритета нет (каталог в значение не входит), а вот резолв обязан
        // найти файл для каждого предложенного варианта.
        val dirs = listOf("templates", "partials")
        for (path in SmartAppTemplateFiles.pathsIn(root(), dirs)) {
            assertNotNull(
                "предложенный путь '$path' не резолвится ни в одном каталоге",
                dirs.firstNotNullOfOrNull { SmartAppFileRefRules.findByPath(root(), "$it/$path") },
            )
        }
    }

    fun testUnknownDirectoryGivesNothing() {
        assertEquals(emptyList<String>(), SmartAppTemplateFiles.pathsIn(root(), listOf("absent")))
    }

    fun testDirectoryWithWrongCaseIsNotOurDirectory() {
        assertEquals(emptyList<String>(), SmartAppTemplateFiles.pathsIn(root(), listOf("Templates")))
    }

    fun testSymlinkCycleTerminates() {
        // Каталог-ссылка на собственного предка: без обрыва по каноническому пути
        // обход не завершится вовсе.
        Files.createSymbolicLink(
            File(dir, "templates/loop").toPath(),
            File(dir, "templates").toPath(),
        )
        val paths = SmartAppTemplateFiles.pathsIn(root(), listOf("templates"))
        assertTrue("файлы каталога собраны", paths.contains("items.jinja2"))
        assertTrue(
            "цикл не должен порождать бесконечные пути: $paths",
            paths.none { it.startsWith("loop/loop/") },
        )
    }

    fun testSymlinkedFileIsCollected() {
        val target = File(dir, "outside.jinja2")
        target.writeText("")
        Files.createSymbolicLink(File(dir, "templates/linked.jinja2").toPath(), target.toPath())
        assertTrue(
            SmartAppTemplateFiles.pathsIn(root(), listOf("templates")).contains("linked.jinja2"),
        )
    }
}
