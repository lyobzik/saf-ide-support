import { describe, expect, it } from "vitest";

import { userClasses, userModel as spec } from "../../src/core/contract";
import { type AppFiles } from "../../src/core/resourceKeywords";
import { userModelOf } from "../../src/core/userModel";

/**
 * Словарь модели пользователя: пол фреймворка плюс половина приложения.
 *
 * Та же таблица входов прогоняется в плагине (`SmartAppUserModelTest`).
 */

const reader = (files: Record<string, string>): AppFiles => ({
  read: (path) => files[path],
  exists: (path) =>
    Object.keys(files).some((known) => known === path || known.startsWith(`${path}/`)),
});

const names = (files: Record<string, string>): string[] => [
  ...(userModelOf(reader(files))?.attributes.keys() ?? []),
];

/** Типовая раскладка: `USER = CustomUser`, класс наследует библиотечный `User`. */
const typicalApp = (body: string): Record<string, string> => ({
  "app_config.py": "from app.user.user import CustomUser\n\nUSER = CustomUser\n",
  "app/user/user.py": `from scenarios.user.user_model import User\n\n\nclass CustomUser(User):\n${body}`,
});

const floor = userClasses.get(spec.defaultClass);

describe("пол фреймворка", () => {
  it("типовое приложение получает все библиотечные имена", () => {
    // `CustomUser.fields` возвращает `super().fields + []`, то есть всё
    // приходит из библиотеки — включая `variables` из исходной задачи.
    const result = names(
      typicalApp(
        "    @property\n" + "    def fields(self):\n" + "        return super().fields + []\n",
      ),
    );
    expect(result).toContain("variables");
    expect(result).toContain("forms");
    expect(result).toContain("message");
    for (const name of floor?.fields ?? []) expect(result).toContain(name);
  });

  it("отсутствие USER — не отказ: фреймворк подставляет свой класс", () => {
    const result = userModelOf(reader({ "app_config.py": "RESOURCES = X\n" }));
    expect(result).toBeDefined();
    expect([...result!.attributes.keys()]).toContain("variables");
    // Классов приложения в цепочке нет — переходить с корневой переменной некуда.
    expect(result?.userClass).toBeUndefined();
  });

  it("USER присвоен в ветке — словаря нет", () => {
    expect(
      userModelOf(
        reader({
          "app_config.py": "if dev:\n    USER = A\nelse:\n    USER = B\n",
        }),
      ),
    ).toBeUndefined();
  });

  it("цепочка недействительна — словаря нет", () => {
    expect(
      userModelOf(
        reader({
          "app_config.py": "from app.user.user import CustomUser\n\nUSER = CustomUser\n",
          "app/user/user.py": "class CustomUser(Unknown):\n    pass\n",
        }),
      ),
    ).toBeUndefined();
  });
});

describe("деградация при неизвестном поле", () => {
  const unknownFloor = {
    "app_config.py": "from app.user.user import CustomUser\n\nUSER = CustomUser\n",
    "app/user/user.py":
      "from nlpf_statemachine.override.user import SMUser\n\n\n" +
      "class CustomUser(SMUser):\n" +
      "    def __init__(self):\n" +
      "        super().__init__()\n" +
      "        self.own_attribute = 1\n",
  };

  it("имена приложения работают, диагностика выключена", () => {
    // Отключать всё молча значило бы для такого приложения ничем не
    // отличаться от «плагин не установлен».
    const result = userModelOf(reader(unknownFloor));
    expect(result).toBeDefined();
    expect([...result!.attributes.keys()]).toContain("own_attribute");
    expect([...result!.attributes.keys()]).not.toContain("variables");
    expect(result?.diagnosticsSafe).toBe(false);
  });

  it("неизвестное поле и пустая цепочка — словаря нет", () => {
    expect(
      userModelOf(
        reader({
          "app_config.py": "from far.away import Other\n\nUSER = Other\n",
        }),
      ),
    ).toBeUndefined();
  });
});

describe("свёртка fields", () => {
  it("без super() поля базы отброшены, атрибуты базы остались", () => {
    const result = names(
      typicalApp(
        "    @property\n" + "    def fields(self):\n" + "        return [Field('own', M)]\n",
      ),
    );
    expect(result).toContain("own");
    expect(result).not.toContain("variables");
    // `message` создаётся не списком `fields`, а `self.message = …` в базе, и
    // переопределение свойства на него не влияет.
    expect(result).toContain("message");
  });

  it("неразобранная форма сохраняет поля базы", () => {
    const result = userModelOf(
      reader(
        typicalApp(
          "    @property\n" +
            "    def fields(self):\n" +
            "        return [*super().fields, Field('own', M)]\n",
        ),
      ),
    );
    expect([...result!.attributes.keys()]).toContain("variables");
    expect([...result!.attributes.keys()]).toContain("own");
    expect(result?.diagnosticsSafe).toBe(false);
  });

  it("из двух одноимённых классов действует последний", () => {
    // Так работает Python; `find` вернул бы первый и собрал бы не тот словарь.
    const result = names({
      "app_config.py": "from app.user.user import CustomUser\n\nUSER = CustomUser\n",
      "app/user/user.py":
        "from scenarios.user.user_model import User\n\n\n" +
        "class CustomUser(User):\n    @property\n    def fields(self):\n" +
        "        return super().fields + [Field('first', M)]\n\n\n" +
        "class CustomUser(User):\n    @property\n    def fields(self):\n" +
        "        return super().fields + [Field('second', M)]\n",
    });
    expect(result).not.toContain("first");
    expect(result).toContain("second");
  });

  it("класс без своего fields сохраняет пол", () => {
    // Самый частый вид класса пользователя вообще.
    expect(names(typicalApp("    pass\n"))).toContain("variables");
  });
});

describe("объявления и цели перехода", () => {
  it("имя из снимка объявлений не имеет", () => {
    const result = userModelOf(reader(typicalApp("    pass\n")));
    expect(result?.attributes.get("variables")?.declarations).toEqual([]);
  });

  it("поле приложения ведёт на содержимое литерала", () => {
    const files = typicalApp(
      "    @property\n" +
        "    def fields(self):\n" +
        "        return super().fields + [Field('own_field', M)]\n",
    );
    const site = userModelOf(reader(files))?.attributes.get("own_field")?.declarations[0];
    expect(site?.origin).toBe("field");
    expect(site?.file).toBe("app/user/user.py");
    expect((files["app/user/user.py"] as string).slice(site?.nameStart, site?.nameEnd)).toBe(
      "own_field",
    );
  });

  it("одно имя, объявленное дважды, даёт две цели", () => {
    const files = typicalApp(
      "    @property\n" +
        "    def fields(self):\n" +
        "        return super().fields + [Field('dual', M)]\n" +
        "\n" +
        "    def __init__(self):\n" +
        "        super().__init__()\n" +
        "        self.dual = 1\n",
    );
    const sites = userModelOf(reader(files))?.attributes.get("dual")?.declarations ?? [];
    expect(sites.map((s) => s.origin).sort()).toEqual(["field", "self"]);
  });

  it("переход с корневой переменной ведёт на класс приложения", () => {
    const files = typicalApp("    pass\n");
    const cls = userModelOf(reader(files))?.userClass;
    expect(cls?.name).toBe("CustomUser");
    expect(cls?.file).toBe("app/user/user.py");
    expect((files["app/user/user.py"] as string).slice(cls?.nameStart, cls?.nameEnd)).toBe(
      "CustomUser",
    );
  });

  it("самый производный класс цепочки — цель перехода", () => {
    const result = userModelOf(
      reader({
        "app_config.py": "from app.user.user import CustomUser\n\nUSER = CustomUser\n",
        "app/user/user.py":
          "from app.user.middle import Middle\n\n\nclass CustomUser(Middle):\n    pass\n",
        "app/user/middle.py":
          "from scenarios.user.user_model import User\n\n\nclass Middle(User):\n    pass\n",
      }),
    );
    expect(result?.userClass?.name).toBe("CustomUser");
  });
});

describe("фильтр имён и гасители", () => {
  it("приватные и неадресуемые имена в словарь не идут", () => {
    const result = names(
      typicalApp(
        "    @property\n" +
          "    def fields(self):\n" +
          "        return super().fields + [Field('ok', M), Field('foo-bar', M)]\n" +
          "\n" +
          "    def __init__(self):\n" +
          "        super().__init__()\n" +
          "        self._private = 1\n",
      ),
    );
    expect(result).toContain("ok");
    expect(result).not.toContain("foo-bar");
    expect(result).not.toContain("_private");
  });

  it("гаситель в классе приложения выключает диагностику", () => {
    const result = userModelOf(
      reader(
        typicalApp(
          "    def __getattr__(self, name):\n        return None\n",
        ),
      ),
    );
    expect(result?.diagnosticsSafe).toBe(false);
    // Словарь при этом работает: гаситель отнимает право утверждать, а не имена.
    expect([...result!.attributes.keys()]).toContain("variables");
  });

  it("чистый класс над безопасным полом разрешает диагностику", () => {
    const result = userModelOf(
      reader(
        typicalApp(
          "    @property\n" + "    def fields(self):\n" + "        return super().fields + []\n",
        ),
      ),
    );
    expect(result?.diagnosticsSafe).toBe(true);
  });
});
