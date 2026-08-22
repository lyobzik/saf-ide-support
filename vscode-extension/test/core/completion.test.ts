import { describe, expect, it } from "vitest";
import { SmartAppIndex } from "../../src/core/index";
import { completionAt } from "../../src/core/completion";
import { documentContext } from "../../src/core/semantics";

/**
 * Порт completion-кейсов из `SmartAppDslTest`/`SmartAppFormFieldTest`: категории
 * ключевых слов, имена сущностей и поля формы в Jinja вместе с их негативами.
 */

const root = "file:///p/static/references";
const scenarioUri = `${root}/scenarios/main.json`;
const formsUri = `${root}/forms/forms.json`;
const forms =
  '{ "hello_form": { "type": "form", "fields": { "name": {}, "age": {}, "odd.field": {} } } }';

const index = (files: Record<string, string>): SmartAppIndex => {
  const idx = new SmartAppIndex();
  for (const [uri, text] of Object.entries(files)) idx.upsert(uri, text);
  idx.markReady();
  return idx;
};

/** Каретка задаётся маркером |, который вырезается из текста. */
const at = (uri: string, source: string, files: Record<string, string> = {}) => {
  const offset = source.indexOf("|");
  const text = source.slice(0, offset) + source.slice(offset + 1);
  return completionAt(index({ ...files, [uri]: text }), documentContext(uri, text), offset);
};

describe("ключевые слова type", () => {
  it("в fields предлагается категория field_description", () => {
    const result = at(formsUri, '{ "f": { "fields": { "x": { "type": "|" } } } }');
    expect(result.kind).toBe("keyword");
    expect(result.items.map((i) => i.label)).toEqual(["composite", "integration", "question"]);
    // Тип action-контейнера в позиции описания поля не предлагается.
    expect(result.items.map((i) => i.label)).not.toContain("external");
  });

  it("в on_filled_actions предлагается категория action", () => {
    const result = at(scenarioUri, '{ "s": { "on_filled_actions": [{ "type": "|" }] } }');
    expect(result.items.map((i) => i.label)).toContain("external");
  });
});

describe("имена сущностей", () => {
  it("предлагает имена форм в позиции form", () => {
    const result = at(scenarioUri, '{ "s": { "form": "|" } }', { [formsUri]: forms });
    expect(result.kind).toBe("name");
    expect(result.items.map((i) => i.label)).toEqual(["hello_form"]);
  });
});

describe("поля формы внутри Jinja", () => {
  const scenario = (jinja: string) =>
    `{ "s": { "form": "hello_form", "answer": "${jinja}" } }`;

  it("предлагает поля после main_form.", () => {
    const result = at(scenarioUri, scenario("{{ main_form.|"), { [formsUri]: forms });
    expect(result.kind).toBe("field");
    // Поле с не-identifier именем не предлагается: лексер его не резолвит.
    expect(result.items.map((i) => i.label)).toEqual(["name", "age"]);
  });

  it("сужает диапазон замены до набранного префикса", () => {
    const source = scenario("{{ main_form.na|");
    const offset = source.indexOf("|");
    const result = at(scenarioUri, source, { [formsUri]: forms });
    expect(result.replaceEnd - result.replaceStart).toBe(2);
    expect(result.replaceEnd).toBe(offset);
  });

  it("предлагает поля и после пробела за точкой", () => {
    const result = at(scenarioUri, scenario("{{ main_form. |"), { [formsUri]: forms });
    expect(result.items.map((i) => i.label)).toEqual(["name", "age"]);
  });

  it("предлагает поля и в statement-теге", () => {
    const result = at(scenarioUri, scenario("{% set x = main_form.| %}"), { [formsUri]: forms });
    expect(result.items.map((i) => i.label)).toEqual(["name", "age"]);
  });

  it("ничего не предлагает после закрытой интерполяции", () => {
    const result = at(scenarioUri, scenario("{{ main_form.name }} |"), { [formsUri]: forms });
    expect(result.items).toEqual([]);
  });

  it("ничего не предлагает в цепочке подобъектов", () => {
    const result = at(scenarioUri, scenario("{{ main_form.name.|"), { [formsUri]: forms });
    expect(result.items).toEqual([]);
  });

  it("ничего не предлагает внутри строкового аргумента фильтра", () => {
    const result = at(scenarioUri, scenario("{{ x | default('main_form.|"), { [formsUri]: forms });
    expect(result.items).toEqual([]);
  });

  it("ничего не предлагает при динамической форме", () => {
    const text = '{ "s": { "form": "{{ x }}", "answer": "{{ main_form.|" } }';
    const result = at(scenarioUri, text, { [formsUri]: forms });
    expect(result.items).toEqual([]);
  });
});

describe("позиция main_form в выражении", () => {
  // Слева от обращения к полю почти всегда стоит ключевое слово Jinja, а не
  // присваивание: `{% if main_form.x %}` — самая частая форма в бою. Пока
  // проверялся только `{% set x = … %}`, пробел после слова гасил completion.
  const fields = (jinja: string) =>
    at(scenarioUri, `{ "s": { "form": "hello_form", "a": "${jinja}" } }`, { [formsUri]: forms })
      .items.map((i) => i.label);

  it.each([
    ["{{ main_form.| }}", true],
    ["{% set x = main_form.| %}", true],
    ["{% if main_form.| %}", true],
    ["{% if not main_form.| %}", true],
    ["{% for i in main_form.| %}", true],
    ["{% if a and main_form.| %}", true],
    ["{{ f(main_form.|) }}", true],
    ["{{ f(a, main_form.|) }}", true],
    ["{{ variables.main_form.| }}", false],
    ["{{ variables. main_form.| }}", false],
    ["{{ xmain_form.| }}", false],
  ])("%s -> %s", (jinja, offered) => {
    expect(fields(jinja as string).includes("name")).toBe(offered);
  });
});

describe("переменная формы", () => {
  const jinjaAt = (jinja: string) =>
    at(scenarioUri, `{ "s": { "form": "hello_form", "a": "${jinja}" } }`, { [formsUri]: forms });

  it("предлагается на месте начатого идентификатора", () => {
    const result = jinjaAt("{% if mai| %}");
    expect(result.kind).toBe("variable");
    expect(result.items.map((i) => i.label)).toEqual(["main_form"]);
    // Подпись — имя целевой формы: из текста выражения её не видно.
    expect(result.items[0]?.detail).toBe("hello_form");
  });

  it("предлагается на пустом месте выражения", () => {
    expect(jinjaAt("{{ | }}").items.map((i) => i.label)).toEqual(["main_form"]);
    expect(jinjaAt("{% if | %}").items.map((i) => i.label)).toEqual(["main_form"]);
  });

  it("каретка посреди слова заменяет слово целиком", () => {
    // Иначе принятый вариант дал бы main_formform.person.
    const source = `{ "s": { "form": "hello_form", "a": "{% if main_|form.person %}" } }`;
    const result = at(scenarioUri, source, { [formsUri]: forms });
    const text = source.replace("|", "");
    expect(text.slice(result.replaceStart, result.replaceEnd)).toBe("main_form");
  });

  it("после точки переменная не предлагается", () => {
    // Это позиция поля (чужого объекта), а не переменной.
    expect(jinjaAt("{{ variables.| }}").items).toEqual([]);
    expect(jinjaAt("{{ variables. | }}").items).toEqual([]);
  });

  it("при динамической форме вариантов нет", () => {
    const result = at(
      scenarioUri,
      '{ "s": { "form": "{{ x }}", "a": "{% if mai| %}" } }',
      { [formsUri]: forms },
    );
    expect(result.items).toEqual([]);
  });
});

describe("позиция имени фильтра", () => {
  // Каретка здесь обозначается ‸: | занят оператором фильтра.
  const atCaret = (jinja: string) => {
    const source = `{ "s": { "form": "hello_form", "a": "${jinja}" } }`;
    const offset = source.indexOf("‸");
    const text = source.slice(0, offset) + source.slice(offset + 1);
    return completionAt(
      index({ [formsUri]: forms, [scenarioUri]: text }),
      documentContext(scenarioUri, text),
      offset,
    ).items.map((i) => i.label);
  };

  it("после | не предлагается ни переменная, ни поле", () => {
    // Jinja ждёт здесь имя фильтра, а не значение.
    expect(atCaret("{{ x | ‸ }}")).toEqual([]);
    expect(atCaret("{{ x | mai‸ }}")).toEqual([]);
    expect(atCaret("{{ x |mai‸ }}")).toEqual([]);
    expect(atCaret("{{ x | main_form.‸ }}")).toEqual([]);
  });

  it("аргумент фильтра — обычная позиция значения", () => {
    expect(atCaret("{{ x | default(main_form.‸) }}")).toEqual(["name", "age"]);
    expect(atCaret("{{ x | default(mai‸) }}")).toEqual(["main_form"]);
  });
});

describe("имена файлов шаблонов", () => {
  const templates = {
    [`${root}/templates/items.jinja2`]: "",
    [`${root}/templates/nested/deep.jinja2`]: "",
  };
  const form = (value: string) =>
    `{ "f": { "fields": { "g": { "items": { "type": "unified_template", "file": "${value}" } } } } }`;

  it("предлагает пути каталога шаблонов, включая вложенные", () => {
    const result = at(formsUri, form("|"), templates);
    expect(result.kind).toBe("file");
    expect(result.items.map((i) => i.label).sort()).toEqual([
      "items.jinja2",
      "nested/deep.jinja2",
    ]);
    expect(result.items.every((i) => i.detail === "file")).toBe(true);
  });

  it("заменяет всё содержимое литерала, чтобы редактор фильтровал и по слэшу", () => {
    const source = form("nested/|");
    const result = at(formsUri, source, templates);
    const text = source.replace("|", "");
    expect(text.slice(result.replaceStart, result.replaceEnd)).toBe("nested/");
  });

  it("другой type владельца — не файловая ссылка", () => {
    const result = at(
      formsUri,
      '{ "f": { "fields": { "g": { "items": { "type": "string", "file": "|" } } } } }',
      templates,
    );
    expect(result.items).toEqual([]);
  });

  it("динамическое имя предложений не даёт", () => {
    const result = at(formsUri, form("{{ x.| }}"), templates);
    expect(result.items).toEqual([]);
  });

  it("до готовности индекса вариантов нет", () => {
    // Реестр наполняется в ходе первичного сканирования: частичный список молча
    // изменился бы под пользователем. В плагине IDEA гейта нет — там источник
    // (VFS) полон всегда, и это закреплено отдельным тестом.
    const source = form("|");
    const offset = source.indexOf("|");
    const text = source.slice(0, offset) + source.slice(offset + 1);
    const notReady = new SmartAppIndex();
    for (const [uri, content] of Object.entries({ ...templates, [formsUri]: text })) {
      notReady.upsert(uri, content);
    }
    expect(completionAt(notReady, documentContext(formsUri, text), offset).items).toEqual([]);
  });
});
