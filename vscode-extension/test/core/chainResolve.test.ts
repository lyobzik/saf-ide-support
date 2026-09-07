import { describe, expect, it } from "vitest";

import { parseModule } from "../../src/core/pythonScan";
import { classRefOf, resolveChain, type AppFiles } from "../../src/core/resourceKeywords";

/**
 * Разрешение цепочки наследования как самостоятельный примитив.
 *
 * У резолвера два потребителя с разными нуждами: ресурсам нужны только классы
 * приложения, модели пользователя — ещё и имя библиотечной базы, по которому
 * выбирается пол из снимка фреймворка. Отсюда два исхода, которых раньше не
 * было: пустая цепочка при известной базе (активный класс библиотечный) и
 * цепочка, кончившаяся классом без базы.
 *
 * Та же таблица входов прогоняется в плагине (`SmartAppChainResolveTest`).
 */

const reader = (files: Record<string, string>): AppFiles => ({
  read: (path) => files[path],
  exists: (path) =>
    Object.keys(files).some((known) => known === path || known.startsWith(`${path}/`)),
});

/** Разрешает значение переменной [variable] из `app_config.py` и строит цепочку. */
const resolve = (files: Record<string, string>, variable: string) => {
  const configText = files["app_config.py"] as string;
  const config = parseModule(configText);
  const value = config.topLevelVars.get(variable);
  if (value === undefined) return undefined;
  const start = classRefOf(value, config, "app_config.py");
  if (start === undefined) return undefined;
  return resolveChain(start, reader(files));
};

const config = (importLine: string, assignment: string): string =>
  `${importLine}\n\n${assignment}\n`;

describe("resolveChain", () => {
  it("класс приложения с библиотечной базой User", () => {
    const result = resolve(
      {
        "app_config.py": config("from app.user.user import CustomUser", "USER = CustomUser"),
        "app/user/user.py":
          "from scenarios.user.user_model import User\n\n\nclass CustomUser(User):\n    pass\n",
      },
      "USER",
    );
    expect(result?.classes.map((entry) => entry.cls.name)).toEqual(["CustomUser"]);
    expect(result?.libraryBase).toBe("scenarios.user.user_model.User");
  });

  it("класс приложения с библиотечной базой BaseUser", () => {
    const result = resolve(
      {
        "app_config.py": config("from app.user.user import CustomUser", "USER = CustomUser"),
        "app/user/user.py":
          "from core.model.base_user import BaseUser\n\n\nclass CustomUser(BaseUser):\n    pass\n",
      },
      "USER",
    );
    expect(result?.libraryBase).toBe("core.model.base_user.BaseUser");
  });

  it("две ступени внутри приложения — от базы к производному", () => {
    const result = resolve(
      {
        "app_config.py": config("from app.user.user import CustomUser", "USER = CustomUser"),
        "app/user/user.py":
          "from app.user.middle import Middle\n\n\nclass CustomUser(Middle):\n    pass\n",
        "app/user/middle.py":
          "from scenarios.user.user_model import User\n\n\nclass Middle(User):\n    pass\n",
      },
      "USER",
    );
    expect(result?.classes.map((entry) => entry.cls.name)).toEqual(["Middle", "CustomUser"]);
    expect(result?.libraryBase).toBe("scenarios.user.user_model.User");
  });

  it("библиотечный класс назван напрямую: классов приложения нет, база известна", () => {
    // Раньше этот исход был отказом (`chain.isEmpty()` -> null). Для модели
    // пользователя он основной: так выглядит `USER = User` и подстановка
    // библиотечного дефолта при отсутствующем `USER`.
    const result = resolve(
      {
        "app_config.py": config("from scenarios.user.user_model import User", "USER = User"),
      },
      "USER",
    );
    expect(result).toBeDefined();
    expect(result?.classes).toEqual([]);
    expect(result?.libraryBase).toBe("scenarios.user.user_model.User");
  });

  it("класс без базы: цепочка есть, библиотечного пола нет", () => {
    const result = resolve(
      {
        "app_config.py": config("from app.user.user import CustomUser", "USER = CustomUser"),
        "app/user/user.py": "class CustomUser:\n    pass\n",
      },
      "USER",
    );
    expect(result?.classes.map((entry) => entry.cls.name)).toEqual(["CustomUser"]);
    expect(result?.libraryBase).toBeUndefined();
  });

  it("пакетная форма модуля даёт то же точечное имя", () => {
    const result = resolve(
      {
        "app_config.py": config("from app.user.user import CustomUser", "USER = CustomUser"),
        "app/user/user.py": "from a.b.c import Base\n\n\nclass CustomUser(Base):\n    pass\n",
      },
      "USER",
    );
    // Модуль `a.b.c` мог бы лежать и как `a/b/c/__init__.py`; имя базы от этого
    // не зависит — `ClassRef.file` всегда хранит `.py`-вариант.
    expect(result?.libraryBase).toBe("a.b.c.Base");
  });

  it("база не сопоставлена ни с импортом, ни с классом рядом — цепочка недействительна", () => {
    const result = resolve(
      {
        "app_config.py": config("from app.user.user import CustomUser", "USER = CustomUser"),
        "app/user/user.py": "class CustomUser(Unknown):\n    pass\n",
      },
      "USER",
    );
    expect(result).toBeUndefined();
  });

  it("пропавший модуль приложения — цепочка недействительна", () => {
    const result = resolve(
      {
        "app_config.py": config("from app.user.user import CustomUser", "USER = CustomUser"),
        // Модуля нет, но корневой пакет `app` в приложении есть: значит это наш
        // модуль, которого не хватает, а не библиотека.
        "app/other.py": "x = 1\n",
      },
      "USER",
    );
    expect(result).toBeUndefined();
  });

  it("множественное наследование — цепочка недействительна", () => {
    const result = resolve(
      {
        "app_config.py": config("from app.user.user import CustomUser", "USER = CustomUser"),
        "app/user/user.py":
          "from scenarios.user.user_model import User\nfrom app.user.mixin import Mixin\n\n\n" +
          "class CustomUser(User, Mixin):\n    pass\n",
        "app/user/mixin.py": "class Mixin:\n    pass\n",
      },
      "USER",
    );
    expect(result).toBeUndefined();
  });

  it("цикл наследования — цепочка недействительна", () => {
    const result = resolve(
      {
        "app_config.py": config("from app.user.a import A", "USER = A"),
        "app/user/a.py": "from app.user.b import B\n\n\nclass A(B):\n    pass\n",
        "app/user/b.py": "from app.user.a import A\n\n\nclass B(A):\n    pass\n",
      },
      "USER",
    );
    expect(result).toBeUndefined();
  });
});
