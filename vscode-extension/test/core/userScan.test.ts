import { describe, expect, it } from "vitest";

import { userModel } from "../../src/core/contract";
import { parseModule, type PyClass } from "../../src/core/pythonScan";

/**
 * Разбор класса модели пользователя: какие имена он объявляет, что за состояние
 * у свойства `fields` и какие гасители диагностики сработали.
 *
 * Та же таблица входов прогоняется в плагине (`SmartAppUserScanTest`).
 */

const first = (source: string): PyClass => {
  const cls = parseModule(source).classes[0];
  if (cls === undefined) throw new Error("класс не разобран");
  return cls;
};

/** Объявления как `origin:name` — origin существен, места объявления разные. */
const declared = (source: string): string[] =>
  first(source).declarations.map((d) => `${d.origin}:${d.name}`);

const blockers = (source: string): string[] => [...first(source).blockers].sort();

describe("состояния свойства fields", () => {
  it("свойство не объявлено — absent", () => {
    expect(first("class C(B):\n    def other(self):\n        return 1\n").fieldsState).toBe(
      "absent",
    );
  });

  it("объявлено с super() — withSuper", () => {
    const cls = first(
      "class C(B):\n" +
        "    @property\n" +
        "    def fields(self):\n" +
        "        return super().fields + [Field('own', M)]\n",
    );
    expect(cls.fieldsState).toBe("withSuper");
    expect(cls.declarations.filter((d) => d.origin === "field").map((d) => d.name)).toEqual([
      "own",
    ]);
  });

  it("объявлено без super() — withoutSuper", () => {
    expect(
      first(
        "class C(B):\n" +
          "    @property\n" +
          "    def fields(self):\n" +
          "        return [Field('own', M)]\n",
      ).fieldsState,
    ).toBe("withoutSuper");
  });

  it("многострочный список остаётся одной логической строкой", () => {
    const cls = first(
      "class C(B):\n" +
        "    @property\n" +
        "    def fields(self):\n" +
        "        return super().fields + [Field('a', A),\n" +
        "                                 Field('b', B)]\n",
    );
    expect(cls.fieldsState).toBe("withSuper");
    expect(cls.declarations.filter((d) => d.origin === "field").map((d) => d.name)).toEqual([
      "a",
      "b",
    ]);
  });

  it("распаковка делает форму неразобранной, но имена извлекаются", () => {
    // Поля базы при этом сохраняются, а диагностика гасится: молча потерять
    // пол хуже, чем предложить лишнее.
    const cls = first(
      "class C(B):\n" +
        "    @property\n" +
        "    def fields(self):\n" +
        "        return [*super().fields, Field('own', M)]\n",
    );
    expect(cls.fieldsState).toBe("unparsed");
    expect(cls.blockers.has("unparsedFieldsProperty")).toBe(true);
    expect(cls.declarations.filter((d) => d.origin === "field").map((d) => d.name)).toEqual([
      "own",
    ]);
  });

  it("посторонний элемент списка делает форму неразобранной", () => {
    expect(
      first(
        "class C(B):\n" +
          "    @property\n" +
          "    def fields(self):\n" +
          "        return [Field('a', A), other_field()]\n",
      ).fieldsState,
    ).toBe("unparsed");
  });

  it("вычисляемое имя поля делает форму неразобранной", () => {
    expect(
      first(
        "class C(B):\n" +
          "    @property\n" +
          "    def fields(self):\n" +
          "        return [Field(name_var, A)]\n",
      ).fieldsState,
    ).toBe("unparsed");
  });

  it("имена полей извлекаются и из присваивания уровня класса", () => {
    // План обещает извлекать распознанные `Field(...)` из **любой** формы
    // свойства; форма влияет только на состояние и на гаситель.
    const cls = first("class C(B):\n    fields = [Field('own', M)]\n");
    expect(cls.declarations.filter((d) => d.origin === "field").map((d) => d.name)).toEqual([
      "own",
    ]);
  });

  it("условное присваивание fields тоже неразобранная форма", () => {
    // Однострочный suite разбирается со сдвигом за двоеточие. Без этого поле
    // терялось молча — вместе с гасителем, то есть диагностика оставалась
    // включённой по неполному словарю.
    for (const source of [
      "class C(B):\n    if flag: fields = [Field('x', M)]\n",
      "class C(B):\n    if flag:\n        fields = [Field('x', M)]\n",
    ]) {
      const cls = first(source);
      expect(cls.fieldsState).toBe("unparsed");
      expect(cls.blockers.has("unparsedFieldsProperty")).toBe(true);
      expect(cls.declarations.filter((d) => d.origin === "field").map((d) => d.name)).toEqual([
        "x",
      ]);
    }
  });

  it("присваивание вместо свойства — неразобранная форма", () => {
    // `fields = […]` переопределяет свойство так же, как `def fields`, но
    // разобрать его тем же правилом нельзя: это не тело метода. Считать такой
    // класс «свойство не объявлено» значило бы взять поля базы там, где их
    // могли отбросить.
    const cls = first("class C(B):\n    fields = [Field('a', A)]\n");
    expect(cls.fieldsState).toBe("unparsed");
    expect(cls.blockers.has("unparsedFieldsProperty")).toBe(true);
  });

  it("тело из нескольких операторов — неразобранная форма", () => {
    const cls = first(
      "class C(B):\n" +
        "    @property\n" +
        "    def fields(self):\n" +
        "        base = super().fields\n" +
        "        return base\n",
    );
    expect(cls.fieldsState).toBe("unparsed");
  });
});

describe("формы объявления имён", () => {
  it("self с значением и без аннотации", () => {
    expect(declared("class C(B):\n    def __init__(self):\n        self.x = 1\n")).toContain(
      "self:x",
    );
  });

  it("self с аннотацией и значением", () => {
    expect(declared("class C(B):\n    def __init__(self):\n        self.x: int = 1\n")).toContain(
      "self:x",
    );
  });

  it("голая аннотация self объявлением не является", () => {
    // `self.x: int` в рантайме ничего не создаёт.
    expect(declared("class C(B):\n    def __init__(self):\n        self.x: int\n")).not.toContain(
      "self:x",
    );
  });

  it("цепочка присваиваний уровня класса даёт все имена", () => {
    // `a = b = 1` создаёт два атрибута.
    const names = declared("class C(B):\n    a = b = 1\n");
    expect(names).toContain("class:a");
    expect(names).toContain("class:b");
  });

  it("именованный аргумент и сравнение объявлением не считаются", () => {
    expect(declared("class C(B):\n    x = f(a=1)\n")).toEqual(["class:x"]);
    expect(declared("class C(B):\n    x = y == z\n")).toEqual(["class:x"]);
  });

  it("юникодные идентификаторы разбираются наравне с ASCII", () => {
    // Грамматика сканера та же, что у фильтра имён: ASCII-приближение теряло бы
    // эти объявления, хотя `isOfferable` их принимает.
    expect(declared("class C(B):\n    def __init__(self):\n        self.я = 1\n")).toContain(
      "self:я",
    );
    expect(declared("class C(B):\n    def имя(self):\n        return 1\n")).toContain("def:имя");
    expect(declared("class C(B):\n    лимит = 5\n")).toContain("class:лимит");
  });

  it("однострочный suite уровня класса даёт объявление", () => {
    expect(declared("class C(B):\n    if flag: LIMIT = 5\n")).toContain("class:LIMIT");
  });

  it("однострочный suite разбирается целиком, а не до первого оператора", () => {
    // `pass; fields = […]` — два простых оператора, и переопределение стоит во
    // втором: разбор «до первой точки с запятой» терял бы его молча.
    expect(declared("class C(B):\n    if flag: pass; LIMIT = 5\n")).toContain("class:LIMIT");
    expect(declared("class C(B):\n    A = 1; B = 2\n")).toEqual(["class:A", "class:B"]);
    // Точка с запятой внутри скобок оператора не разделяет: там она невозможна,
    // а в литерале — уже замаскирована.
    expect(declared('class C(B):\n    A = {"a;b": 1}; B = 2\n')).toEqual([
      "class:A",
      "class:B",
    ]);
  });

  it("тело класса на строке заголовка разбирается", () => {
    // `class C(B): fields = […]` — законный Python; ветка `class` завершает
    // обработку строки, поэтому тело на ней нужно разобрать отдельно.
    expect(declared("class C(B): LIMIT = 5\n")).toContain("class:LIMIT");
    expect(blockers("class C(B): __slots__ = ()\n")).toContain("__slots__");
  });

  it("Unicode-имя не принимается за управляющее слово", () => {
    // Граница `\b` считает словом только ASCII, поэтому `ifя` выглядел бы как
    // `if` с телом ` я: int = 1`, и классу приписывался бы атрибут `int`.
    expect(declared("class C(B):\n    ifя: int = 1\n")).toEqual(["class:ifя"]);
    expect(declared("class C(B):\n    forя = 1\n")).toEqual(["class:forя"]);
  });

  it("имя уровня класса со значением", () => {
    expect(declared("class C(B):\n    LIMIT = 5\n")).toContain("class:LIMIT");
    expect(declared("class C(B):\n    LIMIT: int = 5\n")).toContain("class:LIMIT");
  });

  it("голая аннотация уровня класса объявлением не является", () => {
    // Именно из-за неё имена полей, продублированные аннотациями в BaseUser и
    // User, вернулись бы в словарь после того, как свёртка их отбросила.
    expect(declared("class C(B):\n    variables: Variables\n")).not.toContain("class:variables");
  });

  it("def, включая property и cached_property", () => {
    const names = declared(
      "class C(B):\n" +
        "    @property\n" +
        "    def raw(self):\n" +
        "        return 1\n" +
        "\n" +
        "    @cached_property\n" +
        "    def parametrizer(self):\n" +
        "        return None\n",
    );
    expect(names).toContain("def:raw");
    expect(names).toContain("def:parametrizer");
  });

  it("self собирается с любой глубины", () => {
    // Условное присваивание всё равно создаёт атрибут в той ветке, где
    // выполнится; over-approximation объявлена контрактом.
    const names = declared(
      "class C(B):\n" +
        "    def __init__(self):\n" +
        "        if debug:\n" +
        "            self.deep = 1\n" +
        "        with lock:\n" +
        "            self.inner = 2\n",
    );
    expect(names).toContain("self:deep");
    expect(names).toContain("self:inner");
  });

  it("диапазон имени указывает на само имя в исходнике", () => {
    const source = "class C(B):\n    def __init__(self):\n        self.marker = 1\n";
    const declaration = first(source).declarations.find((d) => d.name === "marker");
    expect(declaration).toBeDefined();
    expect(source.slice(declaration?.nameStart, declaration?.nameEnd)).toBe("marker");
  });

  it("диапазон имени поля указывает на содержимое литерала", () => {
    const source =
      "class C(B):\n" +
      "    @property\n" +
      "    def fields(self):\n" +
      "        return [Field('marker', M)]\n";
    const declaration = first(source).declarations.find((d) => d.origin === "field");
    expect(source.slice(declaration?.nameStart, declaration?.nameEnd)).toBe("marker");
  });

  it("имя класса даёт диапазон для перехода с корневой переменной", () => {
    const source = "class CustomUser(B):\n    pass\n";
    const cls = first(source);
    expect(source.slice(cls.nameStart, cls.nameEnd)).toBe("CustomUser");
  });
});

describe("гасители диагностики", () => {
  it("__init__ без super() у класса с базой", () => {
    expect(blockers("class C(B):\n    def __init__(self):\n        self.x = 1\n")).toContain(
      "initWithoutSuper",
    );
  });

  it("__init__ с super() гасителем не является", () => {
    expect(
      blockers("class C(B):\n    def __init__(self):\n        super().__init__()\n"),
    ).not.toContain("initWithoutSuper");
  });

  it("у класса без базы __init__ гасителем не является", () => {
    // Обоснование «базовые self.* не создаются» к безбазовому классу
    // неприменимо: базы у него нет.
    expect(blockers("class C:\n    def __init__(self):\n        self.x = 1\n")).not.toContain(
      "initWithoutSuper",
    );
  });

  it("dunder кроме __init__", () => {
    expect(blockers('class C(B):\n    def __getattr__(self, name):\n        return 1\n')).toContain(
      "dunderDefExceptInit",
    );
  });

  it("декоратор на самом классе", () => {
    expect(blockers("@dataclass\nclass C(B):\n    pass\n")).toContain("classDecorator");
  });

  it("декоратор на методе классовым не считается", () => {
    expect(
      blockers("class C(B):\n    @property\n    def raw(self):\n        return 1\n"),
    ).not.toContain("classDecorator");
  });

  it("metaclass в заголовке", () => {
    expect(blockers("class C(B, metaclass=M):\n    pass\n")).toContain("metaclassInBases");
  });

  it("токен из контракта в теле", () => {
    expect(blockers('class C(B):\n    def go(self):\n        setattr(self, "x", 1)\n')).toContain(
      "setattr(",
    );
  });

  it("токен в docstring гасителем не является", () => {
    // Токены ищутся по маскированному тексту: содержимое строк и комментариев
    // стёрто, иначе docstring гасил бы диагностику всего приложения.
    expect(
      blockers('class C(B):\n    """Тут упомянут setattr( и __getattr__."""\n    pass\n'),
    ).toEqual([]);
  });

  it("вложенный класс внешний не загрязняет", () => {
    // `stack[0]` остаётся внешним классом и внутри вложенного, поэтому сбор
    // для внешнего прекращается при появлении второго class-блока.
    const cls = first(
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
    );
    expect([...cls.blockers]).toEqual([]);
    expect(cls.declarations.map((d) => d.name)).not.toContain("polluted");
  });

  it("действующим считается последнее определение __init__", () => {
    // Так работает Python; первое определение с `super()` разрешило бы
    // диагностику там, где действующий метод базу не зовёт.
    const cls = first(
      "class C(B):\n" +
        "    def __init__(self):\n" +
        "        super().__init__()\n" +
        "\n" +
        "    def __init__(self):\n" +
        "        self.x = 1\n",
    );
    expect(cls.blockers.has("initWithoutSuper")).toBe(true);
  });

  it("super() в выражении доказательством не считается", () => {
    // Вхождения мало: вызов внутри условного выражения или справа от `=`
    // выполняется не всегда, а `callsSuper` — это доказательство.
    for (const body of [
      "        super().__init__() if flag else None\n",
      "        flag and super().__init__()\n",
      "        x = super().__init__() if flag else 0\n",
    ]) {
      const cls = first(`class C(B):\n    def __init__(self):\n${body}`);
      expect(cls.blockers.has("initWithoutSuper"), body).toBe(true);
    }
  });

  it("однострочное тело свойства fields разбирается", () => {
    // Форма та же, что у многострочного тела, и терять её поля незачем.
    const cls = first("class C(B):\n    def fields(self): return [Field('own', M)]\n");
    expect(cls.fieldsState).toBe("withoutSuper");
    expect(declared("class C(B):\n    def fields(self): return [Field('own', M)]\n")).toContain(
      "field:own",
    );
  });

  it("однострочное тело def разбирается", () => {
    // Ветка `def` завершает обработку строки, поэтому тело на той же строке
    // нужно разобрать отдельно — иначе теряются и объявление, и `super()`.
    expect(declared("class C(B):\n    def __init__(self): self.x = 1\n")).toContain("self:x");
    expect(
      first("class C(B):\n    def __init__(self): super().__init__()\n").blockers.has(
        "initWithoutSuper",
      ),
    ).toBe(false);
  });

  it("локальная переменная однострочного метода атрибутом класса не считается", () => {
    // Сбор имён уровня класса идёт раньше ветки `def`, поэтому общий с ней
    // разбор тела приписывал бы классу локальные переменные метода.
    expect(declared("class C(B):\n    def f(self): x = 1\n")).toEqual(["def:f"]);
    expect(declared("class C(B):\n    def f(self) -> Dict[str, int]: y = 2\n")).toEqual([
      "def:f",
    ]);
  });

  it("класс из ветки match модульным не считается", () => {
    // `match` — мягкое ключевое слово, и без его разбора блок не попадал в
    // стек: `stack.isEmpty()` оказывался истиной, и условный класс входил в
    // модель. Для `if` эта защита была с самого начала.
    expect(parseModule('match mode:\n    case "a":\n        class C:\n            pass\n').classes)
      .toEqual([]);
  });

  it("match и case как имена остаются присваиваниями", () => {
    // `match = 1` и `case = 2` — законный Python, заголовком их считать нельзя.
    expect(declared("class C(B):\n    match = 1\n")).toContain("class:match");
    expect(declared("class C(B):\n    match = {1: 2}\n")).toContain("class:match");
  });

  it("мягкое слово с продолжением выражения заголовком не считается", () => {
    // Подлежащее `match` не может начаться с `.` или `,`: это обращение к
    // атрибуту, то есть присваивание. Считая его заголовком, сканер брал
    // «тело» ` int = 1` и приписывал классу атрибут `int`.
    expect(declared("class C(B):\n    match.foo: int = 1\n")).toEqual([]);
    expect(declared("class C(B):\n    case.foo: int = 1\n")).toEqual([]);
    // Пробел перед точкой ничего не меняет — решает первый значимый символ.
    expect(declared("class C(B):\n    match .foo: int = 1\n")).toEqual([]);
    expect(declared("class C(B):\n    match, x = f()\n")).toEqual([]);
  });

  it("подлежащее в скобках остаётся заголовком", () => {
    // `match [1, 2]:` — законный заголовок, `match[0]: int = 1` — законное
    // присваивание; по одной строке они не различаются, и выбран заголовок:
    // содержимое блока становится условным, а не наоборот.
    expect(declared("class C(B):\n    match[0]: int = 1\n")).toEqual(["class:int"]);
    expect(
      parseModule("match [1, 2]:\n    case 1:\n        class D:\n            pass\n").classes,
    ).toEqual([]);
  });

  it("условный super() доказательством не считается", () => {
    // Метод берётся только из непосредственно объемлющего блока, поэтому любой
    // управляющий или вложенный блок между `def` и вызовом прячет его —
    // направление отказа безопасное.
    for (const body of [
      "        if f:\n            super().__init__()\n",
      "        if f: super().__init__()\n",
      "        try:\n            super().__init__()\n        except E:\n            pass\n",
      "        def inner():\n            super().__init__()\n",
    ]) {
      const cls = first(`class C(B):\n    def __init__(self):\n${body}`);
      expect(cls.blockers.has("initWithoutSuper")).toBe(true);
    }
  });

  it("условное определение метода гасит диагностику", () => {
    // Условный `def fields` — переопределение, про которое неизвестно, действует
    // ли оно. Учесть его как обычное значило бы свернуть поля базы по ветке,
    // которая может не выполниться; промолчать — объявить класс чистым.
    const conditional =
      "class C(B):\n    if enabled:\n        @property\n" +
      "        def fields(self):\n            return [Field('own', M)]\n";
    expect(blockers(conditional)).toEqual(["conditionalDef"]);
    // Имя при этом предлагается: over-approximation объявлена контрактом.
    expect(declared(conditional)).toContain("def:fields");
    // Форма условного `fields` действующей не считается — иначе поля базы
    // отбросились бы по ветке, которая может не выполниться.
    expect(first(conditional).fieldsState).toBe("absent");
  });

  it("условный __init__ обычным не считается", () => {
    // `hasInit` от него не ставится: `initWithoutSuper` — утверждение о том,
    // что базовые `self.*` не создаются, а по условной ветке его не сделать.
    const cls = first("class C(B):\n    if flag:\n        def __init__(self):\n            self.x = 1\n");
    expect(cls.blockers.has("initWithoutSuper")).toBe(false);
    expect(cls.blockers.has("conditionalDef")).toBe(true);
  });

  it("вложенный в метод def условным определением класса не считается", () => {
    // Локальная функция атрибутом класса не является ни при каких условиях.
    const cls = first("class C(B):\n    def m(self):\n        if f:\n            def inner():\n                pass\n");
    expect(cls.blockers.has("conditionalDef")).toBe(false);
    expect(declared("class C(B):\n    def m(self):\n        if f:\n            def inner():\n                pass\n")).toEqual([
      "def:m",
    ]);
  });

  it("присваивание в однострочной ветке case условно", () => {
    // `case "x": USER = C` — заголовок с телом на той же строке. Без разбора
    // переменная выглядела бы не заданной вовсе, и резолвер молча подставил бы
    // библиотечный класс по умолчанию.
    expect([...parseModule('match mode:\n    case "x": USER = C\n').conditionalVars]).toContain(
      "USER",
    );
  });

  it("токен в комментарии гасителем не является", () => {
    expect(blockers("class C(B):\n    # setattr( и __slots__\n    pass\n")).toEqual([]);
  });
});

describe("состав гасителей совпадает с контрактом", () => {
  /** Источник, поднимающий ровно один структурный гаситель. */
  const sources: Record<string, string> = {
    dunderDefExceptInit: "class C(B):\n    def __getattr__(self, n):\n        return 1\n",
    classDecorator: "@dataclass\nclass C(B):\n    pass\n",
    metaclassInBases: "class C(B, metaclass=M):\n    pass\n",
    initWithoutSuper: "class C(B):\n    def __init__(self):\n        self.x = 1\n",
    unparsedFieldsProperty:
      "class C(B):\n    @property\n    def fields(self):\n        return [*super().fields]\n",
    conditionalDef: "class C(B):\n    if flag:\n        def fields(self):\n            return [1]\n",
  };

  /** Гасители-конструкты: всё, что не пришло из списка токенов контракта. */
  const constructsOf = (source: string): string[] =>
    [...first(source).blockers].filter((name) => !userModel.blockerTokens.has(name));

  it("каждый конструкт контракта действительно поднимается сканером", () => {
    for (const name of userModel.blockerConstructs) {
      const source = sources[name];
      expect(source, `в таблице нет источника для конструкта ${name}`).toBeDefined();
      expect(constructsOf(source as string)).toContain(name);
    }
  });

  it("сканер не поднимает конструктов вне контракта", () => {
    // Имена конструктов — литералы в коде обеих реализаций, а контракт хранит
    // их состав. Без этой сверки переименование в контракте прошло бы молча:
    // сканер продолжал бы эмитить старое имя, и никто бы не заметил.
    const emitted = new Set(Object.values(sources).flatMap(constructsOf));
    expect([...emitted].sort()).toEqual([...userModel.blockerConstructs].sort());
  });
});
