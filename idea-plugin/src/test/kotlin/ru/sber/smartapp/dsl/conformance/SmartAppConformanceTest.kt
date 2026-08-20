package ru.sber.smartapp.dsl.conformance

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import ru.sber.smartapp.dsl.SmartAppRefKind
import ru.sber.smartapp.dsl.SmartAppScopes
import ru.sber.smartapp.dsl.highlight.SmartAppTextAttributes
import ru.sber.smartapp.dsl.index.SmartAppNameIndex
import ru.sber.smartapp.dsl.reference.SmartAppFieldReference
import ru.sber.smartapp.dsl.reference.SmartAppReference
import java.io.File

/**
 * Прогон общего conformance-корпуса `shared/fixtures` на стороне IDEA.
 *
 * Тот же корпус гоняет VS Code-расширение. Общий `rules.json` фиксирует только
 * данные; совпадение **поведения** двух независимых реализаций держится именно
 * на этом тесте: любое расхождение в резолве, диагностике, автодополнении или
 * подсветке ломает одну из сторон.
 *
 * Все фикстуры добавляются в один проект, но каждая — в собственный каталог
 * `<fixture>/static/references/...`, поэтому изоляция наборов (`SmartAppScopes`)
 * не даёт им видеть друг друга.
 */
class SmartAppConformanceTest : BasePlatformTestCase() {

    private lateinit var fixtures: List<Fixture>

    override fun setUp() {
        super.setUp()
        fixtures = loadFixtures()
        for (fixture in fixtures) {
            for ((path, text) in fixture.files) {
                myFixture.addFileToProject("${fixture.name}/$path", text)
            }
        }
    }

    fun testCorpusIsNotEmpty() {
        // Молча пропущенный корпус не должен выглядеть зелёным.
        assertTrue("conformance-корпус пуст: ${sharedDir()}", fixtures.isNotEmpty())
        assertTrue(
            "каждая фикстура обязана описывать себя",
            fixtures.all { it.expected.get("description")?.asString?.isNotEmpty() == true },
        )
    }

    /**
     * Секция, которую раннер не умеет проверять, — это молчаливо непроверенный
     * контракт: фикстура что-то обещает, а сторона IDEA этого не смотрит.
     * Поэтому неизвестная секция валит тест, а не игнорируется.
     */
    fun testEverySectionIsSupported() {
        for (fixture in fixtures) {
            val unknown = fixture.expected.keySet() - SUPPORTED_SECTIONS
            assertTrue(
                "фикстура '${fixture.name}' объявляет секции $unknown, " +
                    "которые conformance-раннер IDEA не проверяет",
                unknown.isEmpty(),
            )
        }
    }

    fun testDefinitions() {
        forEachCheck("definitions") { fixture, check ->
            val path = check["file"].asString
            val text = fixture.text(path)
            val offset = anchorOffset(text, check)
            val file = psiFile(fixture, path)

            val expected = check.getAsJsonArray("targets")
                .map { expectedDescribed(fixture, it.asJsonObject) }
            val actual = resolveAt(file, offset)
                .mapIndexed { index, element ->
                    val described = describe(fixture, element)
                    expected.getOrNull(index)?.let { matching(described, it) } ?: described
                }
            assertEquals(
                "definition в '${fixture.name}' по якорю '${check["anchor"].asString}'",
                expected,
                actual,
            )

            // Идентичность определения: имя плюс ordinal среди одноимённых ключей.
            val withOrdinals = check.getAsJsonArray("targets")
                .map { it.asJsonObject }
                .filter { it.has("ordinal") }
            if (withOrdinals.isNotEmpty()) {
                val actualIds = resolveAt(file, offset).map { element ->
                    val property = element as JsonProperty
                    property.name to ordinalOf(property)
                }
                val expectedIds = withOrdinals.map { it["name"].asString to it["ordinal"].asInt }
                assertEquals("ordinal определений в '${fixture.name}'", expectedIds, actualIds)
            }
        }
    }

    fun testReferences() {
        forEachCheck("references") { fixture, check ->
            val path = check["file"].asString
            val text = fixture.text(path)
            val offset = anchorOffset(text, check)
            val file = psiFile(fixture, path)

            // Каретка в корпусе стоит на определении: сущности или поля формы.
            val definition = PsiTreeUtil.findElementOfClassAtOffset(
                file, offset, JsonProperty::class.java, false,
            ) ?: error("под кареткой нет определения в '${fixture.name}/$path'")

            val usages = myFixture.findUsages(definition)
                // Платформа подмешивает к результатам своё: текстовые вхождения
                // слова по всему проекту и одноимённые JSON-ключи из других файлов
                // (их приносит JsonPropertyNameReference самого JSON-плагина).
                // Контракт — только ссылки нашего плагина, поэтому проверяем, что
                // на диапазоне usage висит именно SmartApp-ссылка. По
                // `usage.reference` отобрать нельзя: у наших usages он null.
                .filterNot { it.isNonCodeUsage }
                .filter { isSmartAppUsage(it) }
                .mapNotNull { usage -> describeUsage(fixture, usage) }

            // includeDeclaration — часть контракта секции: при true к результату
            // добавляется само определение, как это делает ядро расширения.
            val includeDeclaration = check.get("includeDeclaration")?.asBoolean ?: false
            val expected = check.getAsJsonArray("targets")
                .map { expectedDescribed(fixture, it.asJsonObject) }
                .sortedWith(compareBy({ it.file }, { it.line }, { it.column ?: 0 }, { it.rangeText }))
            val declaration = if (includeDeclaration) listOf(describe(fixture, definition)) else emptyList()
            val actual = (usages + declaration)
                .sortedWith(compareBy({ it.file }, { it.line }, { it.column ?: 0 }, { it.rangeText }))
                .mapIndexed { index, described ->
                    expected.getOrNull(index)?.let { matching(described, it) } ?: described
                }

            assertEquals(
                "usages в '${fixture.name}' по якорю '${check["anchor"].asString}'",
                expected,
                actual,
            )
        }
    }

    fun testDiagnostics() {
        forEachCheck("diagnostics") { fixture, check ->
            val path = check["file"].asString
            val text = fixture.text(path)
            val file = psiFile(fixture, path)
            myFixture.openFileInEditor(file.virtualFile)

            val actual = myFixture.doHighlighting()
                .filter { it.description?.startsWith(UNRESOLVED_PREFIX) == true }
                .sortedBy { it.startOffset }
                .map {
                    Diagnostic(
                        message = it.description,
                        line = lineOf(text, it.startOffset),
                        rangeText = text.substring(it.startOffset, it.endOffset),
                    )
                }
            val expected = check.getAsJsonArray("items").map {
                val item = it.asJsonObject
                Diagnostic(
                    message = item["message"].asString,
                    line = item["line"].asInt,
                    rangeText = item["rangeText"].asString,
                )
            }
            assertEquals("diagnostics в '${fixture.name}' для $path", expected, actual)
        }
    }

    fun testCompletion() {
        forEachCheck("completion") { fixture, check ->
            val path = check["file"].asString
            val text = fixture.text(path)
            val offset = anchorOffset(text, check)
            val file = psiFile(fixture, path)

            myFixture.openFileInEditor(file.virtualFile)
            // Единственный вариант платформа вставляет прямо в документ, сдвигая
            // якоря следующих кейсов, — поэтому текст восстанавливается после
            // каждой проверки.
            restoreDocument(text)
            myFixture.editor.caretModel.moveToOffset(offset)
            val elements = myFixture.completeBasic()

            val expected = check.getAsJsonArray("items").map { it.asString }
            // Единственный подходящий вариант платформа вставляет сразу, не
            // показывая lookup: тогда `lookupElementStrings` пуст, и фактически
            // предложенным вариантом является уже вставленный идентификатор.
            val actual = myFixture.lookupElementStrings
                ?: elements?.map { it.lookupString }
                ?: listOf(insertedIdentifier())

            // Сравниваем состав проекции на ожидаемые варианты. Точного равенства
            // всему списку не требуем: JSON-плагин платформы подмешивает свои
            // предложения. Порядок тоже не фиксируем — его задаёт сортировщик
            // lookup'а IDEA, а не наш код.
            assertEquals(
                "completion в '${fixture.name}' по якорю '${check["anchor"].asString}'",
                expected.sorted(),
                actual.filter { it in expected }.sorted(),
            )
            val absent = check.getAsJsonArray("absent")?.map { it.asString } ?: emptyList()
            for (variant in absent) {
                assertFalse(
                    "'$variant' не должен предлагаться в '${fixture.name}' " +
                        "по якорю '${check["anchor"].asString}'",
                    variant in actual,
                )
            }
            restoreDocument(text)
        }
    }

    fun testSemanticTokens() {
        forEachCheck("semanticTokens") { fixture, check ->
            val path = check["file"].asString
            val text = fixture.text(path)
            val file = psiFile(fixture, path)
            myFixture.openFileInEditor(file.virtualFile)

            val attribute = attributeOf(check["type"].asString)
            val infos = myFixture.doHighlighting()
                .filter { it.forcedTextAttributesKey == attribute }
                .sortedBy { it.startOffset }

            assertEquals(
                "тексты токенов '${check["type"].asString}' в '${fixture.name}'",
                check.getAsJsonArray("texts").map { it.asString },
                infos.map { text.substring(it.startOffset, it.endOffset) },
            )
            assertEquals(
                "строки токенов '${check["type"].asString}' в '${fixture.name}'",
                check.getAsJsonArray("lines").map { it.asInt },
                infos.map { lineOf(text, it.startOffset) },
            )
        }
    }

    fun testRename() {
        forEachCheck("rename") { fixture, check ->
            val path = check["file"].asString
            val text = fixture.text(path)
            val newName = check["newName"].asString
            val file = psiFile(fixture, path)

            restoreFixtureFiles(fixture)
            myFixture.openFileInEditor(file.virtualFile)
            myFixture.editor.caretModel.moveToOffset(anchorOffset(text, check))
            myFixture.renameElementAtCaretUsingHandler(newName)

            // Сравниваем содержимое файлов **всех** фикстур проекта: так
            // проверяются точные места правок, отсутствие лишних и — главное —
            // отсутствие протечки в чужой набор `static/references`.
            val expectedTexts = applyExpectedEdits(fixture, check.getAsJsonArray("edits"), newName)
            for (other in fixtures) {
                for (otherPath in other.files.keys) {
                    val expectedText = if (other === fixture) {
                        expectedTexts.getValue(otherPath)
                    } else {
                        // Чужая фикстура обязана остаться нетронутой.
                        other.text(otherPath)
                    }
                    assertEquals(
                        "содержимое '${other.name}/$otherPath' после rename " +
                            "'${check["anchor"].asString}' -> '$newName' в '${fixture.name}'",
                        expectedText,
                        currentTextOf(other.name, otherPath),
                    )
                }
            }
            restoreFixtureFiles(fixture)
        }
    }

    /**
     * Ожидаемое содержимое всех файлов фикстуры после переименования: к каждому
     * файлу применяются его правки, остальные обязаны остаться нетронутыми.
     */
    private fun applyExpectedEdits(
        fixture: Fixture,
        edits: com.google.gson.JsonArray,
        newName: String,
    ): Map<String, String> {
        val editsByFile = edits.map { it.asJsonObject }.groupBy { it["file"].asString }
        return fixture.files.keys.associateWith { path ->
            val lines = fixture.text(path).lines().toMutableList()
            // Правки применяем снизу вверх и справа налево: замена меняет длину
            // строки, поэтому колонки правок левее сместились бы, примени мы их
            // в прямом порядке.
            val ordered = editsByFile[path].orEmpty().sortedWith(
                compareByDescending<JsonObject> { it["line"].asInt }
                    .thenByDescending { it.get("column")?.asInt ?: 0 },
            )
            for (edit in ordered) {
                val lineNumber = edit["line"].asInt
                val oldName = edit["rangeText"].asString
                val line = lines[lineNumber]
                val column = edit.get("column")?.asInt

                if (column == null) {
                    // Без колонки правка обязана быть однозначной: иначе не
                    // определить, какое из одинаковых имён на строке меняется.
                    val occurrences = occurrencesInLine(fixture.text(path), lineNumber, oldName)
                    assertEquals(
                        "фикстура '${fixture.name}': в $path строка $lineNumber содержит " +
                            "'$oldName' $occurrences раз(а) — укажите 'column'",
                        1,
                        occurrences,
                    )
                    lines[lineNumber] = line.replaceFirst(oldName, newName)
                } else {
                    assertEquals(
                        "фикстура '${fixture.name}': в $path строка $lineNumber, колонка $column " +
                            "не содержит '$oldName'",
                        oldName,
                        line.substring(column, column + oldName.length),
                    )
                    lines[lineNumber] = line.substring(0, column) + newName +
                        line.substring(column + oldName.length)
                }
            }
            lines.joinToString("\n")
        }
    }

    fun testIndexedNames() {
        forEachCheck("indexedNames") { fixture, check ->
            val kind = SmartAppRefKind.valueOf(check["kind"].asString)
            // Scope — набор references самой фикстуры: все фикстуры живут в
            // одном тестовом проекте и не должны видеть определения друг друга.
            val scope = scopeOf(fixture)
            val expected = check.getAsJsonArray("names").map { it.asString }
            // Порядок обхода файлов у двух реализаций свой — контракт задаёт
            // состав имён, а не их последовательность.
            assertEquals(
                "имена вида ${kind.name} в индексе '${fixture.name}'",
                expected.sorted(),
                SmartAppNameIndex.allNames(project, kind, scope).sorted(),
            )
        }
    }

    // ---- инфраструктура --------------------------------------------------

    /**
     * Ожидаемая позиция из фикстуры. Если `column` не задан, проверяем, что текст
     * встречается в строке ровно один раз — иначе ожидание неоднозначно и кейс
     * обязан уточнить колонку.
     */
    private fun expectedDescribed(fixture: Fixture, json: JsonObject): Described {
        val file = json["file"].asString
        val line = json["line"].asInt
        val rangeText = json["rangeText"].asString
        val column = json.get("column")?.asInt

        if (column == null) {
            val occurrences = occurrencesInLine(fixture.text(file), line, rangeText)
            assertEquals(
                "в '${fixture.name}/$file' строка $line содержит '$rangeText' $occurrences раз(а) — " +
                    "ожидание неоднозначно, укажите 'column'",
                1,
                occurrences,
            )
        }
        return Described(file, line, column, rangeText)
    }

    /** Сколько раз [needle] встречается в строке [line] файла. */
    private fun occurrencesInLine(text: String, line: Int, needle: String): Int {
        val content = text.lines().getOrNull(line) ?: return 0
        var count = 0
        var index = content.indexOf(needle)
        while (index >= 0) {
            count++
            index = content.indexOf(needle, index + 1)
        }
        return count
    }

    /** Приводит фактическую позицию к форме ожидания (с колонкой или без). */
    private fun matching(actual: Described, expected: Described): Described =
        if (expected.column == null) actual.copy(column = null) else actual

    private fun forEachCheck(section: String, check: (Fixture, JsonObject) -> Unit) {
        var executed = 0
        for (fixture in fixtures) {
            val checks = fixture.expected.getAsJsonArray(section) ?: continue
            for (entry in checks) {
                check(fixture, entry.asJsonObject)
                executed++
            }
        }
        // Секция может отсутствовать во всех фикстурах — но если корпус её
        // объявляет, она обязана быть проверена.
        if (fixtures.any { it.expected.has(section) }) {
            assertTrue("проверки секции '$section' не выполнились", executed > 0)
        }
    }

    /** Определения, на которые ведёт позиция [offset]. */
    private fun resolveAt(file: PsiFile, offset: Int): List<PsiElement> {
        val literal = PsiTreeUtil.findElementOfClassAtOffset(
            file, offset, JsonStringLiteral::class.java, false,
        ) ?: return emptyList()

        return literal.references
            .flatMap { reference ->
                when (reference) {
                    is SmartAppReference -> reference.multiResolve(false).mapNotNull { it.element }
                    else -> {
                        // Ссылки на поля формы — поли-вариантные того же контракта.
                        val resolved = reference.resolve()
                        if (resolved != null) listOf(resolved) else emptyList()
                    }
                }
            }
            .ifEmpty { multiResolveFieldReferences(literal, offset) }
    }

    /**
     * Поли-вариантный резолв ссылок на поля формы: у литерала их может быть
     * несколько (по одной на каждое вхождение), поэтому выбирается та, чей
     * диапазон покрывает каретку.
     */
    private fun multiResolveFieldReferences(literal: JsonStringLiteral, offset: Int): List<PsiElement> {
        val inElement = offset - literal.textRange.startOffset
        return literal.references
            .filter { it.rangeInElement.containsOffset(inElement) }
            .flatMap { reference ->
                (reference as? com.intellij.psi.PsiPolyVariantReference)
                    ?.multiResolve(false)
                    ?.mapNotNull { it.element }
                    ?: emptyList()
            }
    }

    private fun psiFile(fixture: Fixture, path: String): PsiFile {
        val virtualFile = myFixture.findFileInTempDir("${fixture.name}/$path")
            ?: error("файл ${fixture.name}/$path не найден в тестовом проекте")
        return myFixture.psiManager.findFile(virtualFile) ?: error("PSI для $path недоступен")
    }

    private fun describe(fixture: Fixture, element: PsiElement): Described {
        val containingPath = element.containingFile.virtualFile.path
        val relative = containingPath.substringAfter("${fixture.name}/")
        val text = fixture.text(relative)
        val range = when (element) {
            is JsonProperty -> element.nameElement.textRange
            else -> element.textRange
        }
        return Described(
            file = relative,
            line = lineOf(text, range.startOffset),
            column = columnOf(text, range.startOffset),
            rangeText = text.substring(range.startOffset, range.endOffset),
        )
    }

    /** Номер строки (0-based) для смещения. */
    private fun lineOf(text: String, offset: Int): Int =
        text.substring(0, offset).count { it == '\n' }

    /** Номер символа в строке (0-based) для смещения. */
    private fun columnOf(text: String, offset: Int): Int =
        offset - (text.lastIndexOf('\n', offset - 1) + 1)

    private fun anchorOffset(text: String, check: JsonObject): Int {
        val anchor = check["anchor"].asString
        val index = text.indexOf(anchor)
        assertTrue("якорь '$anchor' не найден", index >= 0)
        return index + (check.get("anchorOffset")?.asInt ?: 0)
    }

    /** Номер определения среди одноимённых top-level ключей файла. */
    private fun ordinalOf(property: JsonProperty): Int {
        val file = property.containingFile as? JsonFile ?: return 0
        val root = file.topLevelValue as? com.intellij.json.psi.JsonObject ?: return 0
        var ordinal = 0
        for (candidate in root.propertyList) {
            if (candidate === property) return ordinal
            if (candidate.name == property.name) ordinal++
        }
        return ordinal
    }

    private fun attributeOf(type: String) = when (type) {
        "smartappKeyword" -> SmartAppTextAttributes.KEYWORD
        "smartappStructuralKey" -> SmartAppTextAttributes.FIELD
        "smartappJinjaDelimiter" -> SmartAppTextAttributes.JINJA_DELIM
        "smartappJinjaVariable" -> SmartAppTextAttributes.JINJA_VAR
        "smartappJinjaOperator" -> SmartAppTextAttributes.JINJA_OP
        "smartappJinjaFilter" -> SmartAppTextAttributes.JINJA_FILTER
        "smartappJinjaString" -> SmartAppTextAttributes.JINJA_STRING
        else -> error("неизвестный тип семантического токена: $type")
    }

    /**
     * `true`, если использование порождено ссылкой SmartApp DSL, а не
     * платформенной ссылкой JSON-плагина.
     */
    private fun isSmartAppUsage(usage: com.intellij.usageView.UsageInfo): Boolean {
        val element = usage.element ?: return false
        val range = usage.rangeInElement
        return element.references.any { reference ->
            val ours = reference is SmartAppReference || reference is SmartAppFieldReference
            ours && (range == null || reference.rangeInElement == range)
        }
    }

    /**
     * Описание использования. Диапазон берётся из `rangeInElement`: для поля
     * формы ссылка покрывает только имя поля внутри Jinja-литерала, и сравнивать
     * надо именно её, а не весь литерал.
     */
    private fun describeUsage(
        fixture: Fixture,
        usage: com.intellij.usageView.UsageInfo,
    ): Described? {
        val element = usage.element ?: return null
        val path = element.containingFile.virtualFile.path
        // Использование из чужой фикстуры означало бы протечку scope — это не
        // повод молча его отбросить.
        assertTrue(
            "usage из чужой фикстуры: $path (ожидалась '${fixture.name}')",
            path.contains("/${fixture.name}/"),
        )
        val relative = path.substringAfter("${fixture.name}/")
        val text = fixture.text(relative)
        val inElement = usage.rangeInElement
        val start = element.textRange.startOffset + (inElement?.startOffset ?: 0)
        val end = element.textRange.startOffset +
            (inElement?.endOffset ?: element.textRange.length)
        return Described(
            file = relative,
            line = lineOf(text, start),
            column = columnOf(text, start),
            rangeText = text.substring(start, end),
        )
    }

    private fun currentText(fixture: Fixture, path: String): String =
        currentTextOf(fixture.name, path)

    /** Текущий текст файла фикстуры в тестовом проекте. */
    private fun currentTextOf(fixtureName: String, path: String): String {
        val virtualFile = myFixture.findFileInTempDir("$fixtureName/$path")
            ?: error("файл $fixtureName/$path не найден")
        return com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
            .getDocument(virtualFile)?.text
            ?: String(virtualFile.contentsToByteArray(), Charsets.UTF_8)
    }

    /**
     * Возвращает все файлы фикстуры к исходному состоянию: rename правит файлы
     * проекта, и без отката следующие проверки читали бы уже переименованный текст.
     */
    private fun restoreFixtureFiles(fixture: Fixture) {
        val documentManager = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            for ((path, text) in fixture.files) {
                val virtualFile = myFixture.findFileInTempDir("${fixture.name}/$path") ?: continue
                val document = documentManager.getDocument(virtualFile) ?: continue
                if (document.text != text) document.setText(text)
            }
            com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
    }

    /** Возвращает открытый документ к исходному тексту фикстуры. */
    private fun restoreDocument(text: String) {
        val document = myFixture.editor.document
        if (document.text == text) return
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            document.setText(text)
            com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(document)
        }
    }

    /** Идентификатор, вставленный автодополнением слева от каретки. */
    private fun insertedIdentifier(): String {
        val text = myFixture.editor.document.text
        val caret = myFixture.editor.caretModel.offset
        var start = caret
        while (start > 0 && text[start - 1].isJavaIdentifierPart()) start--
        return text.substring(start, caret)
    }

    /** Область поиска, ограниченная каталогом `references` фикстуры. */
    private fun scopeOf(fixture: Fixture): com.intellij.psi.search.GlobalSearchScope {
        val anyPath = fixture.files.keys.first()
        val virtualFile = myFixture.findFileInTempDir("${fixture.name}/$anyPath")
            ?: error("файл фикстуры '${fixture.name}' не найден в тестовом проекте")
        return SmartAppScopes.forFile(project, virtualFile)
    }

    /**
     * Позиция в фикстуре. [column] — символ начала диапазона в строке; в
     * ожиданиях он необязателен (тогда сравнение идёт без него), но обязателен,
     * если такой текст встречается в строке дважды: иначе кейс не различал бы
     * два одинаковых имени на одной строке.
     */
    private data class Described(
        val file: String,
        val line: Int,
        val column: Int?,
        val rangeText: String,
    )

    private data class Diagnostic(val message: String, val line: Int, val rangeText: String)

    private class Fixture(
        val name: String,
        val expected: JsonObject,
        val files: Map<String, String>,
    ) {
        fun text(path: String): String =
            files[path] ?: error("фикстура '$name' не содержит файла $path")
    }

    private fun loadFixtures(): List<Fixture> {
        val root = File(sharedDir(), "fixtures")
        assertTrue("каталог фикстур не найден: $root", root.isDirectory)

        return root.listFiles()!!
            .filter { it.isDirectory }
            .sortedBy { it.name }
            .map { dir ->
                val filesDir = File(dir, "files")
                val files = filesDir.walkTopDown()
                    .filter { it.isFile }
                    .associate { it.relativeTo(filesDir).path.replace(File.separatorChar, '/') to it.readText() }
                Fixture(
                    name = dir.name,
                    expected = JsonParser.parseString(File(dir, "expected.json").readText()).asJsonObject,
                    files = files,
                )
            }
    }

    private fun sharedDir(): File = File(
        System.getProperty("smartapp.shared")
            ?: error("system property 'smartapp.shared' is not set by the build"),
    )

    private companion object {
        /** Общий префикс сообщений плагина о неразрешённых ссылках и полях. */
        const val UNRESOLVED_PREFIX = "Не удаётся разрешить"

        /** Секции `expected.json`, которые умеет проверять этот раннер. */
        val SUPPORTED_SECTIONS = setOf(
            "description",
            "definitions",
            "references",
            "diagnostics",
            "completion",
            "semanticTokens",
            "rename",
            "indexedNames",
        )
    }
}
