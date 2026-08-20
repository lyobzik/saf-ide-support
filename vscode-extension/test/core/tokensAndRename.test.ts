import { describe, expect, it } from "vitest";
import { SmartAppIndex } from "../../src/core/index";
import { documentContext } from "../../src/core/semantics";
import { SemanticTokenType, semanticTokens } from "../../src/core/semanticTokens";
import { renameEdits, renameLookupAt } from "../../src/core/rename";
import { referencesAt } from "../../src/core/semantics";

/**
 * Подсветка и переименование: диапазоны токенов Jinja (включая JSON-escape) и
 * состав правок rename. Ошибка трансляции decoded→raw→документ проявляется
 * именно здесь, поэтому проверяются точные подстроки, а не только типы.
 */

const root = "file:///p/static/references";
const scenarioUri = `${root}/scenarios/main.json`;
const formsUri = `${root}/forms/forms.json`;
const forms = '{ "hello_form": { "type": "form", "fields": { "name": { "type": "integration" } } } }';

const withIndex = (files: Record<string, string>): SmartAppIndex => {
  const index = new SmartAppIndex();
  for (const [uri, text] of Object.entries(files)) index.upsert(uri, text);
  index.markReady();
  return index;
};

const slice = (text: string, token: { start: number; end: number }) =>
  text.slice(token.start, token.end);

describe("семантическая подсветка", () => {
  it("подсвечивает структурные ключи и ключевые слова type", () => {
    // 'form_filling' — тип сценария; на верхнем уровне определения сценария
    // категория берётся по виду файла.
    const text = '{ "s": { "form": "hello_form", "type": "form_filling" } }';
    const tokens = semanticTokens(documentContext(scenarioUri, text));

    const structural = tokens.filter((t) => t.type === SemanticTokenType.STRUCTURAL_KEY);
    expect(structural.map((t) => slice(text, t))).toEqual(['"form"']);

    const keyword = tokens.filter((t) => t.type === SemanticTokenType.KEYWORD);
    expect(keyword.map((t) => slice(text, t))).toEqual(['"form_filling"']);
  });

  it("не подсвечивает значение type из чужой категории", () => {
    // 'external' — тип action, а не описания поля: в позиции fields не ключевое слово.
    const text = '{ "f": { "fields": { "x": { "type": "external" } } } }';
    const tokens = semanticTokens(documentContext(formsUri, text));
    expect(tokens.filter((t) => t.type === SemanticTokenType.KEYWORD)).toEqual([]);
  });

  it("размечает токены Jinja точными диапазонами", () => {
    const text = '{ "s": { "answer": "{{ main_form.name | upper }}" } }';
    const tokens = semanticTokens(documentContext(scenarioUri, text));
    const byType = (type: SemanticTokenType) =>
      tokens.filter((t) => t.type === type).map((t) => slice(text, t));

    expect(byType(SemanticTokenType.JINJA_DELIMITER)).toEqual(["{{", "}}"]);
    expect(byType(SemanticTokenType.JINJA_VARIABLE)).toEqual(["main_form", "name"]);
    expect(byType(SemanticTokenType.JINJA_OPERATOR)).toEqual(["."]);
    expect(byType(SemanticTokenType.JINJA_FILTER)).toEqual(["|", "upper"]);
  });

  it("правильно транслирует диапазоны через JSON-escape", () => {
    const text = '{ "s": { "answer": "{{ x | default(\\"y\\") }}" } }';
    const tokens = semanticTokens(documentContext(scenarioUri, text));
    const strings = tokens
      .filter((t) => t.type === SemanticTokenType.JINJA_STRING)
      .map((t) => slice(text, t));
    // Диапазон покрывает escape-последовательности исходника целиком.
    expect(strings).toEqual(['\\"y\\"']);
  });
});

describe("переименование", () => {
  it("переименовывает определение сущности и все ссылки", () => {
    const scenario = '{ "s": { "form": "hello_form" } }';
    const index = withIndex({ [formsUri]: forms, [scenarioUri]: scenario });
    const context = documentContext(formsUri, forms);
    const edits = renameEdits(index, context, forms.indexOf("hello_form"), "greeting_form");

    expect(edits).toHaveLength(2);
    for (const edit of edits) {
      const source = edit.uri === formsUri ? forms : scenario;
      // Правится только имя, кавычки остаются на месте.
      expect(source.slice(edit.start, edit.end)).toBe("hello_form");
      expect(edit.newText).toBe("greeting_form");
    }
  });

  it("переименование поля правит и определение, и Jinja-использование", () => {
    const scenario = '{ "s": { "form": "hello_form", "answer": "{{ main_form.name }}" } }';
    const index = withIndex({ [formsUri]: forms, [scenarioUri]: scenario });
    const context = documentContext(scenarioUri, scenario);
    const edits = renameEdits(index, context, scenario.indexOf("name }}"), "title");

    expect(edits).toHaveLength(2);
    expect(edits.map((e) => e.uri).sort()).toEqual([formsUri, scenarioUri].sort());
    for (const edit of edits) {
      const source = edit.uri === formsUri ? forms : scenario;
      expect(source.slice(edit.start, edit.end)).toBe("name");
    }
  });

  it("Jinja в JSON-ключе не считается использованием поля", () => {
    // Ключ "{{ main_form.name }}" — не ссылка: иначе переименование поля
    // переписало бы ключ объекта, чего плагин IDEA не делает.
    const scenario =
      '{ "s": { "form": "hello_form", "{{ main_form.name }}": "x", "v": "{{ main_form.name }}" } }';
    const index = withIndex({ [formsUri]: forms, [scenarioUri]: scenario });
    const context = documentContext(formsUri, forms);

    const usages = referencesAt(index, context, forms.indexOf('"name"') + 2, false);
    expect(usages).toHaveLength(1);
    // Единственное использование — значение, а не ключ.
    expect(scenario.indexOf('"v"')).toBeLessThan(usages[0]!.start);

    const edits = renameEdits(index, context, forms.indexOf('"name"') + 2, "title");
    expect(edits).toHaveLength(2);
    for (const edit of edits) {
      const source = edit.uri === formsUri ? forms : scenario;
      expect(source.slice(edit.start, edit.end)).toBe("name");
      if (edit.uri === scenarioUri) expect(edit.start).toBeGreaterThan(scenario.indexOf('"v"'));
    }
  });

  it("неоднозначная ссылка переименованию не подлежит", () => {
    // Правило `action` в external-обёртке допускает и action, и behavior:
    // выбрать один молча нельзя — переименуется не та сущность.
    const actionsUri = `${root}/actions/a.json`;
    const behaviorsUri = `${root}/behaviors/b.json`;
    const scenario = '{ "s": { "actions": [{ "type": "external", "action": "shared" }] } }';
    const index = withIndex({
      [actionsUri]: '{ "shared": { "type": "sdk_answer" } }',
      [behaviorsUri]: '{ "shared": { "type": "base" } }',
      [scenarioUri]: scenario,
    });
    const context = documentContext(scenarioUri, scenario);
    const offset = scenario.indexOf('"shared"') + 2;

    const lookup = renameLookupAt(index, context, offset);
    expect(lookup.target).toBeUndefined();
    expect(lookup.reason).toContain("неоднозначна");
    expect(renameEdits(index, context, offset, "renamed")).toEqual([]);
  });

  it("однозначная ссылка переименовывается по фактически найденному виду", () => {
    // Тот же контекст, но определение есть только как behavior.
    const behaviorsUri = `${root}/behaviors/b.json`;
    const behaviors = '{ "only_behavior": { "type": "base" } }';
    const scenario = '{ "s": { "actions": [{ "type": "external", "action": "only_behavior" }] } }';
    const index = withIndex({ [behaviorsUri]: behaviors, [scenarioUri]: scenario });
    const context = documentContext(scenarioUri, scenario);
    const offset = scenario.indexOf('"only_behavior"') + 2;

    expect(renameLookupAt(index, context, offset).target?.entityKind).toBe("BEHAVIOR");
    const edits = renameEdits(index, context, offset, "renamed");
    expect(edits.map((e) => e.uri).sort()).toEqual([behaviorsUri, scenarioUri].sort());
  });

  it("вне ссылочной позиции правок нет", () => {
    const scenario = '{ "s": { "unknown_key": "value" } }';
    const index = withIndex({ [scenarioUri]: scenario });
    const context = documentContext(scenarioUri, scenario);
    expect(renameEdits(index, context, scenario.indexOf("value"), "x")).toEqual([]);
  });
});
