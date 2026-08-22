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
