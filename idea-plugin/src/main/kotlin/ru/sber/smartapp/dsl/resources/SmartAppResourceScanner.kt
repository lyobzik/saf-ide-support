package ru.sber.smartapp.dsl.resources

import ru.sber.smartapp.dsl.contract.ResourceScanSpec
import ru.sber.smartapp.dsl.contract.UserModelSpec

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
        /**
         * Разбор словаря параметров — только у метода параметризатора.
         * `null` — метод не тот либо правило `<d>` нарушено.
         */
        val dictionary: DictionaryUse? = null,
    )

    /** Запись в словарь параметров шаблона: ключ и правая часть. */
    data class DictionaryBinding(
        /** Декодированный ключ — в тех же координатах, что значение JSON. */
        val key: String,
        /** Значение, если распознано как точечное имя; иначе `null` («не знаем»). */
        val value: String?,
        /** Сырой диапазон содержимого ключа: цель перехода и вхождение. */
        val nameStart: Int,
        val nameEnd: Int,
    )

    /**
     * Разбор тела метода вокруг словаря `<d>`, который метод возвращает.
     *
     * Существует, только когда правило `<d>` выполнено целиком (план, раздел 3):
     * ровно один `return` барного имени последним оператором прямого тела,
     * ровно одна инициализация, все прочие упоминания `<d>` — записи
     * поддержанной формы на прямом уровне, и ни одного гасителя в теле. `null` —
     * правило нарушено, и привязки этого метода считать нельзя: молчаливое
     * «почти доказательство» здесь дало бы WARNING на несуществующем корне.
     */
    data class DictionaryUse(
        val name: String,
        /** Инициализация `<d> = super().<метод>(…)`: привязки базы наследуются. */
        val inheritsBase: Boolean,
        /** Записи по возрастанию смещения: при повторе ключа побеждает последняя. */
        val bindings: List<DictionaryBinding>,
    )

    /** Откуда взялось имя атрибута модели пользователя. */
    enum class DeclarationOrigin { FIELD, SELF, DEF, CLASS_LEVEL }

    /**
     * Объявление имени в классе: имя и **сырой** диапазон для перехода.
     *
     * Диапазон сырой, а имя декодированное — то же правило, что у регистраций:
     * имя сравнивается со значением JSON, а диапазон служит целью навигации.
     */
    data class PyDeclaration(
        val name: String,
        val nameStart: Int,
        val nameEnd: Int,
        val origin: DeclarationOrigin,
    )

    /**
     * Состояние свойства `fields` — четыре, и различать надо все четыре.
     *
     * [ABSENT] — свойство унаследовано как есть; [WITH_SUPER] — база сохранена;
     * [WITHOUT_SUPER] — база отброшена; [UNPARSED] — форму не разобрали, и тогда
     * поля базы сохраняются, а диагностика гасится: молча потерять пол хуже, чем
     * предложить лишнее.
     */
    enum class FieldsState { ABSENT, WITH_SUPER, WITHOUT_SUPER, UNPARSED }

    /** Позиционные базы; ключевые аргументы заголовка (`metaclass=`) базой не считаются. */
    data class PyClass(
        val name: String,
        val bases: List<String>,
        val methods: List<PyMethod>,
        /** Сырой диапазон имени класса — цель перехода с корневой переменной. */
        val nameStart: Int,
        val nameEnd: Int,
        /** Имена атрибутов модели пользователя со ссылками на места объявления. */
        val declarations: List<PyDeclaration>,
        val fieldsState: FieldsState,
        /** Сработавшие гасители диагностики из контракта. */
        val blockers: Set<String>,
    )

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
    /**
     * Идентификатор Python — та же грамматика, что у [SmartAppIdentifiers]:
     * буква или `_` в начале, плюс десятичная цифра дальше. ASCII-приближение
     * теряло бы `self.я`, `def имя` и `class Пользователь`, хотя фильтр имён их
     * принимает.
     *
     * Символы вне BMP этот шаблон матчит, а `isAddressableName` — нет.
     * Расхождение безвредно: сканер только находит имена, а годность решает
     * фильтр, и он строже.
     */
    private const val IDENT = "[\\p{L}_][\\p{L}\\p{Nd}_]*"
    private const val DOTTED = "(?:$IDENT\\.)*$IDENT"
    private val CLASS_RE = Regex("^\\s*class\\s+($IDENT)\\s*(?:\\(([^)]*)\\))?\\s*:")
    private val DEF_RE = Regex("^\\s*(?:async\\s+)?def\\s+($IDENT)\\s*\\(")
    private val FROM_IMPORT_RE = Regex("^\\s*from\\s+($DOTTED)\\s+import\\s+(.+?)\\s*$")
    private val IMPORT_RE = Regex("^\\s*import\\s+($DOTTED)(?:\\s+as\\s+($IDENT))?\\s*$")
    private val ASSIGN_DOTTED_RE = Regex("^\\s*($IDENT)\\s*=\\s*($DOTTED)\\s*$")
    private val ASSIGN_ANY_RE = Regex("^\\s*($IDENT)\\s*=[^=]")

    /**
     * Конец ключевого слова. Записан лоокэхедом, а не `\b`: словом `\b` считает
     * только ASCII, поэтому в `ifя` (законное имя по грамматике идентификаторов)
     * граница нашлась бы сразу после `if`, и объявление уехало бы в управляющие
     * конструкции.
     */
    private const val KEYWORD_END = "(?![\\p{L}\\p{Nd}_])"

    /** Заголовок управляющей конструкции: тело может быть и на этой же строке. */
    private val CONTROL_RE = Regex(
        "^\\s*(?:if|elif|else|for|while|try|except|finally|with|async\\s+(?:for|with))$KEYWORD_END",
    )

    /**
     * `match` и `case` — **мягкие** ключевые слова: `match = 1` и
     * `case: int = 2` законные присваивания. Отличает заголовок не хвостовое
     * двоеточие, а то, что стоит за словом (см. [isSoftControlHeader]).
     */
    private val SOFT_CONTROL_RE = Regex("^\\s*(?:match|case)$KEYWORD_END")

    /** Присваивание где-то внутри строки — для однострочных `if dev: X = Y`. */
    private val INLINE_ASSIGN_RE = Regex("(?:^|[^\\p{L}\\p{Nd}_.])($IDENT)\\s*=[^=]")

    /** Строка-декоратор: `@property`, `@dataclass(...)`. */
    private val DECORATOR_RE = Regex("^\\s*@")

    /**
     * `self.x = …` и `self.x: T = …`. Аннотация между именем и `=` пропускается:
     * значение создаёт атрибут независимо от неё, а голая аннотация (без `=`)
     * не создаёт ничего и потому не матчится.
     */
    private val SELF_ASSIGN_RE = Regex("(?:^|[^\\p{L}\\p{Nd}_.])self\\.($IDENT)\\s*(?::[^=\\n]+)?=(?!=)")

    /** Имя уровня класса **со значением**: `X = …`, `X: T = …`. Заякорена в начало. */
    private val CLASS_LEVEL_ASSIGN_RE = Regex("^\\s*($IDENT)\\s*(?::[^=\\n]+)?=(?!=)")

    /** Вызов фабрики поля: `Field(` и `field.Field(` — берётся последний сегмент. */
    private val FIELD_CALL_RE =
        Regex("(?:^|[^\\p{L}\\p{Nd}_])(?:$IDENT\\.)*${UserModelSpec.fieldFactory}\\s*\\(")

    /** `super().fields` — обращение к атрибуту, а не вызов. */
    private val SUPER_FIELDS_RE =
        Regex("^super\\s*\\(\\s*\\)\\s*\\.\\s*${UserModelSpec.fieldsProperty}\\s*\\+\\s*")

    /** `metaclass=` в заголовке класса. */
    private val METACLASS_RE = Regex("\\bmetaclass\\s*=")
    private val SUBSCRIPT_RE = Regex("(?:^|[^\\p{L}\\p{Nd}_.])((?:$IDENT\\.)*)($IDENT)\\s*\\[")
    private val UPDATE_RE = Regex("(?:^|[^\\p{L}\\p{Nd}_.])((?:$IDENT\\.)*)($IDENT)\\s*\\.update\\s*\\(")
    private val DOTTED_ONLY_RE = Regex("^$DOTTED$")
    private val ASSIGN_TAIL_RE = Regex("^\\s*=[^=]")

    private class ClassDraft(
        val name: String,
        val bases: List<String>,
        val nameStart: Int,
        val nameEnd: Int,
        val methods: MutableList<MethodDraft> = ArrayList(),
        val declarations: MutableList<PyDeclaration> = ArrayList(),
        var fieldsState: FieldsState = FieldsState.ABSENT,
        val blockers: MutableSet<String> = LinkedHashSet(),
        /** Логические строки прямого тела свойства `fields` — разбираются в конце. */
        val fieldsBody: MutableList<LogicalLine> = ArrayList(),
        var hasInit: Boolean = false,
    )

    private class MethodDraft(
        val name: String,
        var callsSuper: Boolean = false,
        val registrations: MutableList<Registration> = ArrayList(),
        /**
         * Логические строки тела — с любой глубины и только у метода
         * параметризатора: правило `<d>` формулируется вокруг всего тела, а не
         * вокруг прямых операторов, поэтому разобрать его построчно нельзя.
         */
        val bodyLines: MutableList<BodyLine> = ArrayList(),
        /** Есть ли декоратор на самом методе: он способен подменить результат. */
        var decorated: Boolean = false,
        var dictionary: DictionaryUse? = null,
    )

    /** Строка тела метода: [direct] — прямой уровень тела, а не глубже. */
    private class BodyLine(val start: Int, val end: Int, val direct: Boolean)

    private class Block(
        val indent: Int,
        val isClass: Boolean,
        val isControl: Boolean = false,
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
        // Декоратор стоит на строке перед `class`/`def`; на самом классе он
        // означает произвольную подмену поведения, которую сканер не отследит.
        var decoratedIndent: Int? = null

        for (line in logicalLines(masked)) {
            while (stack.isNotEmpty() && stack.last().indent >= line.indent) {
                stack.removeAt(stack.size - 1)
            }
            val maskedLine = masked.substring(line.start, line.end)
            val rawLine = text.substring(line.start, line.end)

            // Отступ тела фиксирует первая строка внутри блока — какой бы она ни
            // была. Считается до разбора строки: `class`/`def`/декоратор тоже
            // строки тела, а записи тела параметризатора признак «прямой
            // уровень» нужен уже здесь.
            val openBlock = stack.lastOrNull()
            if (openBlock != null && openBlock.bodyIndent == null) openBlock.bodyIndent = line.indent
            collectBodyLine(stack, line)

            // Класс верхнего уровня, внутри которого идёт строка: только его
            // имена собираются в модель пользователя.
            // Второй class-блок в стеке — вложенный класс: его имена,
            // self-атрибуты и гасители к внешнему отношения не имеют, и сбор
            // для внешнего прекращается.
            val nested = stack.drop(1).any { it.isClass }
            val ownerClass = stack.firstOrNull()?.takeIf { it.isClass && !nested }?.cls
            if (ownerClass != null) {
                collectBlockerTokens(maskedLine, ownerClass.blockers)
                // `self.x` собирается с **любой** глубины: условное присваивание
                // всё равно создаёт атрибут в той ветке, где выполнится, и
                // потерять имя хуже, чем предложить лишнее.
                if (stack.any { !it.isClass && !it.isControl }) {
                    collectMatches(
                        SELF_ASSIGN_RE, maskedLine, line.start,
                        DeclarationOrigin.SELF, ownerClass.declarations,
                    )
                } else {
                    // Однострочный suite (`if flag: LIMIT = 5`) — тоже
                    // объявление уровня класса, просто со сдвигом: разбор
                    // начинается после двоеточия. Без этого условное
                    // `fields = […]` терялось молча, вместе с гасителем.
                    collectClassLevelBody(
                        ownerClass, maskedLine, line, suiteBodyStart(maskedLine),
                    )
                }
            }

            if (DECORATOR_RE.containsMatchIn(maskedLine)) {
                decoratedIndent = line.indent
                continue
            }
            val decorated = decoratedIndent == line.indent
            decoratedIndent = null

            val classMatch = CLASS_RE.find(maskedLine)
            if (classMatch != null) {
                val className = classMatch.groupValues[1]
                val nameStart = line.start + maskedLine.indexOf(className, 5)
                val cls = ClassDraft(
                    name = className,
                    bases = positionalBases(rawLine, classMatch.groupValues[2]),
                    nameStart = nameStart,
                    nameEnd = nameStart + className.length,
                )
                if (decorated) cls.blockers.add("classDecorator")
                if (METACLASS_RE.containsMatchIn(rawLine)) cls.blockers.add("metaclassInBases")
                // Модель отдаёт только классы модульного уровня: вложенный класс
                // не может быть ресурсным, а одноимённый увёл бы резолвер не туда.
                if (stack.isEmpty()) classes.add(cls)
                stack.add(Block(line.indent, isClass = true, cls = cls, method = null))
                // Тело на строке заголовка (`class C(B): fields = […]`) — это
                // тело класса: сбор имён уровня класса до сюда не дошёл (строка
                // ещё не была внутри класса), и без разбора здесь и имена, и
                // гасители пропали бы.
                val inlineBody = bodyAfterColon(maskedLine)
                if (inlineBody > 0) {
                    collectBlockerTokens(maskedLine.substring(inlineBody), cls.blockers)
                    collectClassLevelBody(cls, maskedLine, line, inlineBody)
                }
                continue
            }

            val defMatch = DEF_RE.find(maskedLine)
            if (defMatch != null) {
                val owner = stack.lastOrNull()
                val name = defMatch.groupValues[1]
                val method = MethodDraft(name, decorated = decorated)
                if (owner != null && owner.isClass) owner.cls?.methods?.add(method)
                // Метод класса верхнего уровня бывает двух видов: прямой —
                // владелец сам класс, и условный — между классом и `def` стоит
                // управляющий блок. Вложенный `def` внутри метода методом
                // класса не является вовсе.
                val inOwnerClass = ownerClass != null && stack.none { !it.isClass && !it.isControl }
                val directMethod = inOwnerClass && owner != null && owner.isClass
                // Однострочное тело (`def __init__(self): self.x = 1`) — это
                // тело метода, просто на строке заголовка: ветка `def`
                // завершает обработку, и без отдельного разбора и объявление, и
                // `super()` терялись бы.
                // Тело `def` разбирается своим вызовом, а не через
                // `suiteBodyStart`: тот отвечает за управляющие конструкции и
                // используется ещё и сбором имён уровня класса, где локальная
                // переменная однострочного метода стала бы атрибутом класса.
                val suite = bodyAfterColon(maskedLine)
                if (suite > 0) {
                    if (callsSuperOnLine(maskedLine.substring(suite), name)) method.callsSuper = true
                    if (inOwnerClass && ownerClass != null) {
                        collectMatches(
                            SELF_ASSIGN_RE, maskedLine.substring(suite), line.start + suite,
                            DeclarationOrigin.SELF, ownerClass.declarations,
                        )
                    }
                    // Тело свойства `fields` на строке заголовка разбирается тем
                    // же разбором, что и многострочное: форма здесь ничем не
                    // отличается, и терять её поля незачем. Условный `def` сюда
                    // не идёт — его форму нельзя считать действующей
                    // (см. `conditionalDef`).
                    // Тело параметризатора на строке заголовка: в стек метод
                    // ещё не положен, поэтому строку записывает ветка `def`.
                    if (directMethod && name == UserModelSpec.parametrizerMethod) {
                        method.bodyLines.add(BodyLine(line.start + suite, line.end, direct = true))
                    }
                    if (directMethod && ownerClass != null && name == UserModelSpec.fieldsProperty) {
                        ownerClass.fieldsBody.add(LogicalLine(line.start + suite, line.end, line.indent))
                    }
                }
                if (inOwnerClass && ownerClass != null) {
                    val nameStart = line.start + maskedLine.indexOf(name, defMatch.range.first)
                    ownerClass.declarations.add(
                        PyDeclaration(name, nameStart, nameStart + name.length, DeclarationOrigin.DEF),
                    )
                    if (directMethod) {
                        if (name == "__init__") {
                            ownerClass.hasInit = true
                        } else if (name.startsWith("__") && name.endsWith("__")) {
                            ownerClass.blockers.add("dunderDefExceptInit")
                        }
                    } else {
                        // Условное определение метода. Считать его обычным
                        // нельзя: условное `fields` свернуло бы поля базы, а
                        // условный `__init__` без `super()` — наоборот, объявил
                        // бы класс грязным. Но и молчать нельзя: класс тогда
                        // выглядит чистым, хотя переопределение в нём есть. Имя
                        // предлагается, право утверждать теряется.
                        ownerClass.blockers.add("conditionalDef")
                    }
                }
                stack.add(Block(line.indent, isClass = false, cls = null, method = method))
                continue
            }

            val enclosing = stack.lastOrNull()

            // Однострочный suite (`if enabled: actions["x"] = C`) стоит на отступе
            // тела, но регистрация в нём условна так же, как в многострочном.
            if (isControlHeader(maskedLine)) {
                for (match in INLINE_ASSIGN_RE.findAll(maskedLine)) {
                    conditionalVars.add(match.groupValues[1])
                }
                // Блок кладётся в стек: иначе `class` внутри многострочного `if`
                // увиделся бы при пустом стеке и попал в модель как модульный,
                // хотя его существование условно.
                stack.add(Block(line.indent, isClass = false, isControl = true, cls = null, method = null))
                continue
            }

            val method = if (enclosing != null && !enclosing.isClass) enclosing.method else null
            // Только прямые операторы тела метода. Строка глубже — это `if`,
            // `for`, `try`, `with` или вложенный `def`: регистрация там условная,
            // и принять её значило бы обещать слово, которого может не быть.
            val directBodyLine = enclosing != null && enclosing.bodyIndent == line.indent
            val insideResourceMethod = method != null && directBodyLine &&
                method.name.startsWith(ResourceScanSpec.methodPrefix) &&
                // Ровно «класс -> метод»: вложенный класс внутри init_* не ресурсный.
                stack.size == 2 && stack[0].isClass

            // Признак нужен и ресурсам (`init_*`), и модели пользователя
            // (`__init__`), поэтому считается для любого метода, а не только
            // внутри ресурсной ветки.
            if (method != null && callsSuperOnLine(maskedLine, method.name)) {
                method.callsSuper = true
            }

            // Прямое тело свойства `fields` класса верхнего уровня: разбирается
            // после обхода, потому что состояние определяется формой всего тела.
            if (method != null && directBodyLine && method.name == UserModelSpec.fieldsProperty &&
                stack.size == 2 && stack[0].cls === ownerClass && ownerClass != null
            ) {
                ownerClass.fieldsBody.add(line)
            }

            if (insideResourceMethod && method != null) {
                method.registrations.addAll(registrationsIn(text, masked, strings, line))
                continue
            }

            val fromImport = FROM_IMPORT_RE.find(maskedLine)
            if (fromImport != null) {
                // Список имён берётся из маскированной строки: там комментарий уже
                // стёрт, а имена — идентификаторы, маскирование их не меняет.
                val items = fromImport.groupValues[2]
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

        for (draft in classes) {
            finishUserModel(text, masked, strings, draft)
            finishParametrizer(text, masked, strings, draft)
        }

        return PyModule(
            classes = classes.map { draft ->
                PyClass(
                    name = draft.name,
                    bases = draft.bases,
                    methods = draft.methods.map {
                        PyMethod(it.name, it.callsSuper, it.registrations.toList(), it.dictionary)
                    },
                    nameStart = draft.nameStart,
                    nameEnd = draft.nameEnd,
                    declarations = draft.declarations.toList(),
                    fieldsState = draft.fieldsState,
                    blockers = draft.blockers.toSet(),
                )
            },
            imports = imports,
            moduleImports = moduleImports,
            topLevelVars = topLevelVars,
            conditionalVars = conditionalVars,
        )
    }

    /** Токены-гасители из контракта по маскированной строке. */
    private fun collectBlockerTokens(maskedLine: String, out: MutableSet<String>) {
        // Маскированной: упоминание в docstring или комментарии гасителем не является.
        for (token in UserModelSpec.blockerTokens) {
            if (maskedLine.contains(token)) out.add(token)
        }
    }

    /**
     * Цепочка целей присваивания уровня класса: `a = b = 1` создаёт **два**
     * атрибута. Каждый следующий ищется в остатке строки после предыдущего `=`.
     */
    private fun collectClassLevelChain(
        maskedLine: String,
        offset: Int,
        out: MutableList<PyDeclaration>,
        from: Int = 0,
        to: Int = maskedLine.length,
    ) {
        var position = from
        while (true) {
            val rest = maskedLine.substring(position, to)
            val match = CLASS_LEVEL_ASSIGN_RE.find(rest) ?: return
            val name = match.groupValues[1]
            val start = offset + position + rest.indexOf(name, match.range.first)
            out.add(PyDeclaration(name, start, start + name.length, DeclarationOrigin.CLASS_LEVEL))
            position += match.range.last + 1
        }
    }

    /**
     * Начало тела однострочного suite: позиция за двоеточием заголовка
     * (`if flag: X = 1`). `0` — строка не управляющая либо тела на ней нет.
     */
    private fun suiteBodyStart(maskedLine: String): Int {
        if (!isControlHeader(maskedLine)) return 0
        return bodyAfterColon(maskedLine)
    }

    /**
     * Позиция двоеточия, закрывающего заголовок, либо `-1`.
     *
     * Ищется на нулевой глубине скобок: в `def f(self) -> Dict[str, int]: y = 2`
     * заголовок кончается последним двоеточием, а не тем, что внутри `Dict[...]`.
     */
    private fun colonAt(maskedLine: String): Int {
        var depth = 0
        for (i in maskedLine.indices) {
            when (maskedLine[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ':' -> if (depth == 0) return i
            }
        }
        return -1
    }

    /** Позиция за двоеточием заголовка, если тело есть на этой же строке; иначе `0`. */
    private fun bodyAfterColon(maskedLine: String): Int {
        val colon = colonAt(maskedLine)
        if (colon < 0) return 0
        return if (maskedLine.substring(colon + 1).isBlank()) 0 else colon + 1
    }

    /**
     * Простые операторы однострочного тела: у `pass; fields = [1]` их два.
     *
     * Без разбиения виден только первый, и всё остальное тело пропадает молча.
     * Разделитель ищется на нулевой глубине скобок; строки к этому моменту
     * замаскированы, поэтому `;` внутри литерала сюда не попадает.
     */
    private fun simpleStatements(maskedLine: String, from: Int): List<IntRange> {
        val result = ArrayList<IntRange>()
        var depth = 0
        var start = from
        for (i in from until maskedLine.length) {
            when (maskedLine[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ';' -> if (depth == 0) {
                    result.add(start until i)
                    start = i + 1
                }
            }
        }
        result.add(start until maskedLine.length)
        return result
    }

    /** Вызов `super().<method>()` отдельным оператором строки — в том числе в `a; b`. */
    private fun callsSuperOnLine(maskedLine: String, method: String): Boolean =
        simpleStatements(maskedLine, 0).any {
            isBareSuperCall(maskedLine.substring(it.first, it.last + 1), method)
        }

    /**
     * Записывает строку в тело метода параметризатора.
     *
     * Только прямой метод класса верхнего уровня: правило `<d>` — про этот
     * метод, а не про любой одноимённый. Строки берутся с любой глубины:
     * упоминание `<d>` внутри `if` отменяет доказательство, и увидеть его можно,
     * только собрав тело целиком.
     */
    private fun collectBodyLine(stack: List<Block>, line: LogicalLine) {
        val owner = stack.getOrNull(0) ?: return
        val block = stack.getOrNull(1) ?: return
        if (!owner.isClass || block.isClass || block.isControl) return
        val method = block.method ?: return
        if (method.name != UserModelSpec.parametrizerMethod) return
        method.bodyLines.add(
            BodyLine(
                line.start, line.end,
                // Прямой уровень — когда объемлющий блок и есть сам метод.
                direct = stack.size == 2 && block.bodyIndent == line.indent,
            ),
        )
    }

    /** Разбирает словарь параметров у методов параметризатора класса. */
    private fun finishParametrizer(
        text: String,
        masked: String,
        strings: List<PyString>,
        cls: ClassDraft,
    ) {
        for (method in cls.methods) {
            if (method.name != UserModelSpec.parametrizerMethod) continue
            // Декоратор на самом методе подменяет его результат целиком: тело
            // может собирать словарь как угодно, а в шаблон уедет то, что вернул
            // декоратор. Для свойства `fields` декоратор наоборот обязателен
            // (`@property` — способ объявления во фреймворке), поэтому запрет
            // только здесь.
            if (method.decorated) continue
            method.dictionary = dictionaryUse(text, masked, strings, method.bodyLines)
        }
    }

    /** Оператор тела: [direct] — прямой уровень, не глубже и не в однострочном suite. */
    private class BodyStatement(val text: String, val at: Int, val direct: Boolean)

    private val RETURN_RE = Regex("^\\s*return$KEYWORD_END")
    private val RETURN_NAME_RE = Regex("^\\s*return\\s+($IDENT)\\s*$")

    /** `dict()` — инициализация пустым словарём наравне с `{}`. */
    private val EMPTY_DICT_CALL_RE = Regex("^dict\\s*\\(\\s*\\)$")

    /**
     * Инициализация словаря вызовом базы — **только** `super()` без аргументов.
     *
     * Аргументированный `super(Base, self)` начинает поиск по MRO **после**
     * `Base`, то есть одноимённый метод самой `Base` не вызывает вовсе:
     * засчитать по нему наследование значило бы приписать словарю привязки,
     * которых в нём нет. Здесь это строже, чем в [isBareSuperCall] (там форма
     * служит признаком «база вызвана», и ошибка ведёт к отбрасыванию слов, а не
     * к ложному утверждению).
     */
    private val SUPER_DICT_RE =
        Regex("^super\\s*\\(\\s*\\)\\s*\\.\\s*${UserModelSpec.parametrizerMethod}\\s*\\(")

    /** Вызов `super().<метод>(…)`, занимающий всё выражение целиком. */
    private fun initializesFromSuper(value: String): Boolean {
        val match = SUPER_DICT_RE.find(value) ?: return false
        val open = match.range.last
        val close = matchBracket(value, open)
        return close >= 0 && value.substring(close + 1).isBlank()
    }

    /**
     * Присваивание за подпиской. Отдельная от [ASSIGN_TAIL_RE]: та **съедает**
     * символ после `=`, и смещение значения у двух реализаций разошлось бы.
     */
    private val ASSIGN_VALUE_RE = Regex("^\\s*=(?!=)")

    /** Упоминание имени по маскированному тексту; точка слева границей не считается. */
    private fun mentionRe(name: String): Regex =
        Regex("(?<![\\p{L}\\p{Nd}_])$name(?![\\p{L}\\p{Nd}_])")

    /** Есть ли в строке хоть один контрактный токен-гаситель. */
    private fun hasBlockerToken(maskedLine: String): Boolean =
        UserModelSpec.blockerTokens.any { maskedLine.contains(it) }

    /**
     * Тело метода как плоский список операторов в порядке документа.
     *
     * Заголовок управляющей конструкции, `def`, `class` и декоратор прямыми
     * операторами не считаются: запись в них либо условна, либо принадлежит
     * другому телу. Пустые операторы (хвостовая `;`) отбрасываются.
     */
    private fun bodyStatements(masked: String, body: List<BodyLine>): List<BodyStatement> {
        val result = ArrayList<BodyStatement>()
        for (line in body) {
            val maskedLine = masked.substring(line.start, line.end)
            val direct = line.direct &&
                !isControlHeader(maskedLine) &&
                !DEF_RE.containsMatchIn(maskedLine) &&
                !CLASS_RE.containsMatchIn(maskedLine) &&
                !DECORATOR_RE.containsMatchIn(maskedLine)
            for (statement in simpleStatements(maskedLine, 0)) {
                val text = maskedLine.substring(statement.first, statement.last + 1)
                if (text.isBlank()) continue
                result.add(BodyStatement(text, line.start + statement.first, direct))
            }
        }
        return result
    }

    /**
     * Имя словаря `<d>` из `return <d>`.
     *
     * `return` обязан быть единственным во всём теле, прямым оператором,
     * последним оператором тела и возвращать барное имя. Без «последнего»
     * запись после `return` — мёртвый код, который никогда не выполнится, —
     * засчиталась бы за привязку.
     */
    private fun returnedDictionary(statements: List<BodyStatement>): Pair<BodyStatement, String>? {
        val returns = statements.filter { RETURN_RE.containsMatchIn(it.text) }
        val only = returns.singleOrNull() ?: return null
        if (only !== statements.lastOrNull() || !only.direct) return null
        val name = RETURN_NAME_RE.find(only.text)?.groupValues?.get(1) ?: return null
        return only to name
    }

    /**
     * Разбор тела метода параметризатора по правилу `<d>` (план, раздел 3).
     *
     * `null` — правило нарушено; привязки такого метода ненадёжны целиком, а не
     * частично: любое упоминание `<d>` вне трёх поддержанных форм означает, что
     * содержимое словаря нам неизвестно.
     */
    private fun dictionaryUse(
        text: String,
        masked: String,
        strings: List<PyString>,
        body: List<BodyLine>,
    ): DictionaryUse? {
        val statements = bodyStatements(masked, body)
        val (returnStatement, name) = returnedDictionary(statements) ?: return null
        // Гаситель где угодно в теле меняет содержимое в обход имени, и никакое
        // правило про упоминания его не поймает.
        for (line in body) {
            if (hasBlockerToken(masked.substring(line.start, line.end))) return null
        }

        val mention = mentionRe(name)
        val bindings = ArrayList<DictionaryBinding>()
        var inheritsBase = false
        var initialized = false

        for (statement in statements) {
            // Сам `return <d>` — по тождеству, а не «последний оператор»: иначе
            // при ослаблении требования «последний» пропускался бы мёртвый код
            // после него.
            if (statement === returnStatement) continue
            if (!mention.containsMatchIn(statement.text)) continue
            if (!statement.direct) return null

            val init = initializationOf(text, masked, strings, statement, name)
            if (init != null) {
                if (initialized) return null // вторая инициализация
                initialized = true
                inheritsBase = init.inheritsBase
                bindings.addAll(init.bindings)
                continue
            }
            val written = writtenBindings(text, masked, strings, statement, name) ?: return null
            bindings.addAll(written)
        }
        return if (initialized) DictionaryUse(name, inheritsBase, bindings) else null
    }

    private class Initialization(val inheritsBase: Boolean, val bindings: List<DictionaryBinding>)

    /**
     * Инициализация `<d>`: `super().<метод>(…)`, `{}`, `dict()` или словарный
     * литерал с ключами. Аннотация между именем и `=` пропускается — семантики
     * она не меняет, а разойтись в её разборе две реализации не должны.
     */
    private fun initializationOf(
        text: String,
        masked: String,
        strings: List<PyString>,
        statement: BodyStatement,
        name: String,
    ): Initialization? {
        val head = Regex("^\\s*$name\\s*(?::[^=\\n]+)?=(?!=)").find(statement.text) ?: return null
        val consumed = head.value.length
        val tail = statement.text.substring(consumed)
        val offset = consumed + (tail.length - tail.trimStart().length)
        val value = statement.text.substring(offset).trim()
        val at = statement.at + offset

        // Наследование даёт только эта форма: отдельный вызов-оператор словарь
        // не инициализирует, и засчитывать его значило бы унаследовать привязки,
        // которых в `<d>` нет, — то есть ложное доказательство.
        if (initializesFromSuper(value)) {
            return Initialization(inheritsBase = true, bindings = emptyList())
        }
        if (EMPTY_DICT_CALL_RE.containsMatchIn(value)) {
            return Initialization(inheritsBase = false, bindings = emptyList())
        }
        if (!value.startsWith("{")) return null
        if (matchBracket(masked, at) != at + value.length - 1) return null
        val pairs = dictLiteralPairs(text, masked, strings, at, at + value.length - 1) ?: return null
        return Initialization(inheritsBase = false, bindings = pairs)
    }

    /**
     * Запись в `<d>`: `<d>["ключ"] = <значение>` либо `<d>.update({ … })`.
     *
     * Имя обязано быть барным и совпадать с `<d>`: точечный префикс сделал бы
     * источником корня любой посторонний словарь (`other.data["user"] = …`).
     */
    private fun writtenBindings(
        text: String,
        masked: String,
        strings: List<PyString>,
        statement: BodyStatement,
        name: String,
    ): List<DictionaryBinding>? {
        val subscript = Regex("^\\s*$name\\s*\\[").find(statement.text)
        if (subscript != null) {
            val open = statement.at + subscript.value.length - 1
            val close = matchBracket(masked, open)
            if (close < 0) return null
            // За подпиской обязано стоять присваивание, а не чтение или сравнение.
            val tail = statement.text.substring(close - statement.at + 1)
            val assign = ASSIGN_VALUE_RE.find(tail) ?: return null
            val key = keyAt(text, masked, strings, open + 1, close) ?: return null
            val value = dottedOrNull(tail.substring(assign.value.length))
            return listOf(DictionaryBinding(key.first, value, key.second, key.third))
        }

        val update = Regex("^\\s*$name\\s*\\.\\s*update\\s*\\(").find(statement.text)
            ?: return null
        val open = statement.at + update.value.length - 1
        val close = matchBracket(masked, open)
        if (close < 0 || statement.text.substring(close - statement.at + 1).isNotBlank()) return null
        // Ровно один аргумент, и он словарный литерал: второй аргумент и
        // именованные (`dict.update` имеет сигнатуру `update(m, **kwargs)`)
        // молча переопределяют ключ, поэтому привязкой такая форма не считается.
        val inside = masked.substring(open + 1, close)
        val argument = inside.trim()
        if (!argument.startsWith("{")) return null
        val braceOpen = open + 1 + inside.indexOf('{')
        val braceClose = braceOpen + argument.length - 1
        if (matchBracket(masked, braceOpen) != braceClose) return null
        return dictLiteralPairs(text, masked, strings, braceOpen, braceClose)
    }

    /**
     * Пары словарного литерала. `null` — среди элементов есть тот, что не
     * является парой «строковый ключ: значение»: распаковка `**other`,
     * вычисляемый ключ, comprehension. Такой литерал может переопределить любой
     * ключ, и считать его привязкой нельзя.
     */
    private fun dictLiteralPairs(
        text: String,
        masked: String,
        strings: List<PyString>,
        braceOpen: Int,
        braceClose: Int,
    ): List<DictionaryBinding>? {
        val result = ArrayList<DictionaryBinding>()
        for (element in depthZeroParts(masked, braceOpen + 1, braceClose, ',')) {
            if (masked.substring(element.first, element.last + 1).isBlank()) continue
            val colon = depthZeroIndex(masked, element.first, element.last + 1, ':')
            if (colon < 0) return null
            val key = keyAt(text, masked, strings, element.first, colon) ?: return null
            // По маскированному: хвостовой комментарий стёрт, а строковое
            // значение заполнителем точечным именем не станет. То же и в ветке
            // подписки.
            val value = dottedOrNull(masked.substring(colon + 1, element.last + 1))
            result.add(DictionaryBinding(key.first, value, key.second, key.third))
        }
        return result
    }

    /** Ключ-литерал в диапазоне: декодированное имя и сырой диапазон содержимого. */
    private fun keyAt(
        text: String,
        masked: String,
        strings: List<PyString>,
        start: Int,
        end: Int,
    ): Triple<String, Int, Int>? {
        val literal = soleString(masked, strings, start, end) ?: return null
        val key = decodeString(text, literal) ?: return null
        return Triple(key, literal.contentStart, literal.contentEnd)
    }

    /** Части диапазона, разделённые [separator] на нулевой глубине скобок. */
    private fun depthZeroParts(masked: String, start: Int, end: Int, separator: Char): List<IntRange> {
        val result = ArrayList<IntRange>()
        var depth = 0
        var from = start
        for (i in start until end) {
            when (masked[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                separator -> if (depth == 0) {
                    result.add(from until i)
                    from = i + 1
                }
            }
        }
        result.add(from until end)
        return result
    }

    /** Индекс [char] на нулевой глубине скобок внутри диапазона, либо -1. */
    private fun depthZeroIndex(masked: String, start: Int, end: Int, char: Char): Int {
        val parts = depthZeroParts(masked, start, end, char)
        return if (parts.size == 1) -1 else parts[0].last + 1
    }

    /**
     * Имена уровня класса, объявленные начиная с [from].
     *
     * Каждый простой оператор разбирается отдельно: у `if flag: pass; fields = […]`
     * всё после первого оператора иначе пропадает, а вместе с ним и гаситель.
     *
     * `fields = […]` переопределяет свойство и тоже содержит литералы полей:
     * форму разобрать нельзя (это не тело метода), но имена извлекаются — как
     * из любой другой неразобранной формы.
     */
    private fun collectClassLevelBody(
        cls: ClassDraft,
        maskedLine: String,
        line: LogicalLine,
        from: Int,
    ) {
        val before = cls.declarations.size
        for (statement in simpleStatements(maskedLine, from)) {
            collectClassLevelChain(
                maskedLine, line.start, cls.declarations,
                statement.first, statement.last + 1,
            )
        }
        val assignsFields = cls.declarations
            .drop(before)
            .any { it.name == UserModelSpec.fieldsProperty }
        if (assignsFields) cls.fieldsBody.add(line)
    }

    /** Все совпадения [re] как объявления имён, с сырым диапазоном. */
    private fun collectMatches(
        re: Regex,
        maskedLine: String,
        offset: Int,
        origin: DeclarationOrigin,
        out: MutableList<PyDeclaration>,
    ) {
        for (match in re.findAll(maskedLine)) {
            val name = match.groupValues[1]
            val start = offset + maskedLine.indexOf(name, match.range.first)
            out.add(PyDeclaration(name, start, start + name.length, origin))
        }
    }

    /**
     * Достраивает модель пользователя: разбирает свойство `fields` и досчитывает
     * структурные гасители, которые видны только по классу целиком.
     */
    private fun finishUserModel(
        text: String,
        masked: String,
        strings: List<PyString>,
        cls: ClassDraft,
    ) {
        // `__init__` без `super().__init__()` — гаситель только у класса С БАЗОЙ:
        // у безбазового обосновать его нечем, базовых `self.*` там не существует.
        if (cls.hasInit && cls.bases.isNotEmpty()) {
            // Побеждает **последнее** определение: так работает Python, а первое
            // разрешило бы диагностику там, где действующий `__init__` базу не зовёт.
            val init = cls.methods.lastOrNull { it.name == "__init__" }
            if (init != null && !init.callsSuper) cls.blockers.add("initWithoutSuper")
        }

        if (cls.fieldsBody.isEmpty()) {
            val hasProperty = cls.methods.any { it.name == UserModelSpec.fieldsProperty }
            if (hasProperty) {
                cls.fieldsState = FieldsState.UNPARSED
                cls.blockers.add("unparsedFieldsProperty")
            }
            return
        }

        cls.declarations.addAll(fieldDeclarations(text, masked, strings, cls.fieldsBody))
        cls.fieldsState = fieldsState(masked, strings, cls.fieldsBody)
        if (cls.fieldsState == FieldsState.UNPARSED) cls.blockers.add("unparsedFieldsProperty")
    }

    /**
     * Имена полей из тела свойства — **всегда**, какой бы ни была форма: литерал
     * в `[*super().fields, Field("x", X)]` виден не хуже, чем в обычном списке,
     * и терять имя, которое точно существует, незачем.
     */
    private fun fieldDeclarations(
        text: String,
        masked: String,
        strings: List<PyString>,
        lines: List<LogicalLine>,
    ): List<PyDeclaration> {
        val result = ArrayList<PyDeclaration>()
        for (line in lines) {
            for (call in fieldCalls(masked, line.start, line.end)) {
                val literal = soleString(masked, strings, call.argStart, call.argEnd) ?: continue
                val name = decodeString(text, literal) ?: continue
                result.add(
                    PyDeclaration(
                        name, literal.contentStart, literal.contentEnd, DeclarationOrigin.FIELD,
                    ),
                )
            }
        }
        return result
    }

    /**
     * Состояние свойства по форме тела. Признаются только `return [ … ]` и
     * `return super().fields + [ … ]`, причём каждый элемент списка обязан быть
     * вызовом фабрики поля со строковым первым аргументом.
     */
    private fun fieldsState(
        masked: String,
        strings: List<PyString>,
        lines: List<LogicalLine>,
    ): FieldsState {
        val line = lines.singleOrNull() ?: return FieldsState.UNPARSED
        val body = masked.substring(line.start, line.end)
        if (!body.trim().startsWith("return")) return FieldsState.UNPARSED

        val exprOffset = line.start + body.indexOf("return") + "return".length
        var expr = masked.substring(exprOffset, line.end).trim()
        var state = FieldsState.WITHOUT_SUPER

        if (SUPER_FIELDS_RE.containsMatchIn(expr)) {
            state = FieldsState.WITH_SUPER
            expr = SUPER_FIELDS_RE.replace(expr, "")
        }
        if (!expr.startsWith("[") || !expr.endsWith("]")) return FieldsState.UNPARSED

        val listStart = exprOffset + masked.substring(exprOffset, line.end).indexOf(expr)
        val listEnd = matchBracket(masked, listStart)
        if (listEnd != listStart + expr.length - 1) return FieldsState.UNPARSED

        return if (allElementsAreFields(masked, strings, listStart + 1, listEnd)) {
            state
        } else {
            FieldsState.UNPARSED
        }
    }

    /** Каждый элемент списка — вызов фабрики поля со строковым первым аргументом. */
    private fun allElementsAreFields(
        masked: String,
        strings: List<PyString>,
        start: Int,
        end: Int,
    ): Boolean {
        val calls = fieldCalls(masked, start, end)
        if (calls.isEmpty()) return SEPARATORS_RE.matches(masked.substring(start, end))
        var cursor = start
        for (call in calls) {
            // Между элементами допустимы только запятые и пробелы: распаковка
            // `*`, comprehension и любой посторонний элемент делают форму
            // неразобранной.
            if (!SEPARATORS_RE.matches(masked.substring(cursor, call.callStart))) return false
            if (soleString(masked, strings, call.argStart, call.argEnd) == null) return false
            cursor = call.callEnd
        }
        return SEPARATORS_RE.matches(masked.substring(cursor, end))
    }

    private val SEPARATORS_RE = Regex("^[\\s,]*$")

    private data class FieldCall(
        val callStart: Int,
        val callEnd: Int,
        val argStart: Int,
        val argEnd: Int,
    )

    /** Вызовы фабрики поля в диапазоне: границы вызова и первого аргумента. */
    private fun fieldCalls(masked: String, start: Int, end: Int): List<FieldCall> {
        val slice = masked.substring(start, end)
        val result = ArrayList<FieldCall>()
        for (match in FIELD_CALL_RE.findAll(slice)) {
            val open = start + match.range.last
            val close = matchBracket(masked, open)
            if (close < 0 || close > end) continue
            val leading = if (match.value.first() == '(' || match.range.first == 0) 0 else 1
            result.add(
                FieldCall(
                    callStart = start + match.range.first + leading,
                    callEnd = close + 1,
                    argStart = open + 1,
                    argEnd = firstArgEnd(masked, open + 1, close),
                ),
            )
        }
        return result
    }

    /** Конец первого аргумента: запятая верхнего уровня либо закрывающая скобка. */
    private fun firstArgEnd(masked: String, start: Int, close: Int): Int {
        var depth = 0
        for (i in start until close) {
            when (masked[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) return i
            }
        }
        return close
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

    /**
     * Вызов именно `super()`, а не `my_super()` или `obj.super()`: слева от имени
     * обязана быть граница — начало строки или символ, не входящий в идентификатор
     * и не точка.
     */
    private fun superCallRe(method: String): Regex =
        Regex("^super\\s*\\([^)]*\\)\\s*\\.\\s*$method\\s*\\(")

    /**
     * Вызов `super().<method>(...)` **самостоятельным оператором** строки.
     *
     * Одного вхождения мало: `super().__init__() if flag else None`,
     * `flag and super().__init__()` и `x = super().__init__()` — условные или
     * вложенные вызовы, и засчитывать их за доказательство нельзя.
     */
    private fun isBareSuperCall(maskedLine: String, method: String): Boolean {
        val trimmed = maskedLine.trim()
        val match = superCallRe(method).find(trimmed) ?: return false
        val open = match.range.last
        val close = matchBracket(trimmed, open)
        return close >= 0 && trimmed.substring(close + 1).isBlank()
    }

    /**
     * Заголовок управляющей конструкции, включая `match`/`case`.
     *
     * Без `match` класс, объявленный в его ветке, попадал бы в модель как
     * модульный: блок не кладётся в стек, и `stack.isEmpty()` оказывается
     * истиной — ровно та ошибка, от которой уже защищён `if`.
     */
    private fun isControlHeader(maskedLine: String): Boolean =
        CONTROL_RE.containsMatchIn(maskedLine) || isSoftControlHeader(maskedLine)

    /**
     * Символы, которыми подлежащее `match`/`case` начаться не может: за ними
     * стоит продолжение выражения, то есть мягкое слово здесь — обычное имя.
     * `match.foo: int = 1`, `match .foo: int = 1`, `match = 1`,
     * `case: int = 2`, `match, x = f()` — присваивания, и заголовком их считать
     * нельзя: строка получала бы «тело» ` int = 1` и приписывала классу
     * атрибут `int`.
     *
     * Трейлеры `[` и `(` в список не входят намеренно: `match [1, 2]:` —
     * законный заголовок, а `match[0]: int = 1` — законное присваивание, и по
     * одной строке они не различаются (Python различает их по тому, идёт ли
     * дальше блок `case`). Выбран заголовок: это направление отказа
     * безопасное — содержимое блока становится условным, а не наоборот.
     */
    private val EXPRESSION_TAIL = setOf('.', '=', ':', ',', ';')

    /**
     * Заголовок `match`/`case`: за мягким словом стоит начало подлежащего, а
     * сам заголовок закрыт двоеточием нулевой глубины.
     *
     * Хвостового двоеточия для признака мало: `case "x": USER = C` — заголовок
     * с телом на той же строке, и без него присваивание в ветке `case`
     * считалось бы безусловным, то есть редактор утверждал бы про `USER` то,
     * чего не знает. Проверять же одно двоеточие нельзя: `match = {1: 2}` и
     * `case: int = 2` — присваивания, поэтому символ сразу за словом решает
     * раньше.
     */
    private fun isSoftControlHeader(maskedLine: String): Boolean {
        val match = SOFT_CONTROL_RE.find(maskedLine) ?: return false
        val next = maskedLine.substring(match.value.length).trimStart().firstOrNull()
        if (next == null || next in EXPRESSION_TAIL) return false
        return colonAt(maskedLine) >= 0
    }

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
                '0' -> {
                    // `\0` — NUL, но `\012` — восьмеричная последовательность, а она
                    // вне контракта: принять её частично значило бы получить не то имя.
                    if (content.getOrNull(i) in '0'..'7') return null
                    out.append('\u0000')
                }
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

    /**
     * Точечное имя из текста значения, если это оно; иначе подписи не будет.
     *
     * Хвостовая `}` снимается — значение словарного литерала доходит сюда
     * вместе с закрывающей скобкой. Хвостовая запятая **не** снимается:
     * `x = self._user,` — законный Python и кортеж из одного элемента, а не имя.
     */
    private fun dottedOrNull(value: String): String? {
        val trimmed = value.trim().trimEnd('}').trim()
        return if (DOTTED_ONLY_RE.matches(trimmed)) trimmed else null
    }
}
