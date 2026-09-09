import { describe, expect, it } from "vitest";

import { completionAt } from "../../src/core/completion";
import { renameEdits, renameLookupAt } from "../../src/core/rename";
import { SmartAppIndex } from "../../src/core/index";
import { kinds } from "../../src/core/contract";
import {
  definitionsAt,
  diagnostics,
  documentContext,
  referencesAt,
} from "../../src/core/semantics";

/**
 * Семантика модели пользователя в Jinja: переход, диагностика и автодополнение
 * по `<корень>.<имя>`.
 *
 * Та же таблица входов прогоняется в плагине (`SmartAppUserSemanticsTest`).
 */

const APP = "file:///w/app";
const USER_URI = `${APP}/app/user/user.py`;
const PARAM_URI = `${APP}/app/user/parametrizer.py`;

const CONFIG = [
  "from app.user.user import CustomUser",
  "from app.user.parametrizer import CustomParametrizer",
  "",
  "USER = CustomUser",
  "PARAMETRIZER = CustomParametrizer",
  "",
].join("\n");

const USER_PY = [
  "from scenarios.user.user_model import User",
  "",
  "",
  "class CustomUser(User):",
  "    @property",
  "    def fields(self):",
  '        return super().fields + [Field("smart_geo", Geo)]',
  "",
].join("\n");

/** Параметризатор, связавший `self._user` с корнем `user`. */
const PARAM_PY = [
  "from scenarios.user.parametrizer import Parametrizer",
  "",
  "",
  "class CustomParametrizer(Parametrizer):",
  "    def _get_user_data(self, tpr=None):",
  "        data = super()._get_user_data(tpr)",
  '        data["user"] = self._user',
  "        return data",
  "",
].join("\n");

/** Тот же параметризатор, связавший `self._user` ещё и с псевдонимом `me`. */
const PARAM_PY_TWO_ROOTS = PARAM_PY.replace(
  '        data["user"] = self._user\n',
  '        data["user"] = self._user\n        data["me"] = self._user\n',
);

/** Класс пользователя, объявивший то же имя дважды разными формами. */
const USER_PY_TWICE =
  USER_PY + "\n    def __init__(self):\n        super().__init__()\n        self.smart_geo = None\n";

/** Тот же параметризатор, но привязки нет: корень дефолтный, утверждать нельзя. */
const PARAM_PY_UNPROVEN = PARAM_PY.replace('        data["user"] = self._user\n', "");

const JSON_URI = `${APP}/static/references/behaviors/b.json`;

/** Тот же класс пользователя с гасителем: словарь заведомо неполон. */
const USER_PY_UNSAFE = USER_PY + "\n    def __getattr__(self, name):\n        return None\n";

const indexWith = (
  files: Record<string, string> = {},
  parametrizer: string = PARAM_PY,
  userPy: string = USER_PY,
): SmartAppIndex => {
  const index = new SmartAppIndex();
  index.upsert(`${APP}/app_config.py`, CONFIG);
  index.upsert(USER_URI, userPy);
  index.upsert(PARAM_URI, parametrizer);
  for (const [uri, text] of Object.entries(files)) index.upsert(uri, text);
  index.markReady();
  return index;
};

const jsonWith = (value: string): string => `{ "b": { "text": ${JSON.stringify(value)} } }`;

/** Каретка на первом символе имени [name] внутри значения. */
const caretAt = (text: string, name: string): number => text.indexOf(name);

describe("переход по модели пользователя", () => {
  it("имя приложения ведёт на строку объявления в Python", () => {
    const text = jsonWith("Гео: {{ user.smart_geo }}");
    const index = indexWith({ [JSON_URI]: text });
    const found = definitionsAt(index, documentContext(JSON_URI, text), caretAt(text, "smart_geo"));
    expect(found).toHaveLength(1);
    expect(found[0]?.uri).toBe(USER_URI);
    expect(USER_PY.slice(found[0]?.start, found[0]?.end)).toBe("smart_geo");
  });

  it("корневая переменная ведёт на класс пользователя", () => {
    const text = jsonWith("{{ user.smart_geo }}");
    const index = indexWith({ [JSON_URI]: text });
    const found = definitionsAt(index, documentContext(JSON_URI, text), caretAt(text, "user"));
    expect(found).toHaveLength(1);
    expect(USER_PY.slice(found[0]?.start, found[0]?.end)).toBe("CustomUser");
  });

  it("имя из снимка фреймворка объявлений не имеет", () => {
    // `variables` приходит из снимка: в коде проекта такой строки нет.
    const text = jsonWith("{{ user.variables }}");
    const index = indexWith({ [JSON_URI]: text });
    expect(
      definitionsAt(index, documentContext(JSON_URI, text), caretAt(text, "variables")),
    ).toEqual([]);
  });

  it("работает во всех видах DSL-файлов", () => {
    // Словарь параметров шаблона один и тот же на любой рендер, поэтому
    // сужать контракт до подмножества видов было бы произволом (план, раздел 0).
    // Перечисление берётся из контракта: добавление вида без обновления теста
    // красит его, а не оставляет тихую дыру.
    for (const kind of kinds) {
      const uri = `${APP}/static/references/${kind.dirName}/x.json`;
      const text = jsonWith("{{ user.smart_geo }}");
      const index = indexWith({ [uri]: text });
      const found = definitionsAt(index, documentContext(uri, text), caretAt(text, "smart_geo"));
      expect(found, kind.kind).toHaveLength(1);
    }
  });

  it("в ключе JSON семантики нет", () => {
    const text = '{ "{{ user.smart_geo }}": { "type": "x" } }';
    const index = indexWith({ [JSON_URI]: text });
    expect(
      definitionsAt(index, documentContext(JSON_URI, text), caretAt(text, "smart_geo")),
    ).toEqual([]);
  });

  it("чужое корневое имя семантики не получает", () => {
    const text = jsonWith("{{ other.smart_geo }}");
    const index = indexWith({ [JSON_URI]: text });
    expect(
      definitionsAt(index, documentContext(JSON_URI, text), caretAt(text, "smart_geo")),
    ).toEqual([]);
  });
});

describe("диагностика по модели пользователя", () => {
  const messagesFor = (value: string, parametrizer = PARAM_PY, userPy = USER_PY): string[] => {
    const text = jsonWith(value);
    const index = indexWith({ [JSON_URI]: text }, parametrizer, userPy);
    return diagnostics(index, documentContext(JSON_URI, text)).map((d) => d.message);
  };

  it("неизвестное имя при доказанном корне подчёркивается", () => {
    expect(messagesFor("{{ user.nope }}")).toEqual([
      "Не удаётся разрешить поле 'nope' модели пользователя",
    ]);
  });

  it("известное имя не подчёркивается", () => {
    expect(messagesFor("{{ user.smart_geo }}")).toEqual([]);
    expect(messagesFor("{{ user.variables }}")).toEqual([]);
  });

  it("без доказанного корня утверждать нельзя", () => {
    // Под именем `user` может лежать что угодно — подчёркивать по нему значит
    // выдумывать ошибку.
    expect(messagesFor("{{ user.nope }}", PARAM_PY_UNPROVEN)).toEqual([]);
  });

  it("при сработавшем гасителе утверждать нельзя", () => {
    // Словарь тогда заведомо неполон: имя может существовать, просто сканер
    // его не увидел.
    expect(messagesFor("{{ user.nope }}", PARAM_PY, USER_PY_UNSAFE)).toEqual([]);
  });

  it("диапазон покрывает только имя поля", () => {
    const text = jsonWith("{{ user.nope }}");
    const index = indexWith({ [JSON_URI]: text });
    const found = diagnostics(index, documentContext(JSON_URI, text));
    expect(text.slice(found[0]?.start, found[0]?.end)).toBe("nope");
  });
});

describe("использования атрибута модели пользователя", () => {
  const OTHER_URI = `${APP}/static/references/scenarios/s.json`;
  const NESTED_URI = `${APP}/subapp/static/references/behaviors/n.json`;
  // Зависимость, вендоренная внутрь самого набора: файл — настоящий DSL-файл
  // приложения, и отсекает его только список исключённых каталогов.
  const VENDORED_URI = `${APP}/static/references/behaviors/venv/vendored.json`;
  // Набор внутри venv рядом с приложением: у него свой корень приложения, и
  // отсекает его правило владения, а не список каталогов.
  const OUTSIDE_URI = `${APP}/venv/lib/pkg/static/references/behaviors/o.json`;

  const usagesOf = (
    text: string,
    caret: string,
    includeDeclaration = false,
    extra: Record<string, string> = {},
  ) => {
    const index = indexWith({ [JSON_URI]: text, ...extra });
    return referencesAt(
      index,
      documentContext(JSON_URI, text),
      caretAt(text, caret),
      includeDeclaration,
    );
  };

  it("собирает вхождения по всем файлам приложения", () => {
    const text = jsonWith("{{ user.smart_geo }}");
    const other = '{ "s": { "answer": "{{ user.smart_geo }}" } }';
    const found = usagesOf(text, "smart_geo", false, { [OTHER_URI]: other });
    expect(found.map((f) => f.uri).sort()).toEqual([JSON_URI, OTHER_URI].sort());
  });

  it("диапазон — имя без корня и без кавычек", () => {
    const text = jsonWith("{{ user.smart_geo }}");
    const found = usagesOf(text, "smart_geo");
    expect(text.slice(found[0]?.start, found[0]?.end)).toBe("smart_geo");
  });

  it("вложенное приложение — чужое", () => {
    // `subapp` — отдельное приложение по правилу владения.
    const text = jsonWith("{{ user.smart_geo }}");
    const found = usagesOf(text, "smart_geo", false, {
      [NESTED_URI]: '{ "n": { "answer": "{{ user.smart_geo }}" } }',
      [`${APP}/subapp/app_config.py`]: CONFIG,
    });
    expect(found.map((f) => f.uri)).toEqual([JSON_URI]);
  });

  it("вендоренная зависимость внутри набора не считается", () => {
    const text = jsonWith("{{ user.smart_geo }}");
    const found = usagesOf(text, "smart_geo", false, {
      [VENDORED_URI]: '{ "v": { "answer": "{{ user.smart_geo }}" } }',
    });
    expect(found.map((f) => f.uri)).toEqual([JSON_URI]);
  });

  it("набор внутри соседнего venv — чужое приложение", () => {
    const text = jsonWith("{{ user.smart_geo }}");
    const found = usagesOf(text, "smart_geo", false, {
      [OUTSIDE_URI]: '{ "o": { "answer": "{{ user.smart_geo }}" } }',
    });
    expect(found.map((f) => f.uri)).toEqual([JSON_URI]);
  });

  it("вхождение под вторым корневым именем тоже считается", () => {
    // `user` и `me` связаны с одним `self._user`: оба — действующие корни, и
    // обращение под любым из них — вхождение одного и того же атрибута.
    const text = jsonWith("{{ user.smart_geo }}");
    const other = '{ "s": { "answer": "{{ me.smart_geo }}" } }';
    const index = indexWith(
      { [JSON_URI]: text, [OTHER_URI]: other },
      PARAM_PY_TWO_ROOTS,
    );
    const found = referencesAt(
      index,
      documentContext(JSON_URI, text),
      caretAt(text, "smart_geo"),
      false,
    );
    expect(found.map((f) => f.uri).sort()).toEqual([JSON_URI, OTHER_URI].sort());
  });

  it("имя, объявленное дважды, даёт обе строки объявления", () => {
    const text = jsonWith("{{ user.smart_geo }}");
    const index = indexWith({ [JSON_URI]: text }, PARAM_PY, USER_PY_TWICE);
    const found = referencesAt(
      index,
      documentContext(JSON_URI, text),
      caretAt(text, "smart_geo"),
      true,
    );
    expect(found.filter((f) => f.uri === USER_URI)).toHaveLength(2);
  });

  it("чужой корень вхождением не является", () => {
    const text = jsonWith("{{ user.smart_geo }} {{ other.smart_geo }}");
    expect(usagesOf(text, "smart_geo")).toHaveLength(1);
  });

  it("includeDeclaration добавляет строки объявления в Python", () => {
    const text = jsonWith("{{ user.smart_geo }}");
    const found = usagesOf(text, "smart_geo", true);
    expect(found.map((f) => f.uri)).toContain(USER_URI);
    expect(USER_PY.slice(found[0]?.start, found[0]?.end)).toBe("smart_geo");
  });

  it("у имени из снимка объявлений нет — список не меняется", () => {
    const text = jsonWith("{{ user.variables }}");
    expect(usagesOf(text, "variables", true)).toEqual(usagesOf(text, "variables", false));
  });

  it("на корневой переменной вхождений нет", () => {
    // Имени класса в тексте DSL не существует: там псевдоним из параметризатора.
    const text = jsonWith("{{ user.smart_geo }}");
    expect(usagesOf(text, "user")).toEqual([]);
  });
});

describe("переименование модели пользователя", () => {
  it("на атрибуте и на корне переименовывать нечего", () => {
    // Имя живёт в Python-коде и в Jinja-выражениях всех файлов приложения:
    // переписать их согласованно мы не умеем, поэтому rename обязан отказать, а
    // не переписать строку.
    const text = jsonWith("{{ user.smart_geo }}");
    const index = indexWith({ [JSON_URI]: text });
    const context = documentContext(JSON_URI, text);
    for (const caret of ["smart_geo", "user"]) {
      const at = caretAt(text, caret);
      expect(renameLookupAt(index, context, at).target, caret).toBeUndefined();
      expect(renameEdits(index, context, at, "renamed"), caret).toEqual([]);
    }
  });
});

describe("автодополнение модели пользователя", () => {
  const completeIn = (value: string, caret: string) => {
    const text = jsonWith(value);
    const index = indexWith({ [JSON_URI]: text });
    return completionAt(index, documentContext(JSON_URI, text), text.indexOf(caret) + caret.length);
  };

  it("после корня и точки предлагаются атрибуты", () => {
    const result = completeIn("{{ user. }}", "user.");
    expect(result.kind).toBe("user_field");
    expect(result.items.map((i) => i.label)).toContain("smart_geo");
    expect(result.items.map((i) => i.label)).toContain("variables");
  });

  it("подпись варианта — класс пользователя", () => {
    expect(completeIn("{{ user. }}", "user.").items[0]?.detail).toBe("CustomUser");
  });

  it("сама корневая переменная предлагается на месте идентификатора", () => {
    const result = completeIn("{{ us }}", "us");
    expect(result.kind).toBe("variable");
    expect(result.items.map((i) => i.label)).toContain("user");
  });

  it("в statement-теге работает так же", () => {
    const result = completeIn("{% if user. %}", "user.");
    expect(result.items.map((i) => i.label)).toContain("smart_geo");
  });

  it("после имени фильтра вариантов нет", () => {
    expect(completeIn("{{ x | user. }}", "user.").items).toEqual([]);
  });
});
