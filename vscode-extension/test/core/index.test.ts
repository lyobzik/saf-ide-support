import { describe, expect, it } from "vitest";
import { referencesRoot } from "../../src/core/files";
import { SmartAppIndex } from "../../src/core/index";
import { definitionsAt, diagnostics, documentContext, referencesAt } from "../../src/core/semantics";

/**
 * Быстрая обратная связь по фундаменту ядра: индекс, резолв, диагностика,
 * изоляция наборов и строгость индексации. Полный паритет с IDEA проверяет
 * conformance-корпус из shared/fixtures; здесь — базовые инварианты.
 */

const root = "file:///project/static/references";
const scenarioUri = `${root}/scenarios/main.json`;
const formsUri = `${root}/forms/forms.json`;

const withIndex = (files: Record<string, string>): SmartAppIndex => {
  const index = new SmartAppIndex();
  for (const [uri, text] of Object.entries(files)) index.upsert(uri, text);
  index.markReady();
  return index;
};

/** Смещение каретки по маркеру |, который вырезается из текста. */
const caret = (text: string): { text: string; offset: number } => {
  const offset = text.indexOf("|");
  return { text: text.slice(0, offset) + text.slice(offset + 1), offset };
};

describe("резолв кросс-ссылок", () => {
  const forms = '{ "hello_form": { "type": "form" } }';

  it("form в сценарии ведёт на определение формы", () => {
    const { text, offset } = caret('{ "s": { "form": "hel|lo_form" } }');
    const index = withIndex({ [formsUri]: forms, [scenarioUri]: text });
    const found = definitionsAt(index, documentContext(scenarioUri, text), offset);
    expect(found).toHaveLength(1);
    expect(found[0]?.uri).toBe(formsUri);
  });

  it("неразрешённая ссылка даёт предупреждение с текстом как в IDEA", () => {
    const text = '{ "s": { "form": "missing_form" } }';
    const index = withIndex({ [formsUri]: forms, [scenarioUri]: text });
    const found = diagnostics(index, documentContext(scenarioUri, text));
    expect(found).toHaveLength(1);
    expect(found[0]?.message).toBe("Не удаётся разрешить form 'missing_form'");
    // Диапазон покрывает литерал вместе с кавычками — как textRange в IDEA.
    expect(text.slice(found[0]!.start, found[0]!.end)).toBe('"missing_form"');
  });

  it("Jinja-значение не считается битой ссылкой", () => {
    const text = '{ "s": { "form": "{{ dynamic }}" } }';
    const index = withIndex({ [formsUri]: forms, [scenarioUri]: text });
    expect(diagnostics(index, documentContext(scenarioUri, text))).toEqual([]);
  });

  it("external action ссылается и на action, и на behavior", () => {
    const behaviorsUri = `${root}/behaviors/b.json`;
    const text = '{ "s": { "actions": [{ "type": "external", "action": "shared_name" }] } }';
    const index = withIndex({
      [behaviorsUri]: '{ "shared_name": { "type": "process_behavior" } }',
      [scenarioUri]: text,
    });
    const offset = text.indexOf("shared_name");
    expect(definitionsAt(index, documentContext(scenarioUri, text), offset)).toHaveLength(1);
  });
});

describe("определения", () => {
  it("дубликат top-level ключа даёт два различимых определения", () => {
    const text = '{ "s": { "form": "dup" } }';
    const index = withIndex({
      [formsUri]: '{ "dup": { "type": "form" }, "dup": { "type": "base" } }',
      [scenarioUri]: text,
    });
    const found = definitionsAt(index, documentContext(scenarioUri, text), text.indexOf("dup"));
    expect(found).toHaveLength(2);
    expect(found.map((d) => ("ordinal" in d ? d.ordinal : undefined))).toEqual([0, 1]);
  });

  it("наборы references изолированы друг от друга", () => {
    const otherRoot = "file:///project/other/static/references";
    const text = '{ "s": { "form": "far_form" } }';
    const index = withIndex({
      [`${otherRoot}/forms/f.json`]: '{ "far_form": { "type": "form" } }',
      [scenarioUri]: text,
    });
    expect(definitionsAt(index, documentContext(scenarioUri, text), text.indexOf("far_form"))).toEqual(
      [],
    );
  });
});

describe("строгость индексации", () => {
  const scenario = '{ "s": { "form": "hello_form" } }';

  it.each([
    ["оборванный JSON", '{ "hello_form": '],
    ["мусор после корневого объекта", '{ "hello_form": { "type": "form" } } junk'],
    ["массив на верхнем уровне", "[1, 2, 3]"],
    ["строка на верхнем уровне", '"hello_form"'],
    ["пустой файл", ""],
  ])("%s не даёт определений", (_name, formsText) => {
    const index = withIndex({ [formsUri]: formsText, [scenarioUri]: scenario });
    expect(definitionsAt(index, documentContext(scenarioUri, scenario), scenario.indexOf("hello"))).toEqual(
      [],
    );
  });

  it.each([
    ["комментарий", '{\n  // комментарий\n  "hello_form": { "type": "form" }\n}'],
    ["висячая запятая", '{ "hello_form": { "type": "form" }, }'],
  ])("%s индексируется — как в IDEA", (_name, formsText) => {
    const index = withIndex({ [formsUri]: formsText, [scenarioUri]: scenario });
    expect(
      definitionsAt(index, documentContext(scenarioUri, scenario), scenario.indexOf("hello")),
    ).toHaveLength(1);
  });

  it("битый файл не мешает соседям и сам продолжает резолвить ссылки", () => {
    const brokenUri = `${root}/scenarios/broken.json`;
    const broken = '{ "s": { "form": "hello_form" ';
    const index = withIndex({
      [formsUri]: '{ "hello_form": { "type": "form" } }',
      [brokenUri]: broken,
    });
    // Определения битого файла в индекс не попали...
    expect(index.namesOfKind("SCENARIO", referencesRoot(brokenUri))).toEqual([]);
    // ...но ссылка внутри него по-прежнему резолвится.
    expect(
      definitionsAt(index, documentContext(brokenUri, broken), broken.indexOf("hello_form")),
    ).toHaveLength(1);
  });
});

describe("поля формы в Jinja", () => {
  const forms = '{ "hello_form": { "type": "form", "fields": { "name": { "type": "string" } } } }';

  it("резолвит main_form.<field> и даёт диапазон только на имя поля", () => {
    const text = '{ "s": { "form": "hello_form", "answer": "{{ main_form.name }}" } }';
    const index = withIndex({ [formsUri]: forms, [scenarioUri]: text });
    const offset = text.indexOf("name }}");
    const found = definitionsAt(index, documentContext(scenarioUri, text), offset);
    expect(found).toHaveLength(1);
    expect(forms.slice(found[0]!.start, found[0]!.end)).toBe('"name"');
  });

  it("неизвестное поле известной формы даёт предупреждение на имени поля", () => {
    const text = '{ "s": { "form": "hello_form", "answer": "{{ main_form.age }}" } }';
    const index = withIndex({ [formsUri]: forms, [scenarioUri]: text });
    const found = diagnostics(index, documentContext(scenarioUri, text));
    expect(found).toHaveLength(1);
    expect(found[0]?.message).toBe("Не удаётся разрешить поле 'age' формы 'hello_form'");
    expect(text.slice(found[0]!.start, found[0]!.end)).toBe("age");
  });

  it("динамическая форма не даёт ни ссылки, ни предупреждения", () => {
    const text = '{ "s": { "form": "{{ x }}", "answer": "{{ main_form.age }}" } }';
    const index = withIndex({ [formsUri]: forms, [scenarioUri]: text });
    expect(diagnostics(index, documentContext(scenarioUri, text))).toEqual([]);
  });

  it("находит использования поля от его определения", () => {
    const text = '{ "s": { "form": "hello_form", "answer": "{{ main_form.name }}" } }';
    const index = withIndex({ [formsUri]: forms, [scenarioUri]: text });
    const context = documentContext(formsUri, forms);
    const usages = referencesAt(index, context, forms.indexOf('"name"') + 2, false);
    expect(usages).toHaveLength(1);
    expect(usages[0]?.uri).toBe(scenarioUri);
  });
});

describe("готовность индекса", () => {
  it("до готовности резолв и диагностика молчат", () => {
    const text = '{ "s": { "form": "missing" } }';
    const index = new SmartAppIndex();
    index.upsert(scenarioUri, text);
    expect(definitionsAt(index, documentContext(scenarioUri, text), text.indexOf("missing"))).toEqual(
      [],
    );
    expect(diagnostics(index, documentContext(scenarioUri, text))).toEqual([]);
  });
});
