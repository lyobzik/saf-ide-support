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

  it("ничего не предлагает в statement-теге", () => {
    const result = at(scenarioUri, scenario("{% set x = main_form.| %}"), { [formsUri]: forms });
    expect(result.items).toEqual([]);
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
