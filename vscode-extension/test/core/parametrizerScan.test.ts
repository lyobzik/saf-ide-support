import { describe, expect, it } from "vitest";

import { parseModule, type DictionaryUse } from "../../src/core/pythonScan";

/**
 * Правило `<d>`: какие формы тела `_get_user_data` дают надёжные привязки, а
 * какие отменяют доказательство целиком.
 *
 * Та же таблица входов прогоняется в плагине (`SmartAppParametrizerScanTest`).
 * Сравнивается строковый снимок разбора — так расхождение портов видно целиком,
 * а не по одному полю.
 */

const render = (use: DictionaryUse | undefined): string =>
  use === undefined
    ? "правило нарушено"
    : `<d>=${use.name} super=${use.inheritsBase} ` +
      `[${use.bindings.map((b) => `${b.key}=${b.value ?? "?"}`).join(" ")}]`;

const scan = (body: string): DictionaryUse | undefined =>
  parseModule(
    "class CustomParametrizer(Parametrizer):\n    def _get_user_data(self, tpr=None):\n" + body,
  ).classes[0]?.methods.find((method) => method.name === "_get_user_data")?.dictionary;

const dictionaryOf = (body: string): string => render(scan(body));

describe("поддержанные формы", () => {
  const cases: Record<string, [string, string]> = {
    "эталонный параметризатор приложения": [
      "        data = super()._get_user_data(tpr)\n        data.update({})\n        return data\n",
      "<d>=data super=true []",
    ],
    "библиотечный стиль: словарь одним литералом": [
      '        tpr_data = tpr.raw if tpr else {}\n        forms = self._user.forms.collect_form_fields()\n' +
        '        data = {\n            "counters": self._user.counters.raw,\n' +
        '            "forms": forms,\n            "message": self._user.message,\n        }\n' +
        "        return data\n",
      "<d>=data super=false [counters=self._user.counters.raw forms=forms message=self._user.message]",
    ],
    "запись подпиской": [
      '        data = super()._get_user_data(tpr)\n        data["user"] = self._user\n        return data\n',
      "<d>=data super=true [user=self._user]",
    ],
    "запись через update": [
      '        data = super()._get_user_data(tpr)\n        data.update({"user": self._user})\n        return data\n',
      "<d>=data super=true [user=self._user]",
    ],
    "одно значение под двумя ключами": [
      '        data = {}\n        data.update({"user": self._user, "account": self._user})\n        return data\n',
      "<d>=data super=false [user=self._user account=self._user]",
    ],
    "аннотированная инициализация": [
      '        data: Dict[str, Any] = super()._get_user_data(tpr)\n        data["user"] = self._user\n        return data\n',
      "<d>=data super=true [user=self._user]",
    ],
    "инициализация dict()": [
      '        data = dict()\n        data["user"] = self._user\n        return data\n',
      "<d>=data super=false [user=self._user]",
    ],
    "инициализация литералом с ключами": [
      '        data = {"user": self._user}\n        return data\n',
      "<d>=data super=false [user=self._user]",
    ],
    "голый вызов super() наследования не даёт": [
      '        data = {}\n        super()._get_user_data(tpr)\n        data["user"] = self._user\n        return data\n',
      "<d>=data super=false [user=self._user]",
    ],
    "вспомогательные локали правилу не мешают": [
      '        forms = self._user.forms\n        if forms:\n            log.info("x")\n' +
        '        data = {}\n        data["user"] = self._user\n        return data\n',
      "<d>=data super=false [user=self._user]",
    ],
    "порядок записей — по смещению, а не по форме": [
      '        data = {}\n        data.update({"user": OTHER}); data["user"] = self._user\n        return data\n',
      "<d>=data super=false [user=OTHER user=self._user]",
    ],
    "значение не распознано": [
      '        data = {}\n        data["user"] = build_user()\n        return data\n',
      "<d>=data super=false [user=?]",
    ],
    "чужое значение читается как чужое": [
      '        data = {}\n        data["user"] = other_user\n        return data\n',
      "<d>=data super=false [user=other_user]",
    ],
    "хвостовая запятая делает значение кортежем": [
      '        data = {}\n        data["user"] = self._user,\n        return data\n',
      "<d>=data super=false [user=?]",
    ],
    "хвостовой комментарий значению не мешает": [
      '        data = {}\n        data.update({\n            "user": self._user  # корень\n        })\n        return data\n',
      "<d>=data super=false [user=self._user]",
    ],
    "строковое значение точечным именем не считается": [
      '        data = {}\n        data.update({"user": "self._user"})\n        return data\n',
      "<d>=data super=false [user=?]",
    ],
  };

  for (const [title, [body, expected]] of Object.entries(cases)) {
    it(title, () => {
      expect(dictionaryOf(body)).toBe(expected);
    });
  }
});

describe("формы, отменяющие доказательство", () => {
  const cases: Record<string, string> = {
    "мёртвый код после return": '        data = {}\n        return data\n        data["dead"] = self._user\n',
    "return не последний оператор": "        data = {}\n        return data\n        x = 1\n",
    "два return": "        data = {}\n        if x:\n            return data\n        return data\n",
    "return не барного имени": '        return {"user": self._user}\n',
    "нет инициализации": '        data["user"] = self._user\n        return data\n',
    "вторая инициализация": "        data = {}\n        data = {}\n        return data\n",
    "запись в ветке if": '        data = {}\n        if enabled:\n            data["user"] = self._user\n        return data\n',
    "запись в однострочном if": '        data = {}\n        if enabled: data["user"] = self._user\n        return data\n',
    "запись в ветке match": '        data = {}\n        match mode:\n            case "a":\n                data["user"] = self._user\n        return data\n',
    "алиас словаря": "        data = {}\n        tmp = data\n        return data\n",
    "точечный префикс имени": '        data = {}\n        other.data["user"] = self._user\n        return data\n',
    "передача словаря в функцию": "        data = {}\n        helper(data)\n        return data\n",
    // Все упоминания `data` здесь поддержаны — отменяет доказательство именно
    // гаситель: он меняет содержимое в обход имени.
    "гаситель в теле при исправных упоминаниях":
      '        data = {}\n        data["user"] = self._user\n' +
      "        log.debug(vars(self))\n        return data\n",
    "гаситель в аргументе update": "        data = {}\n        data.update(vars(self))\n        return data\n",
    "распаковка внутри литерала": '        data = {}\n        data.update({"user": self._user, **other})\n        return data\n',
    "второй аргумент update": '        data = {}\n        data.update({"user": self._user}, user=override)\n        return data\n',
    "аргумент update не литерал": "        data = {}\n        data.update(other)\n        return data\n",
    // `super(Base, self)` начинает поиск по MRO ПОСЛЕ `Base`, то есть метод
    // самой `Base` не вызывает вовсе. Форму с аргументами не разбираем совсем:
    // инициализация не опознана, значит и правило не выполнено.
    "аргументированный super в инициализации":
      "        data = super(Base, self)._get_user_data(tpr)\n        return data\n",
    "super с самим классом тоже не принимается":
      "        data = super(C, self)._get_user_data(tpr)\n        return data\n",
    "вычисляемый ключ в литерале": "        data = {}\n        data.update({key: self._user})\n        return data\n",
  };

  for (const [title, body] of Object.entries(cases)) {
    it(title, () => {
      expect(dictionaryOf(body)).toBe("правило нарушено");
    });
  }
});

describe("границы разбора", () => {
  it("тело метода на строке заголовка разбирается", () => {
    const module = parseModule(
      "class P(Parametrizer):\n" +
        '    def _get_user_data(self, tpr=None): data = {}; data["user"] = self._user; return data\n',
    );
    expect(render(module.classes[0]?.methods[0]?.dictionary)).toBe(
      "<d>=data super=false [user=self._user]",
    );
  });

  it("ключ ведёт на содержимое литерала", () => {
    const source =
      "class P(Parametrizer):\n    def _get_user_data(self, tpr=None):\n" +
      '        data = {}\n        data["user"] = self._user\n        return data\n';
    const binding = parseModule(source).classes[0]?.methods[0]?.dictionary?.bindings[0];
    expect(source.slice(binding?.nameStart, binding?.nameEnd)).toBe("user");
  });

  it("декорированный метод не разбирается", () => {
    // Декоратор подменяет результат целиком: тело собирает одно, а в шаблон
    // уедет то, что вернул декоратор.
    for (const decorator of ["    @staticmethod\n", "    @wrap_result\n"]) {
      const module = parseModule(
        "class P(Parametrizer):\n" +
          decorator +
          "    def _get_user_data(self, tpr=None):\n" +
          '        data = {}\n        data["user"] = self._user\n        return data\n',
      );
      expect(module.classes[0]?.methods[0]?.dictionary, decorator).toBeUndefined();
    }
  });

  it("метод с другим именем словаря не разбирается", () => {
    const module = parseModule(
      "class P(Parametrizer):\n    def collect(self, tpr=None):\n" +
        '        data = {}\n        data["user"] = self._user\n        return data\n',
    );
    expect(module.classes[0]?.methods[0]?.dictionary).toBeUndefined();
  });
});
