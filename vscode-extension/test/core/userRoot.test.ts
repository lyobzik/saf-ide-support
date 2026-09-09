import { describe, expect, it } from "vitest";

import { type AppFiles } from "../../src/core/resourceKeywords";
import { userRootOf } from "../../src/core/userRoot";

/**
 * Корневое имя пользователя по параметризатору: доказательство, его отсутствие
 * и доказательство обратного.
 *
 * Та же таблица входов прогоняется в плагине (`SmartAppUserRootTest`).
 */

const reader = (files: Record<string, string>): AppFiles => ({
  read: (path) => files[path],
  exists: (path) =>
    Object.keys(files).some((known) => known === path || known.startsWith(`${path}/`)),
});

/** Типовая раскладка: `PARAMETRIZER = CustomParametrizer` над библиотечным классом. */
const app = (body: string): Record<string, string> => ({
  "app_config.py": "from app.user.parametrizer import CustomParametrizer\n\nPARAMETRIZER = CustomParametrizer\n",
  "app/user/parametrizer.py":
    "from scenarios.user.parametrizer import Parametrizer\n\n\n" +
    "class CustomParametrizer(Parametrizer):\n" +
    "    def _get_user_data(self, tpr=None):\n" +
    body,
});

const root = (files: Record<string, string>) => userRootOf(reader(files));

/** Тот же класс с доказанной привязкой, но с гасителем: заголовок и хвост свои. */
const withBlocker = (header: string, extra = ""): Record<string, string> => ({
  "app_config.py": "from app.user.parametrizer import CustomParametrizer\n\nPARAMETRIZER = CustomParametrizer\n",
  "app/user/parametrizer.py":
    "from scenarios.user.parametrizer import Parametrizer\n\n\n" +
    header +
    "    def _get_user_data(self, tpr=None):\n" +
    '        data = {}\n        data["user"] = self._user\n        return data\n' +
    extra,
});

describe("доказанная привязка", () => {
  it("запись под именем user даёт корень и право на WARNING", () => {
    const result = root(
      app('        data = super()._get_user_data(tpr)\n        data["user"] = self._user\n        return data\n'),
    );
    expect(result.state).toBe("proven");
    expect(result.names).toEqual(["user"]);
  });

  it("имя корня не обязано быть user", () => {
    const result = root(
      app('        data = {}\n        data["me"] = self._user\n        return data\n'),
    );
    expect(result.state).toBe("proven");
    expect(result.names).toEqual(["me"]);
  });

  it("одно значение под двумя ключами даёт два корня", () => {
    const result = root(
      app('        data = {}\n        data.update({"user": self._user, "account": self._user})\n        return data\n'),
    );
    expect(result.names).toEqual(["user", "account"]);
  });

  it("побеждает последняя запись ключа", () => {
    const result = root(
      app('        data = {}\n        data["user"] = other\n        data["user"] = self._user\n        return data\n'),
    );
    expect(result.state).toBe("proven");
  });

  it("имя с ведущим подчёркиванием корнем быть может", () => {
    // Условие «не начинается с `_`» к корневым именам не применяется: ключ
    // словаря параметров приложение выбирает осознанно, и `{{ _u.x }}` законно.
    const result = root(
      app('        data = {}\n        data["_u"] = self._user\n        return data\n'),
    );
    expect(result.state).toBe("proven");
    expect(result.names).toEqual(["_u"]);
  });

  it("связь только с переменной формы корнем не считается", () => {
    // Под `main_form` уже работает своя семантика (план, раздел 0).
    const result = root(
      app('        data = {}\n        data["main_form"] = self._user\n        return data\n'),
    );
    expect(result.state).toBe("default");
    expect(result.names).toEqual(["user"]);
  });
});

describe("доказательства нет — работает дефолт", () => {
  const cases: Record<string, Record<string, string>> = {
    "эталонный параметризатор: правило пройдено, ключей ноль": app(
      "        data = super()._get_user_data(tpr)\n        data.update({})\n        return data\n",
    ),
    "правило нарушено — запись в ветке": app(
      '        data = {}\n        if flag:\n            data["user"] = self._user\n        return data\n',
    ),
    "значение не распознано": app(
      '        data = {}\n        data["user"] = build_user()\n        return data\n',
    ),
    "метод не переопределён": {
      "app_config.py": "from app.user.parametrizer import CustomParametrizer\n\nPARAMETRIZER = CustomParametrizer\n",
      "app/user/parametrizer.py":
        "from scenarios.user.parametrizer import Parametrizer\n\n\nclass CustomParametrizer(Parametrizer):\n    pass\n",
    },
    "переменной нет": { "app_config.py": "RESOURCES = X\n" },
    "переменная присвоена в ветке": {
      "app_config.py": "if dev:\n    PARAMETRIZER = A\nelse:\n    PARAMETRIZER = B\n",
    },
    "значение не резолвится в класс": {
      "app_config.py": "PARAMETRIZER = build()\n",
    },
    "цепочка недействительна": {
      "app_config.py": "from app.user.parametrizer import CustomParametrizer\n\nPARAMETRIZER = CustomParametrizer\n",
      "app/user/parametrizer.py": "class CustomParametrizer(Unknown):\n    pass\n",
    },
    "назван библиотечный класс": {
      "app_config.py":
        "from scenarios.user.parametrizer import Parametrizer\n\nPARAMETRIZER = Parametrizer\n",
    },
    "нет app_config.py": {},
    // Обратиться к такому имени одним сегментом `<имя>.<поле>` нельзя, а ключ
    // словаря такую строку принимает молча.
    "неадресуемое имя ключа: дефис": app(
      '        data = {}\n        data["foo-bar"] = self._user\n        return data\n',
    ),
    "неадресуемое имя ключа: точка": app(
      '        data = {}\n        data["foo.bar"] = self._user\n        return data\n',
    ),
    "неадресуемое имя ключа: цифра в начале": app(
      '        data = {}\n        data["9lives"] = self._user\n        return data\n',
    ),
    "неадресуемое имя ключа: пустая строка": app(
      '        data = {}\n        data[""] = self._user\n        return data\n',
    ),
    "декорированный метод непроверяем": {
      "app_config.py": "from app.user.parametrizer import CustomParametrizer\n\nPARAMETRIZER = CustomParametrizer\n",
      "app/user/parametrizer.py":
        "from scenarios.user.parametrizer import Parametrizer\n\n\n" +
        "class CustomParametrizer(Parametrizer):\n    @wrap_result\n" +
        "    def _get_user_data(self, tpr=None):\n" +
        '        data = {}\n        data["user"] = self._user\n        return data\n',
    },
    "значение — кортеж из одного элемента": app(
      '        data = {}\n        data["user"] = self._user,\n        return data\n',
    ),
    // Гаситель в классе параметризатора ломает обе опоры доказательства: что
    // прочитанный `_get_user_data` и есть действующий метод и что `self._user`
    // — атрибут фреймворка.
    "декоратор на классе параметризатора": withBlocker("@replace_class\nclass CustomParametrizer(Parametrizer):\n"),
    "метакласс в заголовке класса": withBlocker("class CustomParametrizer(Parametrizer, metaclass=M):\n"),
    "перехват атрибутов классом": withBlocker(
      "class CustomParametrizer(Parametrizer):\n",
      "\n    def __getattribute__(self, n):\n        return 1\n",
    ),
    "__init__ без super(): self._user не от фреймворка": withBlocker(
      "class CustomParametrizer(Parametrizer):\n",
      "\n    def __init__(self, user, items):\n        self._user = None\n",
    ),
    "токен-гаситель в другом методе класса": withBlocker(
      "class CustomParametrizer(Parametrizer):\n",
      "\n    def helper(self):\n        setattr(self, 'x', 1)\n",
    ),
  };

  for (const [title, files] of Object.entries(cases)) {
    it(title, () => {
      const result = root(files);
      expect(result.state).toBe("default");
      expect(result.names).toEqual(["user"]);
    });
  }
});

describe("доказательство обратного", () => {
  it("под дефолтным именем лежит распознанное чужое значение", () => {
    // Предлагать поля модели под именем, про которое прочитано, что в нём
    // другое значение, хуже, чем не предлагать вовсе.
    const result = root(
      app('        data = {}\n        data["user"] = other_user\n        return data\n'),
    );
    expect(result.state).toBe("disproved");
    expect(result.names).toEqual([]);
  });

  it("последняя запись решает и здесь", () => {
    const result = root(
      app('        data = {}\n        data["user"] = self._user\n        data["user"] = other_user\n        return data\n'),
    );
    expect(result.state).toBe("disproved");
  });
});

describe("свёртка цепочки параметризаторов", () => {
  const twoLevel = (baseBody: string, derivedBody: string): Record<string, string> => ({
    "app_config.py": "from app.user.derived import Derived\n\nPARAMETRIZER = Derived\n",
    "app/user/derived.py":
      "from app.user.base import Base\n\n\nclass Derived(Base):\n" +
      "    def _get_user_data(self, tpr=None):\n" +
      derivedBody,
    "app/user/base.py":
      "from scenarios.user.parametrizer import Parametrizer\n\n\nclass Base(Parametrizer):\n" +
      "    def _get_user_data(self, tpr=None):\n" +
      baseBody,
  });

  const bindsUser = '        data = {}\n        data["user"] = self._user\n        return data\n';

  it("привязка базы наследуется через super()", () => {
    const result = root(
      twoLevel(bindsUser, "        data = super()._get_user_data(tpr)\n        return data\n"),
    );
    expect(result.state).toBe("proven");
    expect(result.names).toEqual(["user"]);
  });

  it("голый вызов super() наследования не даёт", () => {
    // Результат вызова выброшен: привязок базы в `data` нет, и засчитать их
    // значило бы получить WARNING на корне, которого не существует.
    const result = root(
      twoLevel(
        bindsUser,
        "        data = {}\n        super()._get_user_data(tpr)\n        return data\n",
      ),
    );
    expect(result.state).toBe("default");
  });

  it("метод, собравший словарь с нуля, привязок базы не получает", () => {
    const result = root(twoLevel(bindsUser, "        data = {}\n        return data\n"));
    expect(result.state).toBe("default");
  });

  it("нарушение правила в базе отменяет унаследованное доказательство", () => {
    const result = root(
      twoLevel(
        '        data = {}\n        helper(data)\n        data["user"] = self._user\n        return data\n',
        "        data = super()._get_user_data(tpr)\n        return data\n",
      ),
    );
    expect(result.state).toBe("default");
  });

  it("нарушение в базе отменяет и собственную привязку наследника", () => {
    // По плану неподдержанное упоминание в любом методе, участвующем в свёртке,
    // отменяет доказательство целиком. Формально запись наследника легла бы
    // поверх содержимого базы, но WARNING — утверждение, и делать его по методу,
    // про который прочитано «содержимое неизвестно», нельзя.
    const result = root(
      twoLevel(
        "        data = {}\n        helper(data)\n        return data\n",
        '        data = super()._get_user_data(tpr)\n        data["user"] = self._user\n        return data\n',
      ),
    );
    expect(result.state).toBe("default");
  });

  it("нарушение в базе, которую не наследуют, доказательству не мешает", () => {
    const result = root(twoLevel("        helper(data)\n        return data\n", bindsUser));
    expect(result.state).toBe("proven");
    expect(result.names).toEqual(["user"]);
  });

  it("из двух определений метода в классе действует последнее", () => {
    // Так работает Python: первое определение перекрыто вторым, и словарь
    // собирает именно оно.
    const result = root({
      "app_config.py": "from app.user.parametrizer import P\n\nPARAMETRIZER = P\n",
      "app/user/parametrizer.py":
        "from scenarios.user.parametrizer import Parametrizer\n\n\nclass P(Parametrizer):\n" +
        '    def _get_user_data(self, tpr=None):\n        data = {}\n        data["first"] = self._user\n        return data\n' +
        '\n    def _get_user_data(self, tpr=None):\n        data = {}\n        data["second"] = self._user\n        return data\n',
    });
    expect(result.names).toEqual(["second"]);
  });

  it("аргументированный super() наследования не даёт", () => {
    const result = root(
      twoLevel(bindsUser, "        data = super(Base, self)._get_user_data(tpr)\n        return data\n"),
    );
    expect(result.state).toBe("default");
  });

  it("гаситель в любом классе цепочки отменяет доказательство", () => {
    // Наследник здесь чистый и связывает `user` сам — без правила «любой
    // гаситель любого класса» каждая строка давала бы `proven`.
    const bases: Record<string, string> = {
      classDecorator: "@replace_class\nclass Base(Parametrizer):\n    pass\n",
      metaclassInBases: "class Base(Parametrizer, metaclass=M):\n    pass\n",
      "__getattribute__":
        "class Base(Parametrizer):\n    def __getattribute__(self, n):\n        return 1\n",
      initWithoutSuper:
        "class Base(Parametrizer):\n    def __init__(self, user, items):\n        self._user = None\n",
      "setattr(":
        "class Base(Parametrizer):\n    def helper(self):\n        setattr(self, 'x', 1)\n",
    };
    for (const [blocker, base] of Object.entries(bases)) {
      const result = root({
        "app_config.py": "from app.user.derived import Derived\n\nPARAMETRIZER = Derived\n",
        "app/user/derived.py":
          "from app.user.base import Base\n\n\nclass Derived(Base):\n" +
          "    def _get_user_data(self, tpr=None):\n" +
          bindsUser,
        "app/user/base.py":
          "from scenarios.user.parametrizer import Parametrizer\n\n\n" + base,
      });
      expect(result.state, blocker).toBe("default");
    }
  });

  it("гаситель в базе цепочки отменяет доказательство наследника", () => {
    // `__getattribute__` базы перехватывает и метод, и `self._user` у
    // производного экземпляра — чистота самого наследника ничего не значит.
    const result = root({
      "app_config.py": "from app.user.derived import Derived\n\nPARAMETRIZER = Derived\n",
      "app/user/derived.py":
        "from app.user.base import Base\n\n\nclass Derived(Base):\n" +
        "    def _get_user_data(self, tpr=None):\n" +
        bindsUser,
      "app/user/base.py":
        "from scenarios.user.parametrizer import Parametrizer\n\n\nclass Base(Parametrizer):\n" +
        "    def __getattribute__(self, n):\n        return 1\n",
    });
    expect(result.state).toBe("default");
  });

  it("класс без переопределения пропускает состояние базы дальше", () => {
    const result = root({
      "app_config.py": "from app.user.derived import Derived\n\nPARAMETRIZER = Derived\n",
      "app/user/derived.py":
        "from app.user.base import Base\n\n\nclass Derived(Base):\n    pass\n",
      "app/user/base.py":
        "from scenarios.user.parametrizer import Parametrizer\n\n\nclass Base(Parametrizer):\n" +
        "    def _get_user_data(self, tpr=None):\n" +
        bindsUser,
    });
    expect(result.state).toBe("proven");
  });
});
