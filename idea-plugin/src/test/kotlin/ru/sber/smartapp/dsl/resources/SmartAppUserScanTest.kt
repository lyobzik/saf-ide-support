package ru.sber.smartapp.dsl.resources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.sber.smartapp.dsl.contract.UserModelSpec
import ru.sber.smartapp.dsl.resources.SmartAppResourceScanner.DeclarationOrigin
import ru.sber.smartapp.dsl.resources.SmartAppResourceScanner.FieldsState

/**
 * Разбор класса модели пользователя: какие имена он объявляет, что за состояние
 * у свойства `fields` и какие гасители диагностики сработали.
 *
 * Та же таблица входов прогоняется в ядре расширения (`userScan.test.ts`).
 */
class SmartAppUserScanTest {

    private fun first(source: String): SmartAppResourceScanner.PyClass =
        SmartAppResourceScanner.parseModule(source).classes.firstOrNull()
            ?: error("класс не разобран")

    /** Объявления как `origin:name` — origin существен, места объявления разные. */
    private fun declared(source: String): List<String> =
        first(source).declarations.map { "${it.origin}:${it.name}" }

    private fun blockers(source: String): Set<String> = first(source).blockers

    private fun fieldNames(cls: SmartAppResourceScanner.PyClass): List<String> =
        cls.declarations.filter { it.origin == DeclarationOrigin.FIELD }.map { it.name }

    // --- состояния свойства fields ---

    @Test
    fun testPropertyAbsent() {
        assertEquals(
            FieldsState.ABSENT,
            first("class C(B):\n    def other(self):\n        return 1\n").fieldsState,
        )
    }

    @Test
    fun testPropertyWithSuper() {
        val cls = first(
            "class C(B):\n" +
                "    @property\n" +
                "    def fields(self):\n" +
                "        return super().fields + [Field('own', M)]\n",
        )
        assertEquals(FieldsState.WITH_SUPER, cls.fieldsState)
        assertEquals(listOf("own"), fieldNames(cls))
    }

    @Test
    fun testPropertyWithoutSuper() {
        assertEquals(
            FieldsState.WITHOUT_SUPER,
            first(
                "class C(B):\n" +
                    "    @property\n" +
                    "    def fields(self):\n" +
                    "        return [Field('own', M)]\n",
            ).fieldsState,
        )
    }

    @Test
    fun testMultilineListStaysOneLogicalLine() {
        val cls = first(
            "class C(B):\n" +
                "    @property\n" +
                "    def fields(self):\n" +
                "        return super().fields + [Field('a', A),\n" +
                "                                 Field('b', B)]\n",
        )
        assertEquals(FieldsState.WITH_SUPER, cls.fieldsState)
        assertEquals(listOf("a", "b"), fieldNames(cls))
    }

    @Test
    fun testUnpackingIsUnparsedButNamesAreKept() {
        // Поля базы при этом сохраняются, а диагностика гасится: молча потерять
        // пол хуже, чем предложить лишнее.
        val cls = first(
            "class C(B):\n" +
                "    @property\n" +
                "    def fields(self):\n" +
                "        return [*super().fields, Field('own', M)]\n",
        )
        assertEquals(FieldsState.UNPARSED, cls.fieldsState)
        assertTrue("unparsedFieldsProperty" in cls.blockers)
        assertEquals(listOf("own"), fieldNames(cls))
    }

    @Test
    fun testForeignElementIsUnparsed() {
        assertEquals(
            FieldsState.UNPARSED,
            first(
                "class C(B):\n" +
                    "    @property\n" +
                    "    def fields(self):\n" +
                    "        return [Field('a', A), other_field()]\n",
            ).fieldsState,
        )
    }

    @Test
    fun testComputedFieldNameIsUnparsed() {
        assertEquals(
            FieldsState.UNPARSED,
            first(
                "class C(B):\n" +
                    "    @property\n" +
                    "    def fields(self):\n" +
                    "        return [Field(name_var, A)]\n",
            ).fieldsState,
        )
    }

    @Test
    fun testFieldNamesExtractedFromClassLevelAssignment() {
        // План обещает извлекать распознанные `Field(...)` из **любой** формы
        // свойства; форма влияет только на состояние и на гаситель.
        assertEquals(
            listOf("own"),
            fieldNames(first("class C(B):\n    fields = [Field('own', M)]\n")),
        )
    }

    @Test
    fun testConditionalFieldsAssignmentIsUnparsed() {
        // Однострочный suite разбирается со сдвигом за двоеточие. Без этого поле
        // терялось молча — вместе с гасителем, то есть диагностика оставалась
        // включённой по неполному словарю.
        val sources = listOf(
            "class C(B):\n    if flag: fields = [Field('x', M)]\n",
            "class C(B):\n    if flag:\n        fields = [Field('x', M)]\n",
        )
        for (source in sources) {
            val cls = first(source)
            assertEquals(source, FieldsState.UNPARSED, cls.fieldsState)
            assertTrue(source, "unparsedFieldsProperty" in cls.blockers)
            assertEquals(source, listOf("x"), fieldNames(cls))
        }
    }

    @Test
    fun testAssignmentInsteadOfPropertyIsUnparsed() {
        // `fields = […]` переопределяет свойство так же, как `def fields`, но
        // разобрать его тем же правилом нельзя: это не тело метода. Считать
        // такой класс «свойство не объявлено» значило бы взять поля базы там,
        // где их могли отбросить.
        val cls = first("class C(B):\n    fields = [Field('a', A)]\n")
        assertEquals(FieldsState.UNPARSED, cls.fieldsState)
        assertTrue("unparsedFieldsProperty" in cls.blockers)
    }

    @Test
    fun testMultiStatementBodyIsUnparsed() {
        assertEquals(
            FieldsState.UNPARSED,
            first(
                "class C(B):\n" +
                    "    @property\n" +
                    "    def fields(self):\n" +
                    "        base = super().fields\n" +
                    "        return base\n",
            ).fieldsState,
        )
    }

    // --- формы объявления имён ---

    @Test
    fun testSelfAssignment() {
        assertTrue("SELF:x" in declared("class C(B):\n    def __init__(self):\n        self.x = 1\n"))
    }

    @Test
    fun testSelfAssignmentWithAnnotation() {
        assertTrue(
            "SELF:x" in declared("class C(B):\n    def __init__(self):\n        self.x: int = 1\n"),
        )
    }

    @Test
    fun testBareSelfAnnotationIsNotDeclaration() {
        // `self.x: int` в рантайме ничего не создаёт.
        assertFalse(
            "SELF:x" in declared("class C(B):\n    def __init__(self):\n        self.x: int\n"),
        )
    }

    @Test
    fun testChainedClassLevelAssignment() {
        // `a = b = 1` создаёт два атрибута.
        val names = declared("class C(B):\n    a = b = 1\n")
        assertTrue("CLASS_LEVEL:a" in names)
        assertTrue("CLASS_LEVEL:b" in names)
    }

    @Test
    fun testKeywordArgumentAndComparisonAreNotDeclarations() {
        assertEquals(listOf("CLASS_LEVEL:x"), declared("class C(B):\n    x = f(a=1)\n"))
        assertEquals(listOf("CLASS_LEVEL:x"), declared("class C(B):\n    x = y == z\n"))
    }

    @Test
    fun testUnicodeIdentifiers() {
        // Грамматика сканера та же, что у фильтра имён: ASCII-приближение
        // теряло бы эти объявления, хотя `isOfferable` их принимает.
        assertTrue(
            "SELF:я" in declared("class C(B):\n    def __init__(self):\n        self.я = 1\n"),
        )
        assertTrue("DEF:имя" in declared("class C(B):\n    def имя(self):\n        return 1\n"))
        assertTrue("CLASS_LEVEL:лимит" in declared("class C(B):\n    лимит = 5\n"))
    }

    @Test
    fun testSingleLineSuiteGivesClassLevelDeclaration() {
        assertTrue("CLASS_LEVEL:LIMIT" in declared("class C(B):\n    if flag: LIMIT = 5\n"))
    }

    @Test
    fun testSingleLineSuiteIsParsedWhole() {
        // `pass; LIMIT = 5` — два простых оператора, и объявление стоит во
        // втором: разбор «до первой точки с запятой» терял бы его молча.
        assertTrue(
            "CLASS_LEVEL:LIMIT" in declared("class C(B):\n    if flag: pass; LIMIT = 5\n"),
        )
        assertEquals(
            listOf("CLASS_LEVEL:A", "CLASS_LEVEL:B"),
            declared("class C(B):\n    A = 1; B = 2\n"),
        )
        // Точка с запятой внутри скобок оператора не разделяет: там она
        // невозможна, а в литерале — уже замаскирована.
        assertEquals(
            listOf("CLASS_LEVEL:A", "CLASS_LEVEL:B"),
            declared("class C(B):\n    A = {\"a;b\": 1}; B = 2\n"),
        )
    }

    @Test
    fun testInlineClassBodyIsParsed() {
        // `class C(B): fields = […]` — законный Python; ветка `class` завершает
        // обработку строки, поэтому тело на ней нужно разобрать отдельно.
        assertTrue("CLASS_LEVEL:LIMIT" in declared("class C(B): LIMIT = 5\n"))
        assertTrue("__slots__" in blockers("class C(B): __slots__ = ()\n"))
    }

    @Test
    fun testUnicodeNameIsNotAKeyword() {
        // Граница `\b` считает словом только ASCII, поэтому `ifя` выглядел бы
        // как `if` с телом ` я: int = 1`, и классу приписывался бы атрибут `int`.
        assertEquals(listOf("CLASS_LEVEL:ifя"), declared("class C(B):\n    ifя: int = 1\n"))
        assertEquals(listOf("CLASS_LEVEL:forя"), declared("class C(B):\n    forя = 1\n"))
    }

    @Test
    fun testClassLevelAssignment() {
        assertTrue("CLASS_LEVEL:LIMIT" in declared("class C(B):\n    LIMIT = 5\n"))
        assertTrue("CLASS_LEVEL:LIMIT" in declared("class C(B):\n    LIMIT: int = 5\n"))
    }

    @Test
    fun testBareClassLevelAnnotationIsNotDeclaration() {
        // Именно из-за неё имена полей, продублированные аннотациями в BaseUser
        // и User, вернулись бы в словарь после того, как свёртка их отбросила.
        assertFalse(
            "CLASS_LEVEL:variables" in declared("class C(B):\n    variables: Variables\n"),
        )
    }

    @Test
    fun testDefIncludingProperties() {
        val names = declared(
            "class C(B):\n" +
                "    @property\n" +
                "    def raw(self):\n" +
                "        return 1\n" +
                "\n" +
                "    @cached_property\n" +
                "    def parametrizer(self):\n" +
                "        return None\n",
        )
        assertTrue("DEF:raw" in names)
        assertTrue("DEF:parametrizer" in names)
    }

    @Test
    fun testSelfCollectedAtAnyDepth() {
        // Условное присваивание всё равно создаёт атрибут в той ветке, где
        // выполнится; over-approximation объявлена контрактом.
        val names = declared(
            "class C(B):\n" +
                "    def __init__(self):\n" +
                "        if debug:\n" +
                "            self.deep = 1\n" +
                "        with lock:\n" +
                "            self.inner = 2\n",
        )
        assertTrue("SELF:deep" in names)
        assertTrue("SELF:inner" in names)
    }

    @Test
    fun testDeclarationRangePointsAtName() {
        val source = "class C(B):\n    def __init__(self):\n        self.marker = 1\n"
        val declaration = first(source).declarations.firstOrNull { it.name == "marker" }
        assertNotNull(declaration)
        assertEquals("marker", source.substring(declaration!!.nameStart, declaration.nameEnd))
    }

    @Test
    fun testFieldRangePointsAtLiteralContent() {
        val source = "class C(B):\n" +
            "    @property\n" +
            "    def fields(self):\n" +
            "        return [Field('marker', M)]\n"
        val declaration = first(source).declarations.first { it.origin == DeclarationOrigin.FIELD }
        assertEquals("marker", source.substring(declaration.nameStart, declaration.nameEnd))
    }

    @Test
    fun testClassNameRangeIsNavigable() {
        val source = "class CustomUser(B):\n    pass\n"
        val cls = first(source)
        assertEquals("CustomUser", source.substring(cls.nameStart, cls.nameEnd))
    }

    // --- гасители диагностики ---

    @Test
    fun testInitWithoutSuperOnClassWithBase() {
        assertTrue(
            "initWithoutSuper" in
                blockers("class C(B):\n    def __init__(self):\n        self.x = 1\n"),
        )
    }

    @Test
    fun testInitWithSuperIsNotBlocker() {
        assertFalse(
            "initWithoutSuper" in
                blockers("class C(B):\n    def __init__(self):\n        super().__init__()\n"),
        )
    }

    @Test
    fun testInitOnClassWithoutBaseIsNotBlocker() {
        // Обоснование «базовые self.* не создаются» к безбазовому классу
        // неприменимо: базы у него нет.
        assertFalse(
            "initWithoutSuper" in
                blockers("class C:\n    def __init__(self):\n        self.x = 1\n"),
        )
    }

    @Test
    fun testDunderOtherThanInit() {
        assertTrue(
            "dunderDefExceptInit" in
                blockers("class C(B):\n    def __getattr__(self, name):\n        return 1\n"),
        )
    }

    @Test
    fun testClassDecorator() {
        assertTrue("classDecorator" in blockers("@dataclass\nclass C(B):\n    pass\n"))
    }

    @Test
    fun testMethodDecoratorIsNotClassDecorator() {
        assertFalse(
            "classDecorator" in
                blockers("class C(B):\n    @property\n    def raw(self):\n        return 1\n"),
        )
    }

    @Test
    fun testMetaclassInHeader() {
        assertTrue("metaclassInBases" in blockers("class C(B, metaclass=M):\n    pass\n"))
    }

    @Test
    fun testContractTokenInBody() {
        assertTrue(
            "setattr(" in
                blockers("class C(B):\n    def go(self):\n        setattr(self, \"x\", 1)\n"),
        )
    }

    @Test
    fun testTokenInDocstringIsNotBlocker() {
        // Токены ищутся по маскированному тексту: содержимое строк и
        // комментариев стёрто, иначе docstring гасил бы диагностику всего
        // приложения.
        assertEquals(
            emptySet<String>(),
            blockers("class C(B):\n    \"\"\"Тут упомянут setattr( и __getattr__.\"\"\"\n    pass\n"),
        )
    }

    @Test
    fun testNestedClassDoesNotPolluteOuter() {
        // `stack[0]` остаётся внешним классом и внутри вложенного, поэтому сбор
        // для внешнего прекращается при появлении второго class-блока.
        val cls = first(
            "class Outer(B):\n" +
                "    def __init__(self):\n" +
                "        super().__init__()\n" +
                "\n" +
                "    class Inner:\n" +
                "        def __getattr__(self, n):\n" +
                "            return 1\n" +
                "\n" +
                "        def go(self):\n" +
                "            self.polluted = 1\n",
        )
        assertEquals(emptySet<String>(), cls.blockers)
        assertFalse(cls.declarations.any { it.name == "polluted" })
    }

    @Test
    fun testLastInitDefinitionWins() {
        // Так работает Python; первое определение с `super()` разрешило бы
        // диагностику там, где действующий метод базу не зовёт.
        val cls = first(
            "class C(B):\n" +
                "    def __init__(self):\n" +
                "        super().__init__()\n" +
                "\n" +
                "    def __init__(self):\n" +
                "        self.x = 1\n",
        )
        assertTrue("initWithoutSuper" in cls.blockers)
    }

    @Test
    fun testSuperInsideExpressionIsNotProof() {
        // Вхождения мало: вызов внутри условного выражения или справа от `=`
        // выполняется не всегда, а `callsSuper` — это доказательство.
        val bodies = listOf(
            "        super().__init__() if flag else None\n",
            "        flag and super().__init__()\n",
            "        x = super().__init__() if flag else 0\n",
        )
        for (body in bodies) {
            val cls = first("class C(B):\n    def __init__(self):\n$body")
            assertTrue(body, "initWithoutSuper" in cls.blockers)
        }
    }

    @Test
    fun testSingleLineDefBodyIsScanned() {
        // Ветка `def` завершает обработку строки, поэтому тело на той же строке
        // нужно разобрать отдельно — иначе теряются и объявление, и `super()`.
        assertTrue(
            "SELF:x" in declared("class C(B):\n    def __init__(self): self.x = 1\n"),
        )
        assertFalse(
            "initWithoutSuper" in
                blockers("class C(B):\n    def __init__(self): super().__init__()\n"),
        )
    }

    @Test
    fun testSingleLineFieldsBodyIsParsed() {
        // Форма та же, что у многострочного тела, и терять её поля незачем.
        val cls = first("class C(B):\n    def fields(self): return [Field('own', M)]\n")
        assertEquals(FieldsState.WITHOUT_SUPER, cls.fieldsState)
        assertEquals(listOf("own"), fieldNames(cls))
    }

    @Test
    fun testLocalOfSingleLineMethodIsNotClassAttribute() {
        // Сбор имён уровня класса идёт раньше ветки `def`, поэтому общий с ней
        // разбор тела приписывал бы классу локальные переменные метода.
        assertEquals(listOf("DEF:f"), declared("class C(B):\n    def f(self): x = 1\n"))
        assertEquals(
            listOf("DEF:f"),
            declared("class C(B):\n    def f(self) -> Dict[str, int]: y = 2\n"),
        )
    }

    @Test
    fun testClassInsideMatchIsNotModuleLevel() {
        // `match` — мягкое ключевое слово, и без его разбора блок не попадал в
        // стек: `stack.isEmpty()` оказывался истиной, и условный класс входил в
        // модель. Для `if` эта защита была с самого начала.
        val module = SmartAppResourceScanner.parseModule(
            "match mode:\n    case \"a\":\n        class C:\n            pass\n",
        )
        assertEquals(emptyList<String>(), module.classes.map { it.name })
    }

    @Test
    fun testMatchAndCaseAsNamesStayAssignments() {
        // `match = 1` и `case = 2` — законный Python, заголовком их считать нельзя.
        assertTrue("CLASS_LEVEL:match" in declared("class C(B):\n    match = 1\n"))
        assertTrue("CLASS_LEVEL:match" in declared("class C(B):\n    match = {1: 2}\n"))
    }

    @Test
    fun testSoftKeywordWithExpressionTailIsNotHeader() {
        // Подлежащее `match` не может начаться с `.` или `,`: это обращение к
        // атрибуту, то есть присваивание. Считая его заголовком, сканер брал
        // «тело» ` int = 1` и приписывал классу атрибут `int`.
        assertEquals(emptyList<String>(), declared("class C(B):\n    match.foo: int = 1\n"))
        assertEquals(emptyList<String>(), declared("class C(B):\n    case.foo: int = 1\n"))
        // Пробел перед точкой ничего не меняет — решает первый значимый символ.
        assertEquals(emptyList<String>(), declared("class C(B):\n    match .foo: int = 1\n"))
        assertEquals(emptyList<String>(), declared("class C(B):\n    match, x = f()\n"))
    }

    @Test
    fun testSubscriptSubjectStaysHeader() {
        // `match [1, 2]:` — законный заголовок, `match[0]: int = 1` — законное
        // присваивание; по одной строке они не различаются, и выбран заголовок:
        // содержимое блока становится условным, а не наоборот.
        assertEquals(
            listOf("CLASS_LEVEL:int"),
            declared("class C(B):\n    match[0]: int = 1\n"),
        )
        val module = SmartAppResourceScanner.parseModule(
            "match [1, 2]:\n    case 1:\n        class D:\n            pass\n",
        )
        assertEquals(emptyList<String>(), module.classes.map { it.name })
    }

    @Test
    fun testConditionalSuperIsNotProof() {
        // Метод берётся только из непосредственно объемлющего блока, поэтому
        // любой управляющий или вложенный блок между `def` и вызовом прячет
        // его — направление отказа безопасное.
        val bodies = listOf(
            "        if f:\n            super().__init__()\n",
            "        if f: super().__init__()\n",
            "        try:\n            super().__init__()\n        except E:\n            pass\n",
            "        def inner():\n            super().__init__()\n",
        )
        for (body in bodies) {
            val cls = first("class C(B):\n    def __init__(self):\n$body")
            assertTrue("условный super() в '$body'", "initWithoutSuper" in cls.blockers)
        }
    }

    @Test
    fun testConditionalDefDisablesDiagnostics() {
        // Условный `def fields` — переопределение, про которое неизвестно,
        // действует ли оно. Учесть его как обычное значило бы свернуть поля
        // базы по ветке, которая может не выполниться; промолчать — объявить
        // класс чистым.
        val source = "class C(B):\n    if enabled:\n        @property\n" +
            "        def fields(self):\n            return [Field('own', M)]\n"
        assertEquals(setOf("conditionalDef"), blockers(source))
        // Имя при этом предлагается: over-approximation объявлена контрактом.
        assertTrue("DEF:fields" in declared(source))
        // Форма условного `fields` действующей не считается.
        assertEquals(FieldsState.ABSENT, first(source).fieldsState)
    }

    @Test
    fun testConditionalInitIsNotAnOrdinaryInit() {
        // `hasInit` от него не ставится: `initWithoutSuper` — утверждение о том,
        // что базовые `self.*` не создаются, а по условной ветке его не сделать.
        val cls = first(
            "class C(B):\n    if flag:\n        def __init__(self):\n            self.x = 1\n",
        )
        assertFalse("initWithoutSuper" in cls.blockers)
        assertTrue("conditionalDef" in cls.blockers)
    }

    @Test
    fun testNestedDefIsNotAConditionalMethod() {
        // Локальная функция атрибутом класса не является ни при каких условиях.
        val source = "class C(B):\n    def m(self):\n        if f:\n" +
            "            def inner():\n                pass\n"
        assertFalse("conditionalDef" in first(source).blockers)
        assertEquals(listOf("DEF:m"), declared(source))
    }

    @Test
    fun testAssignmentInSingleLineCaseIsConditional() {
        // `case "x": USER = C` — заголовок с телом на той же строке. Без
        // разбора переменная выглядела бы не заданной вовсе, и резолвер молча
        // подставил бы библиотечный класс по умолчанию.
        val module = SmartAppResourceScanner.parseModule(
            "match mode:\n    case \"x\": USER = C\n",
        )
        assertTrue("USER" in module.conditionalVars)
    }

    @Test
    fun testTokenInCommentIsNotBlocker() {
        assertEquals(
            emptySet<String>(),
            blockers("class C(B):\n    # setattr( и __slots__\n    pass\n"),
        )
    }

    // --- состав гасителей совпадает с контрактом ---

    /** Источник, поднимающий ровно один структурный гаситель. */
    private val constructSources = mapOf(
        "dunderDefExceptInit" to "class C(B):\n    def __getattr__(self, n):\n        return 1\n",
        "classDecorator" to "@dataclass\nclass C(B):\n    pass\n",
        "metaclassInBases" to "class C(B, metaclass=M):\n    pass\n",
        "initWithoutSuper" to "class C(B):\n    def __init__(self):\n        self.x = 1\n",
        "unparsedFieldsProperty" to
            "class C(B):\n    @property\n    def fields(self):\n        return [*super().fields]\n",
        "conditionalDef" to
            "class C(B):\n    if flag:\n        def fields(self):\n            return [1]\n",
    )

    /** Гасители-конструкты: всё, что не пришло из списка токенов контракта. */
    private fun constructsOf(source: String): Set<String> =
        blockers(source) - UserModelSpec.blockerTokens

    @Test
    fun testEveryContractConstructIsRaised() {
        for (name in UserModelSpec.blockerConstructs) {
            val source = constructSources[name]
                ?: error("в таблице нет источника для конструкта $name")
            assertTrue("конструкт '$name' не поднялся", name in constructsOf(source))
        }
    }

    @Test
    fun testScannerRaisesNoConstructsOutsideContract() {
        // Имена конструктов — литералы в коде обеих реализаций, а контракт
        // хранит их состав. Без этой сверки переименование в контракте прошло
        // бы молча: сканер продолжал бы эмитить старое имя.
        val emitted = constructSources.values.flatMap { constructsOf(it) }.toSortedSet()
        assertEquals(UserModelSpec.blockerConstructs.toSortedSet(), emitted)
    }
}
