import { describe, expect, it } from "vitest";

import { jinja, userClasses, userModel } from "../../src/core/contract";

/**
 * Секции контракта, описывающие модель пользователя, и снимок её библиотечного
 * пола. Значения приходят из Kotlin-таблиц и из генератора через `shared/`;
 * здесь проверяется, что потребитель их видит и что структуры собраны, а не
 * остались сырым JSON.
 */
describe("контракт модели пользователя", () => {
  it("секция userModel прочитана и типизирована", () => {
    // Значения приходят из Kotlin-таблиц через снимок; здесь проверяется, что
    // потребитель их видит и что множества собраны, а не остались массивами.
    expect(userModel.configVariable).toBe("USER");
    expect(userModel.parametrizerVariable).toBe("PARAMETRIZER");
    expect(userModel.parametrizerMethod).toBe("_get_user_data");
    expect(userModel.userValueExpression).toBe("self._user");
    expect(userModel.blockerTokens.has("setattr(")).toBe(true);
    expect(userModel.blockerConstructs.has("initWithoutSuper")).toBe(true);
    expect(jinja.userVariableDefault).toBe("user");
  });

  it("снимок библиотечного пола прочитан", () => {
    const floor = userClasses.get(userModel.defaultClass);
    expect(floor).toBeDefined();
    // Именно эти имена приходят из библиотеки на типовом приложении — в том
    // числе `variables` из исходной задачи.
    expect(floor?.fields).toContain("variables");
    expect(floor?.fields).toContain("forms");
    expect(floor?.attributes).toContain("message");
    expect(floor?.attributes.some((name) => name.startsWith("_"))).toBe(false);
    expect(floor?.diagnosticsSafe).toBe(true);
  });
});
