import { describe, expect, it } from "vitest";
import { customKeywords, hasExcludedSegment, type ModuleReader } from "../../src/core/resourceKeywords";
import { SmartAppIndex } from "../../src/core/index";
import { documentContext } from "../../src/core/semantics";
import { semanticTokens, SemanticTokenType } from "../../src/core/semanticTokens";
import { completionAt } from "../../src/core/completion";

/**
 * Разрешение цепочки ресурсов: активный класс берётся из RESOURCES, а
 * регистрации цепочки сворачиваются по правилам Python (super() и
 * переопределение), а не объединяются.
 */

const reader = (files: Record<string, string>): ModuleReader => (path) => files[path];

const CONFIG = [
  "from app.resources.custom_app_resources import CustomAppResources",
  "RESOURCES = CustomAppResources",
].join("\n");

const cls = (name: string, base: string, body: string): string =>
  `class ${name}(${base}):\n${body}\n`;

describe("активный класс", () => {
  it("берётся из RESOURCES и находится по импорту", () => {
    const found = customKeywords(
      reader({
        "app_config.py": CONFIG,
        "app/resources/custom_app_resources.py": cls(
          "CustomAppResources",
          "SmartAppResources",
          '    def init_actions(self):\n        actions["custom_action"] = CustomAction',
        ),
      }),
    );
    expect(found.map((k) => [k.category, k.name, k.className])).toEqual([
      ["action", "custom_action", "CustomAction"],
    ]);
    expect(found[0]?.file).toBe("app/resources/custom_app_resources.py");
  });

  it("неиспользуемый наследник не сканируется", () => {
    const found = customKeywords(
      reader({
        "app_config.py": CONFIG,
        "app/resources/custom_app_resources.py": cls(
          "CustomAppResources",
          "SmartAppResources",
          '    def init_actions(self):\n        actions["used"] = C',
        ),
        "app/resources/unused_resources.py": cls(
          "UnusedResources",
          "SmartAppResources",
          '    def init_actions(self):\n        actions["unused"] = C',
        ),
      }),
    );
    expect(found.map((k) => k.name)).toEqual(["used"]);
  });

  it("RESOURCES в ветке if гасит словарь целиком", () => {
    const found = customKeywords(
      reader({
        "app_config.py":
          "from app.resources.custom_app_resources import CustomAppResources\nif dev:\n    RESOURCES = CustomAppResources\n",
        "app/resources/custom_app_resources.py": cls(
          "CustomAppResources",
          "SmartAppResources",
          '    def init_actions(self):\n        actions["x"] = C',
        ),
      }),
    );
    expect(found).toEqual([]);
  });

  it("условное переопределение RESOURCES гасит словарь, даже если есть верхнеуровневое", () => {
    // Именно ради этого случая нужен запрет: значение верхнего уровня есть, но
    // ветка может его переопределить, и статически выбрать нельзя.
    const found = customKeywords(
      reader({
        "app_config.py": [
          "from app.resources.custom_app_resources import CustomAppResources",
          "RESOURCES = CustomAppResources",
          "if dev:",
          "    RESOURCES = DevResources",
          "",
        ].join("\n"),
        "app/resources/custom_app_resources.py": cls(
          "CustomAppResources",
          "SmartAppResources",
          '    def init_actions(self):\n        actions["x"] = C',
        ),
      }),
    );
    expect(found).toEqual([]);
  });

  it("без app_config.py слов нет", () => {
    expect(customKeywords(reader({}))).toEqual([]);
  });

  it("класс из пакета читается через __init__.py", () => {
    const found = customKeywords(
      reader({
        "app_config.py": "from app.resources import CustomAppResources\nRESOURCES = CustomAppResources\n",
        "app/resources/__init__.py": cls(
          "CustomAppResources",
          "SmartAppResources",
          '    def init_actions(self):\n        actions["packaged"] = C',
        ),
      }),
    );
    expect(found.map((k) => k.name)).toEqual(["packaged"]);
  });
});

describe("цепочка наследования", () => {
  const base = cls(
    "BaseResources",
    "SmartAppResources",
    [
      "    def init_actions(self):",
      '        actions["base_action"] = BaseAction',
      '        actions["shared"] = BaseAction',
      "",
      "    def init_requirements(self):",
      '        requirements["base_requirement"] = BaseRequirement',
    ].join("\n"),
  );

  const derived = (superCall: boolean): string =>
    [
      "from app.resources.base_resources import BaseResources",
      "",
      "class CustomAppResources(BaseResources):",
      "    def init_actions(self):",
      ...(superCall ? ["        super().init_actions()"] : []),
      '        actions["custom_action"] = CustomAction',
      '        actions["shared"] = CustomAction',
      "",
    ].join("\n");

  const run = (superCall: boolean) =>
    customKeywords(
      reader({
        "app_config.py": CONFIG,
        "app/resources/custom_app_resources.py": derived(superCall),
        "app/resources/base_resources.py": base,
      }),
    );

  it("super() наследует регистрации базы", () => {
    expect(run(true).map((k) => k.name).sort()).toEqual([
      "base_action",
      "base_requirement",
      "custom_action",
      "shared",
    ]);
  });

  it("переопределение выигрывает у базы", () => {
    const shared = run(true).find((k) => k.name === "shared");
    expect(shared?.className).toBe("CustomAction");
    expect(shared?.file).toBe("app/resources/custom_app_resources.py");
  });

  it("без super() регистрации базы для этого метода теряются", () => {
    const names = run(false).map((k) => k.name).sort();
    expect(names).toEqual(["base_requirement", "custom_action", "shared"]);
    // Метод init_requirements не переопределён — его регистрации остаются.
    expect(names).toContain("base_requirement");
  });

  it("любое множественное наследование гасит словарь", () => {
    const found = customKeywords(
      reader({
        "app_config.py": CONFIG,
        "app/resources/custom_app_resources.py":
          "from app.resources.base_resources import BaseResources\n\n" +
          "class CustomAppResources(BaseResources, LoggingMixin):\n" +
          "    def init_actions(self):\n" +
          '        actions["custom_action"] = C\n',
        "app/resources/base_resources.py": base,
      }),
    );
    expect(found).toEqual([]);
  });
});

describe("исключённые каталоги", () => {
  it.each([
    ["venv/lib/resources.py", true],
    ["app/.venv/x.py", true],
    ["lib/site-packages/smart_kit/resources.py", true],
    ["app/resources/custom_app_resources.py", false],
  ])("%s -> %s", (path, excluded) => {
    expect(hasExcludedSegment(path as string)).toBe(excluded);
  });

  it("модуль из исключённого каталога не читается", () => {
    const found = customKeywords(
      reader({
        "app_config.py": "from venv.resources import R\nRESOURCES = R\n",
        "venv/resources.py": cls("R", "SmartAppResources", '    def init_actions(self):\n        actions["x"] = C'),
      }),
    );
    expect(found).toEqual([]);
  });
});

describe("категории", () => {
  it("реестр без категории (db_adapters) пропускается", () => {
    const found = customKeywords(
      reader({
        "app_config.py": CONFIG,
        "app/resources/custom_app_resources.py": cls(
          "CustomAppResources",
          "SmartAppResources",
          [
            "    def init_db_adapters(self):",
            '        db_adapters["custom_db_adapter"] = A',
            "",
            "    def init_actions(self):",
            '        actions["kept"] = C',
          ].join("\n"),
        ),
      }),
    );
    expect(found.map((k) => k.name)).toEqual(["kept"]);
  });
});


describe("индекс приложений", () => {
  const CONFIG_FOR = (module: string, cls: string): string =>
    `from ${module} import ${cls}\nRESOURCES = ${cls}\n`;

  const resources = (name: string, action: string): string =>
    `class ${name}(SmartAppResources):\n    def init_actions(self):\n        actions["${action}"] = C\n`;

  const indexWith = (files: Record<string, string>): SmartAppIndex => {
    const index = new SmartAppIndex();
    for (const [uri, text] of Object.entries(files)) index.upsert(uri, text);
    index.markReady();
    return index;
  };

  const APP_A = "file:///w/app_a";
  const APP_B = "file:///w/app_b";
  const NESTED = "file:///w/app_a/subapp";
  const dsl = (root: string): [string, string] => [
    `${root}/static/references/actions/actions.json`,
    '{ "some_action": { "type": "custom_a" } }',
  ];

  it("слова приложения доступны его JSON и не видны соседнему", () => {
    const index = indexWith({
      [`${APP_A}/app_config.py`]: CONFIG_FOR("app.resources.custom", "AResources"),
      [`${APP_A}/app/resources/custom.py`]: resources("AResources", "custom_a"),
      [dsl(APP_A)[0]]: dsl(APP_A)[1],
      [`${APP_B}/app_config.py`]: CONFIG_FOR("app.resources.custom", "BResources"),
      [`${APP_B}/app/resources/custom.py`]: resources("BResources", "custom_b"),
      [dsl(APP_B)[0]]: dsl(APP_B)[1],
    });

    const names = (root: string) =>
      index.customKeywordsOf(`w/${root}/static/references`).map((k) => k.name);
    expect(names("app_a")).toEqual(["custom_a"]);
    expect(names("app_b")).toEqual(["custom_b"]);
  });

  it("вложенное приложение изолировано от внешнего", () => {
    const index = indexWith({
      [`${APP_A}/app_config.py`]: CONFIG_FOR("app.resources.custom", "AResources"),
      [`${APP_A}/app/resources/custom.py`]: resources("AResources", "outer_action"),
      [dsl(APP_A)[0]]: dsl(APP_A)[1],
      [`${NESTED}/app_config.py`]: CONFIG_FOR("app.resources.custom", "NestedResources"),
      [`${NESTED}/app/resources/custom.py`]: resources("NestedResources", "inner_action"),
      [dsl(NESTED)[0]]: dsl(NESTED)[1],
    });

    expect(index.customKeywordsOf("w/app_a/static/references").map((k) => k.name)).toEqual([
      "outer_action",
    ]);
    expect(index.customKeywordsOf("w/app_a/subapp/static/references").map((k) => k.name)).toEqual([
      "inner_action",
    ]);
  });

  it("внешнее приложение не читает модуль, лежащий внутри вложенного", () => {
    // Файл физически внутри subapp: владелец у него — вложенное приложение,
    // поэтому цепочка внешнего его не видит и слов не даёт.
    const index = indexWith({
      [`${APP_A}/app_config.py`]: "from subapp.app.resources.custom import NestedResources\nRESOURCES = NestedResources\n",
      [dsl(APP_A)[0]]: dsl(APP_A)[1],
      [`${NESTED}/app_config.py`]: CONFIG_FOR("app.resources.custom", "NestedResources"),
      [`${NESTED}/app/resources/custom.py`]: resources("NestedResources", "inner_action"),
      [dsl(NESTED)[0]]: dsl(NESTED)[1],
    });

    expect(index.customKeywordsOf("w/app_a/static/references")).toEqual([]);
    expect(index.customKeywordsOf("w/app_a/subapp/static/references").map((k) => k.name)).toEqual([
      "inner_action",
    ]);
  });

  it("правка базового класса пересобирает словарь производного", () => {
    const index = indexWith({
      [`${APP_A}/app_config.py`]: CONFIG_FOR("app.resources.custom", "AResources"),
      [`${APP_A}/app/resources/custom.py`]:
        "from app.resources.base import BaseResources\n\n" +
        "class AResources(BaseResources):\n" +
        "    def init_actions(self):\n        super().init_actions()\n",
      [`${APP_A}/app/resources/base.py`]: resources("BaseResources", "before_edit"),
      [dsl(APP_A)[0]]: dsl(APP_A)[1],
    });
    expect(index.customKeywordsOf("w/app_a/static/references").map((k) => k.name)).toEqual([
      "before_edit",
    ]);

    // Меняется база, а не активный класс: словарь обязан пересобраться целиком.
    index.upsert(`${APP_A}/app/resources/base.py`, resources("BaseResources", "after_edit"));
    expect(index.customKeywordsOf("w/app_a/static/references").map((k) => k.name)).toEqual([
      "after_edit",
    ]);
  });

  it("удаление файла ресурсов убирает слова", () => {
    const index = indexWith({
      [`${APP_A}/app_config.py`]: CONFIG_FOR("app.resources.custom", "AResources"),
      [`${APP_A}/app/resources/custom.py`]: resources("AResources", "custom_a"),
      [dsl(APP_A)[0]]: dsl(APP_A)[1],
    });
    expect(index.customKeywordsOf("w/app_a/static/references")).toHaveLength(1);

    index.remove(`${APP_A}/app/resources/custom.py`);
    expect(index.customKeywordsOf("w/app_a/static/references")).toEqual([]);
  });
});


describe("слова приложения в семантике", () => {
  const uri = "file:///w/app/static/references/actions/actions.json";
  const json = '{ "some_action": { "type": "custom_action" } }';
  const build = (): SmartAppIndex => {
    const index = new SmartAppIndex();
    index.upsert(
      "file:///w/app/app_config.py",
      "from app.resources.custom import R\nRESOURCES = R\n",
    );
    index.upsert(
      "file:///w/app/app/resources/custom.py",
      'class R(SmartAppResources):\n    def init_actions(self):\n        actions["custom_action"] = CustomAction\n',
    );
    index.upsert(uri, json);
    index.markReady();
    return index;
  };

  it("подсвечивается как ключевое слово", () => {
    const index = build();
    const context = documentContext(uri, json);
    const custom = index.customKeywordsOf(context.scopeRoot);
    const tokens = semanticTokens(context, custom).filter(
      (token) => token.type === SemanticTokenType.KEYWORD,
    );
    expect(tokens.map((t) => json.slice(t.start, t.end))).toEqual(['"custom_action"']);
    // Без слов приложения та же позиция ключевым словом не считается.
    expect(semanticTokens(context).filter((t) => t.type === SemanticTokenType.KEYWORD)).toEqual([]);
  });

  it("предлагается в позиции type с подписью класса", () => {
    const index = build();
    const source = '{ "some_action": { "type": "" } }';
    const offset = source.indexOf('""') + 1;
    const context = documentContext(uri, source);
    index.upsert(uri, source);
    const result = completionAt(index, context, offset);
    const item = result.items.find((candidate) => candidate.label === "custom_action");
    expect(item?.detail).toBe("CustomAction");
    // Фреймворковые слова той же категории никуда не делись.
    expect(result.items.map((i) => i.label)).toContain("external");
  });
});
