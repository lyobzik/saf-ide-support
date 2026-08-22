import { describe, expect, it } from "vitest";
import { parseModule } from "../../src/core/pythonScan";

/**
 * Таблица форм сканера ресурсов. Те же входы обязан проходить Kotlin-сканер:
 * расхождение двух реализаций иначе не ловится ничем (план, раздел 5).
 */

/** Оборачивает тело метода в класс ресурсов — минимальный валидный контекст. */
const inMethod = (body: string, method = "init_actions"): string =>
  `class CustomAppResources(SmartAppResources):\n    def ${method}(self):\n        ${body}\n`;

const registrationsOf = (source: string) =>
  parseModule(source).classes.flatMap((cls) => cls.methods.flatMap((m) => [...m.registrations]));

describe("принимаемые формы регистрации", () => {
  it.each([
    ['actions["custom_action"] = CustomAction', "actions", "custom_action", "CustomAction"],
    ["actions['custom_action'] = CustomAction", "actions", "custom_action", "CustomAction"],
    ['ffd.field_filler_description["custom_filler"] = C', "field_filler_description", "custom_filler", "C"],
    ['core.basic_models.actions.basic_actions.actions["x"] = C', "actions", "x", "C"],
    ['actions["""triple"""] = C', "actions", "triple", "C"],
    ['actions["esc\\u0041"] = mod.CustomAction', "actions", "escA", "mod.CustomAction"],
  ])("%s", (line, registry, name, className) => {
    const found = registrationsOf(inMethod(line as string));
    expect(found).toHaveLength(1);
    expect(found[0]?.registry).toBe(registry);
    expect(found[0]?.name).toBe(name);
    expect(found[0]?.className).toBe(className);
  });

  it("update со словарным литералом даёт все ключи", () => {
    const found = registrationsOf(inMethod('actions.update({"a": A, \'b\': B})'));
    expect(found.map((r) => r.name)).toEqual(["a", "b"]);
    expect(found.map((r) => r.className)).toEqual(["A", "B"]);
  });

  it("перенос строки внутри скобок не разрывает выражение", () => {
    const source =
      "class R(Base):\n    def init_actions(self):\n        actions.update({\n            \"a\": A,\n            \"b\": B,\n        })\n";
    expect(registrationsOf(source).map((r) => r.name)).toEqual(["a", "b"]);
  });

  it("диапазон имени — сырое содержимое литерала без кавычек", () => {
    const source = inMethod('actions["esc\\u0041"] = C');
    const found = registrationsOf(source)[0];
    expect(source.slice(found?.nameStart, found?.nameEnd)).toBe("esc\\u0041");
    expect(found?.name).toBe("escA");
  });
});

describe("отвергаемые формы", () => {
  it.each([
    ["actions[name] = C"],
    ["actions[NAME_CONST] = C"],
    ['actions[f"{p}_x"] = C'],
    ['actions["a" + "b"] = C'],
    ["actions.update(mapping)"],
    ["actions.update(**kwargs)"],
    ['if actions["x"] == C:'],
  ])("%s", (line) => {
    expect(registrationsOf(inMethod(line as string))).toEqual([]);
  });

  it("регистрация вне метода init_* не считается", () => {
    expect(registrationsOf(inMethod('actions["x"] = C', "configure"))).toEqual([]);
  });

  it("строки и комментарии не разбираются", () => {
    const source = inMethod('# actions["commented"] = C\n        doc = """actions["inside"] = C"""');
    expect(registrationsOf(source)).toEqual([]);
  });

  it("docstring класса не даёт ложных имён", () => {
    const source =
      'class R(Base):\n    """Присвойте RESOURCES этот класс: actions["fake"] = C"""\n    def init_actions(self):\n        actions["real"] = C\n';
    expect(registrationsOf(source).map((r) => r.name)).toEqual(["real"]);
  });
});

describe("структура модуля", () => {
  const source = [
    "from smart_kit.resources import SmartAppResources",
    "from app.basic_entities.actions import CustomAction as Action",
    "import app.adapters.db_adapters as adapters",
    "",
    "class CustomAppResources(SmartAppResources):",
    "    def init_actions(self):",
    "        super().init_actions()",
    '        actions["custom_action"] = Action',
    "",
    "    def init_requirements(self):",
    '        requirements["custom_requirement"] = R',
    "",
    "RESOURCES = CustomAppResources",
  ].join("\n");

  it("читает базы, методы, super() и импорты", () => {
    const module = parseModule(source);
    expect(module.classes).toHaveLength(1);
    const cls = module.classes[0];
    expect(cls?.name).toBe("CustomAppResources");
    expect(cls?.bases).toEqual(["SmartAppResources"]);
    expect(cls?.methods.map((m) => [m.name, m.callsSuper])).toEqual([
      ["init_actions", true],
      ["init_requirements", false],
    ]);
    expect(module.imports.get("Action")).toEqual({
      module: "app.basic_entities.actions",
      name: "CustomAction",
    });
    expect(module.moduleImports.get("adapters")).toBe("app.adapters.db_adapters");
    expect(module.topLevelVars.get("RESOURCES")).toBe("CustomAppResources");
    expect(module.conditionalVars.has("RESOURCES")).toBe(false);
  });

  it("ключевые аргументы заголовка базой не считаются", () => {
    const module = parseModule("class C(Base, metaclass=M):\n    pass\n");
    expect(module.classes[0]?.bases).toEqual(["Base"]);
  });

  it("присваивание RESOURCES внутри ветки помечается условным", () => {
    const module = parseModule("if dev:\n    RESOURCES = DevResources\nelse:\n    RESOURCES = ProdResources\n");
    expect(module.conditionalVars.has("RESOURCES")).toBe(true);
    expect(module.topLevelVars.has("RESOURCES")).toBe(false);
  });
});

describe("управляющие конструкции в теле метода", () => {
  // Регистрация под `if`/`for`/`try`/`with` условна: в рантайме её может не
  // быть, поэтому принимаются только прямые операторы тела метода.
  it.each([
    ["if enabled:", "conditional"],
    ["for name in names:", "looped"],
    ["try:", "guarded"],
    ["with lock:", "locked"],
  ])("%s", (header) => {
    const source =
      "class R(Base):\n    def init_actions(self):\n" +
      `        ${header}\n` +
      '            actions["nested"] = C\n' +
      '        actions["direct"] = C\n';
    expect(registrationsOf(source).map((r) => r.name)).toEqual(["direct"]);
  });

  it("super() внутри условия не считается вызовом", () => {
    const source =
      "class R(Base):\n    def init_actions(self):\n        if enabled:\n            super().init_actions()\n";
    const method = parseModule(source).classes[0]?.methods[0];
    expect(method?.callsSuper).toBe(false);
  });
});

describe("update: только ключи верхнего уровня", () => {
  it("вложенный словарь ключей не даёт", () => {
    const source =
      "class R(Base):\n    def init_actions(self):\n" +
      "        actions.update({\n" +
      '            "outer": {\n' +
      '                "inner": CustomAction,\n' +
      "            },\n" +
      "        })\n";
    expect(registrationsOf(source).map((r) => r.name)).toEqual(["outer"]);
  });

  it("список значений тоже не даёт ключей", () => {
    const source =
      "class R(Base):\n    def init_actions(self):\n" +
      '        actions.update({"outer": ["inner", "second"]})\n';
    expect(registrationsOf(source).map((r) => r.name)).toEqual(["outer"]);
  });
});

describe("escape-последовательности", () => {
  const nameOf = (literal: string): string | undefined =>
    registrationsOf(inMethod(`actions[${literal}] = C`))[0]?.name;

  it.each([
    ['"a\\u0041b"', "aAb"],
    ['"a\\x41b"', "aAb"],
    ['"a\\\\b"', "a\\b"],
    ['"a\\nb"', "a\nb"],
    ["'it\\'s'", "it's"],
  ])("%s -> %s", (literal, expected) => {
    expect(nameOf(literal as string)).toBe(expected);
  });

  it.each([
    ['"bell\\a"'],
    ['"back\\b"'],
    ['"octal\\101"'],
    ['"named\\N{BULLET}"'],
    ['"huge\\U00110000"'],
  ])("не поддерживается: %s", (literal) => {
    // Контракт escape сознательно узкий: подставить не то имя хуже, чем никакого.
    expect(nameOf(literal as string)).toBeUndefined();
  });

  it("raw-строка: экранированная кавычка не завершает литерал", () => {
    const found = registrationsOf(inMethod('actions[r"a\\"b"] = C'));
    expect(found.map((r) => r.name)).toEqual(['a\\"b']);
  });
});

describe("однострочные конструкции и границы", () => {
  it("однострочный suite не даёт регистрации", () => {
    const source =
      "class R(Base):\n    def init_actions(self):\n" +
      '        if enabled: actions["wrong"] = C\n' +
      '        actions["direct"] = C\n';
    expect(registrationsOf(source).map((r) => r.name)).toEqual(["direct"]);
  });

  it("однострочный super() в условии не считается вызовом", () => {
    const source =
      "class R(Base):\n    def init_actions(self):\n        if enabled: super().init_actions()\n";
    expect(parseModule(source).classes[0]?.methods[0]?.callsSuper).toBe(false);
  });

  it("однострочный if на верхнем уровне помечает переменную условной", () => {
    const module = parseModule("RESOURCES = ProdResources\nif dev: RESOURCES = DevResources\n");
    expect(module.conditionalVars.has("RESOURCES")).toBe(true);
  });

  it.each([
    ["my_super().init_actions()"],
    ["obj.super().init_actions()"],
  ])("%s не считается вызовом super()", (call) => {
    const source = `class R(Base):\n    def init_actions(self):\n        ${call}\n`;
    expect(parseModule(source).classes[0]?.methods[0]?.callsSuper).toBe(false);
  });

  it("настоящий super() распознаётся", () => {
    const source =
      "class R(Base):\n    def init_actions(self):\n        super().init_actions()\n";
    expect(parseModule(source).classes[0]?.methods[0]?.callsSuper).toBe(true);
  });

  it.each([
    ["from app.resources import R  # комментарий"],
    ["from app.resources    import R"],
  ])("импорт разбирается: %s", (line) => {
    const module = parseModule(`${line}\nRESOURCES = R\n`);
    expect(module.imports.get("R")).toEqual({ module: "app.resources", name: "R" });
  });

  it("вложенный класс внутри init_* ресурсным не считается", () => {
    const source =
      "class R(Base):\n    def init_actions(self):\n" +
      "        class Nested:\n" +
      "            def init_actions(self):\n" +
      '                actions["wrong"] = C\n';
    expect(registrationsOf(source)).toEqual([]);
    // Вложенный класс не попадает и в модель модуля.
    expect(parseModule(source).classes.map((c) => c.name)).toEqual(["R"]);
  });

  it("восьмеричный escape отвергается целиком", () => {
    expect(registrationsOf(inMethod('actions["a\\012b"] = C'))).toEqual([]);
    // Одиночный \\0 — это NUL, он поддержан.
    expect(registrationsOf(inMethod('actions["a\\0b"] = C'))[0]?.name).toBe("a\0b");
  });
});

describe("условные объявления верхнего уровня", () => {
  it("класс внутри многострочного if модульным не считается", () => {
    const module = parseModule(
      "if dev:\n" +
        "    class CustomAppResources(SmartAppResources):\n" +
        "        def init_actions(self):\n" +
        '            actions["conditional"] = C\n',
    );
    expect(module.classes).toEqual([]);
  });

  it("класс внутри try тоже условный", () => {
    const module = parseModule(
      "try:\n    class R(SmartAppResources):\n        pass\nexcept ImportError:\n    R = None\n",
    );
    expect(module.classes).toEqual([]);
  });

  it("класс после условного блока остаётся модульным", () => {
    const module = parseModule(
      "if dev:\n    x = 1\n\nclass R(SmartAppResources):\n    def init_actions(self):\n        pass\n",
    );
    expect(module.classes.map((c) => c.name)).toEqual(["R"]);
  });
});
