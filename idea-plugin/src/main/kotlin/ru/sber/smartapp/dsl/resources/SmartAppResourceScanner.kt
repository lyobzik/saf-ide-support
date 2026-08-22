package ru.sber.smartapp.dsl.resources

import ru.sber.smartapp.dsl.contract.ResourceScanSpec

/**
 * Минимальный разбор Python — ровно столько, сколько нужно, чтобы прочитать
 * ресурсы приложения: кто кого наследует, какие `init_*` есть у класса,
 * вызывают ли они `super()`, что регистрируют и что импортируют.
 *
 * Это не парсер Python: поддерживаемые и отвергаемые формы перечислены в плане
 * (`docs/plans/2026-08-22-custom-app-resources.md`) и закреплены одной таблицей
 * входов в тестах обеих реализаций. Порт 1:1 — `core/pythonScan.ts`.
 *
 * Разбор идёт в два шага. Сначала текст **маскируется**: содержимое строк и
 * комментариев заменяется заполнителем той же длины, поэтому скобки и кавычки
 * внутри литералов не ломают структуру, а смещения остаются настоящими. Затем
 * маскированный текст режется на логические строки (перенос внутри скобок и
 * хвостовой обратный слэш продолжают строку), и каждая разбирается регулярками.
 */
object SmartAppResourceScanner {

    /** Символ-заполнитель: занимает место содержимого строк и комментариев. */
    const val FILLER: Char = '\u0001'

    /** Строковый литерал: свой диапазон, диапазон содержимого и префикс (`r`, `f`, …). */
    data class PyString(
        val start: Int,
        val end: Int,
        val contentStart: Int,
        val contentEnd: Int,
        val prefix: String,
    )

    data class LogicalLine(val start: Int, val end: Int, val indent: Int)

    /** Регистрация ключевого слова: `registry["name"] = Class`. */
    data class Registration(
        val registry: String,
        /** Декодированное значение литерала — в тех же координатах, что значение JSON. */
        val name: String,
        /** Сырой диапазон содержимого литерала (без кавычек): цель перехода и вхождение. */
        val nameStart: Int,
        val nameEnd: Int,
        /** Правая часть, если это идентификатор: только для подписи варианта. */
        val className: String?,
    )

    data class PyMethod(
        val name: String,
        /** Вызывает ли метод одноимённый `super().<name>()`. */
        val callsSuper: Boolean,
        val registrations: List<Registration>,
    )

    /** Позиционные базы; ключевые аргументы заголовка (`metaclass=`) базой не считаются. */
    data class PyClass(val name: String, val bases: List<String>, val methods: List<PyMethod>)

    /** Импортированное имя: `from a import B as C` даёт `C -> (a, B)`. */
    data class ImportedName(val module: String, val name: String)

    data class PyModule(
        val classes: List<PyClass>,
        val imports: Map<String, ImportedName>,
        /** Алиас модуля -> модуль (`import a.b as c`, `import a.b`). */
        val moduleImports: Map<String, String>,
        /** Безусловные присваивания верхнего уровня: имя -> значение как текст. */
        val topLevelVars: Map<String, String>,
        /** Имена, присвоенные не на верхнем уровне: значение таких считать нельзя. */
        val conditionalVars: Set<String>,
    )

    /**
     * Заменяет содержимое строк и комментариев заполнителем той же длины.
     * Длина текста сохраняется, поэтому смещения в маске — настоящие смещения файла.
     */
    fun mask(text: String): Pair<String, List<PyString>> {
        val out = text.toCharArray()
        val strings = ArrayList<PyString>()
        var i = 0
        while (i < text.length) {
            val char = text[i]
            if (char == '#') {
                while (i < text.length && text[i] != '\n') out[i++] = ' '
                continue
            }
            if (char == '"' || char == '\'') {
                val prefixStart = prefixStartAt(text, i)
                val prefix = text.substring(prefixStart, i)
                val triple = text.startsWith("$char$char$char", i)
                val quote = if (triple) "$char$char$char" else char.toString()
                val contentStart = i + quote.length
                var j = contentStart
                while (j < text.length) {
                    // В Python обратный слэш не даёт кавычке завершить строку даже
                    // в raw-литерале: для поиска конца это учитывается всегда, а
                    // содержимое raw-строки при декодировании не меняется.
                    if (text[j] == '\\') {
                        j += 2
                        continue
                    }
                    if (text.startsWith(quote, j)) break
                    // Незакрытая однострочная строка обрывается концом строки — так же,
                    // как её видит Python, и структура файла не съезжает.
                    if (!triple && text[j] == '\n') break
                    j++
                }
                val contentEnd = minOf(j, text.length)
                // Переводы строк внутри литерала тоже маскируются: многострочная
                // строка — одна логическая строка, иначе её продолжения выглядели
                // бы как код с нулевым отступом и выбрасывали бы разбор из класса.
                for (k in contentStart until contentEnd) out[k] = FILLER
                for (k in prefixStart until i) out[k] = FILLER
                strings.add(
                    PyString(
                        start = prefixStart,
                        end = minOf(contentEnd + quote.length, text.length),
                        contentStart = contentStart,
                        contentEnd = contentEnd,
                        prefix = prefix,
                    ),
                )
                i = contentEnd + if (text.startsWith(quote, contentEnd)) quote.length else 0
                continue
            }
            i++
        }
        return String(out) to strings
    }

    /** Буквенный префикс строкового литерала (`r`, `rb`, `f`, …) перед кавычкой. */
    private fun prefixStartAt(text: String, quote: Int): Int {
        var start = quote
        while (start > 0 && text[start - 1].isLetter()) start--
        // Больше трёх букв префикса в Python не бывает; иначе это конец идентификатора.
        return if (quote - start <= 3) start else quote
    }

    /**
     * Логические строки маскированного текста: перенос внутри скобок и хвостовой
     * обратный слэш продолжают строку. Отступ — по первой физической строке.
     */
    fun logicalLines(masked: String): List<LogicalLine> {
        val lines = ArrayList<LogicalLine>()
        var depth = 0
        var start = 0
        var indent = 0
        var measured = false
        for (i in masked.indices) {
            if (!measured) {
                indent = indentAt(masked, start)
                measured = true
            }
            when (masked[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth = maxOf(0, depth - 1)
                '\n' -> {
                    val continued = depth > 0 || masked.substring(start, i).trimEnd().endsWith("\\")
                    if (!continued) {
                        if (masked.substring(start, i).isNotBlank()) {
                            lines.add(LogicalLine(start, i, indent))
                        }
                        start = i + 1
                        measured = false
                    }
                }
            }
        }
        if (start < masked.length && masked.substring(start).isNotBlank()) {
            lines.add(
                LogicalLine(start, masked.length, if (measured) indent else indentAt(masked, start)),
            )
        }
        return lines
    }

    private fun indentAt(masked: String, offset: Int): Int {
        var indent = 0
        for (i in offset until masked.length) {
            when (masked[i]) {
                ' ' -> indent += 1
                '\t' -> indent += 4
                else -> return indent
            }
        }
        return indent
    }
    private const val IDENT = "[A-Za-z_][A-Za-z0-9_]*"
    private const val DOTTED = "(?:$IDENT\\.)*$IDENT"
    private val CLASS_RE = Regex("^\\s*class\\s+($IDENT)\\s*(?:\\(([^)]*)\\))?\\s*:")
    private val DEF_RE = Regex("^\\s*(?:async\\s+)?def\\s+($IDENT)\\s*\\(")
    private val FROM_IMPORT_RE = Regex("^\\s*from\\s+($DOTTED)\\s+import\\s+(.+?)\\s*$")
    private val IMPORT_RE = Regex("^\\s*import\\s+($DOTTED)(?:\\s+as\\s+($IDENT))?\\s*$")
    private val ASSIGN_DOTTED_RE = Regex("^\\s*($IDENT)\\s*=\\s*($DOTTED)\\s*$")
    private val ASSIGN_ANY_RE = Regex("^\\s*($IDENT)\\s*=[^=]")
    private val SUBSCRIPT_RE = Regex("(?:^|[^A-Za-z0-9_.])((?:$IDENT\\.)*)($IDENT)\\s*\\[")
    private val UPDATE_RE = Regex("(?:^|[^A-Za-z0-9_.])((?:$IDENT\\.)*)($IDENT)\\s*\\.update\\s*\\(")
    private val DOTTED_ONLY_RE = Regex("^$DOTTED$")
    private val ASSIGN_TAIL_RE = Regex("^\\s*=[^=]")

    private class ClassDraft(
        val name: String,
        val bases: List<String>,
        val methods: MutableList<MethodDraft> = ArrayList(),
    )

    private class MethodDraft(
        val name: String,
        var callsSuper: Boolean = false,
        val registrations: MutableList<Registration> = ArrayList(),
    )

    private class Block(
        val indent: Int,
        val isClass: Boolean,
        val cls: ClassDraft?,
        val method: MethodDraft?,
        /** Отступ тела: у `def` — отступ первой строки тела, дальше он фиксирован. */
        var bodyIndent: Int? = null,
    )

    /** Разбирает модуль настолько, насколько нужно для чтения ресурсов приложения. */
    fun parseModule(text: String): PyModule {
        val (masked, strings) = mask(text)
        val classes = ArrayList<ClassDraft>()
        val imports = LinkedHashMap<String, ImportedName>()
        val moduleImports = LinkedHashMap<String, String>()
        val topLevelVars = LinkedHashMap<String, String>()
        val conditionalVars = LinkedHashSet<String>()
        val stack = ArrayList<Block>()

        for (line in logicalLines(masked)) {
            while (stack.isNotEmpty() && stack.last().indent >= line.indent) {
                stack.removeAt(stack.size - 1)
            }
            val maskedLine = masked.substring(line.start, line.end)
            val rawLine = text.substring(line.start, line.end)

            val classMatch = CLASS_RE.find(maskedLine)
            if (classMatch != null) {
                val cls = ClassDraft(
                    name = classMatch.groupValues[1],
                    bases = positionalBases(rawLine, classMatch.groupValues[2]),
                )
                classes.add(cls)
                stack.add(Block(line.indent, isClass = true, cls = cls, method = null))
                continue
            }

            val defMatch = DEF_RE.find(maskedLine)
            if (defMatch != null) {
                val owner = stack.lastOrNull()
                val method = MethodDraft(defMatch.groupValues[1])
                if (owner != null && owner.isClass) owner.cls?.methods?.add(method)
                stack.add(Block(line.indent, isClass = false, cls = null, method = method))
                continue
            }

            val enclosing = stack.lastOrNull()
            val method = if (enclosing != null && !enclosing.isClass) enclosing.method else null
            if (enclosing != null && enclosing.bodyIndent == null) enclosing.bodyIndent = line.indent
            // Только прямые операторы тела метода. Строка глубже — это `if`,
            // `for`, `try`, `with` или вложенный `def`: регистрация там условная,
            // и принять её значило бы обещать слово, которого может не быть.
            val directBodyLine = enclosing != null && enclosing.bodyIndent == line.indent
            val insideResourceMethod = method != null && directBodyLine &&
                method.name.startsWith(ResourceScanSpec.methodPrefix) &&
                stack.size >= 2 && stack[stack.size - 2].isClass

            if (insideResourceMethod && method != null) {
                if (superCallRe(method.name).containsMatchIn(maskedLine)) method.callsSuper = true
                method.registrations.addAll(registrationsIn(text, masked, strings, line))
                continue
            }

            val fromImport = FROM_IMPORT_RE.find(maskedLine)
            if (fromImport != null) {
                val marker = rawLine.indexOf(" import ")
                val items = if (marker >= 0) rawLine.substring(marker + " import ".length) else ""
                for (item in items.replace("(", "").replace(")", "").split(',')) {
                    val parts = item.trim().split(Regex("\\s+as\\s+"))
                    val name = parts.firstOrNull()?.trim().orEmpty()
                    if (name.isEmpty() || name == "*") continue
                    imports[parts.getOrNull(1)?.trim() ?: name] =
                        ImportedName(fromImport.groupValues[1], name)
                }
                continue
            }

            val moduleImport = IMPORT_RE.find(maskedLine)
            if (moduleImport != null) {
                val module = moduleImport.groupValues[1]
                val alias = moduleImport.groupValues[2].ifEmpty { module }
                moduleImports[alias] = module
                continue
            }

            val anyAssign = ASSIGN_ANY_RE.find(maskedLine) ?: continue
            val dotted = ASSIGN_DOTTED_RE.find(maskedLine)
            if (line.indent == 0 && stack.isEmpty() && dotted != null) {
                // Последнее безусловное присваивание верхнего уровня побеждает — как в Python.
                topLevelVars[dotted.groupValues[1]] = dotted.groupValues[2]
            } else {
                // Присваивание в ветке `if`, в функции или в классе: значение
                // статически неизвестно, полагаться на него нельзя (план, раздел 1).
                conditionalVars.add(anyAssign.groupValues[1])
            }
        }

        return PyModule(
            classes = classes.map { draft ->
                PyClass(
                    draft.name,
                    draft.bases,
                    draft.methods.map { PyMethod(it.name, it.callsSuper, it.registrations.toList()) },
                )
            },
            imports = imports,
            moduleImports = moduleImports,
            topLevelVars = topLevelVars,
            conditionalVars = conditionalVars,
        )
    }

    /**
     * Позиционные базы заголовка класса. Ключевые аргументы (`metaclass=M`) и
     * распаковка базой не считаются: `class C(Base, metaclass=M)` — одна база.
     */
    private fun positionalBases(rawLine: String, maskedHeader: String): List<String> {
        if (maskedHeader.isEmpty()) return emptyList()
        val open = rawLine.indexOf('(')
        val close = rawLine.lastIndexOf(')')
        val header = if (open >= 0 && close > open) rawLine.substring(open + 1, close) else maskedHeader
        return header.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.contains('=') && !it.startsWith("*") }
    }

    private fun superCallRe(method: String): Regex =
        Regex("super\\s*\\([^)]*\\)\\s*\\.\\s*$method\\s*\\(")

    /** Индекс закрывающей скобки для скобки в [open], либо -1. */
    private fun matchBracket(masked: String, open: Int): Int {
        val close = when (masked.getOrNull(open)) {
            '(' -> ')'
            '[' -> ']'
            '{' -> '}'
            else -> return -1
        }
        var depth = 0
        for (i in open until masked.length) {
            when (masked[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> {
                    depth--
                    if (depth == 0) return if (masked[i] == close) i else -1
                }
            }
        }
        return -1
    }

    /** Единственный строковый литерал внутри [start, end), если там больше ничего нет. */
    private fun soleString(
        masked: String,
        strings: List<PyString>,
        start: Int,
        end: Int,
    ): PyString? {
        val only = strings.filter { it.start >= start && it.end <= end }.singleOrNull() ?: return null
        val before = masked.substring(start, only.start).isBlank()
        val after = masked.substring(only.end, end).isBlank()
        return if (before && after) only else null
    }

    /**
     * Декодирует строковый литерал Python. `f`- и `b`-строки не поддерживаются
     * (имя вычисляется или это байты), сырые строки отдаются как есть.
     */
    fun decodeString(text: String, literal: PyString): String? {
        val prefix = literal.prefix.lowercase()
        if (prefix.contains("f") || prefix.contains("b")) return null
        val content = text.substring(literal.contentStart, literal.contentEnd)
        if (prefix.contains("r")) return content

        val out = StringBuilder()
        var i = 0
        while (i < content.length) {
            val char = content[i]
            if (char != '\\') {
                out.append(char)
                i++
                continue
            }
            val next = content.getOrNull(i + 1) ?: return null
            i += 2
            when (next) {
                'n' -> out.append('\n')
                't' -> out.append('\t')
                'r' -> out.append('\r')
                '0' -> out.append('\u0000')
                '\\' -> out.append('\\')
                '\'' -> out.append('\'')
                '"' -> out.append('"')
                '\n' -> Unit // перенос строки, экранированный обратным слэшем
                'x', 'u', 'U' -> {
                    val width = if (next == 'x') 2 else if (next == 'u') 4 else 8
                    if (i + width > content.length) return null
                    val code = content.substring(i, i + width).toIntOrNull(16) ?: return null
                    if (code > 0x10FFFF) return null
                    out.appendCodePoint(code)
                    i += width
                }
                // Контракт escape сознательно узкий (см. план): восьмеричные
                // последовательности, \a, \b, \f, \v, \N{...} не поддерживаются.
                // Отвергаем регистрацию целиком: подставить не то имя хуже, чем
                // не подставить никакого.
                else -> return null
            }
        }
        return out.toString()
    }

    /** Регистрации в одной логической строке: `registry["x"] = C` и `registry.update({...})`. */
    private fun registrationsIn(
        text: String,
        masked: String,
        strings: List<PyString>,
        line: LogicalLine,
    ): List<Registration> {
        val result = ArrayList<Registration>()
        val slice = masked.substring(line.start, line.end)

        for (match in SUBSCRIPT_RE.findAll(slice)) {
            val registry = match.groupValues[2]
            val open = line.start + match.range.last
            val close = matchBracket(masked, open)
            if (close < 0) continue
            // За подпиской должно стоять присваивание, а не сравнение или чтение.
            if (!ASSIGN_TAIL_RE.containsMatchIn(masked.substring(close + 1, line.end))) continue
            val literal = soleString(masked, strings, open + 1, close) ?: continue
            val name = decodeString(text, literal) ?: continue
            result.add(
                Registration(
                    registry = registry,
                    name = name,
                    nameStart = literal.contentStart,
                    nameEnd = literal.contentEnd,
                    className = dottedOrNull(
                        text.substring(close + 1, line.end).trimStart().removePrefix("=").trim(),
                    ),
                ),
            )
        }

        for (match in UPDATE_RE.findAll(slice)) {
            val registry = match.groupValues[2]
            val open = line.start + match.range.last
            val close = matchBracket(masked, open)
            if (close < 0) continue
            val braceOpen = masked.indexOf('{', open)
            if (braceOpen < 0 || braceOpen > close ||
                masked.substring(open + 1, braceOpen).isNotBlank()
            ) {
                // Аргумент не словарный литерал: имена вычисляются, поддержать нечем.
                continue
            }
            val braceClose = matchBracket(masked, braceOpen)
            if (braceClose < 0) continue
            for (literal in strings) {
                if (literal.start < braceOpen || literal.end > braceClose) continue
                // Только непосредственные ключи внешнего словаря: строка внутри
                // вложенного объекта — не имя ключевого слова.
                if (bracketDepthBetween(masked, braceOpen + 1, literal.start) != 0) continue
                if (!masked.substring(literal.end, braceClose).trimStart().startsWith(":")) continue
                val name = decodeString(text, literal) ?: continue
                val value = text.substring(literal.end, braceClose)
                    .trimStart().removePrefix(":").trim()
                result.add(
                    Registration(
                        registry = registry,
                        name = name,
                        nameStart = literal.contentStart,
                        nameEnd = literal.contentEnd,
                        className = dottedOrNull(value.substringBefore(',')),
                    ),
                )
            }
        }
        return result
    }

    /** Глубина вложенности скобок на участке [start, end) — 0 значит «верхний уровень». */
    private fun bracketDepthBetween(masked: String, start: Int, end: Int): Int {
        var depth = 0
        for (i in start until end) {
            when (masked[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
            }
        }
        return depth
    }

    /** Точечное имя из текста значения, если это оно; иначе подписи не будет. */
    private fun dottedOrNull(value: String): String? {
        val trimmed = value.trim().trimEnd(',', '}').trim()
        return if (DOTTED_ONLY_RE.matches(trimmed)) trimmed else null
    }
}
