import { describe, expect, it } from "vitest";
import * as vscodeMock from "../mocks/vscode";
import { SmartAppIndex } from "../../src/core/index";
import {
  SEMANTIC_TOKEN_LEGEND,
  createCompletionProvider,
  createDefinitionProvider,
  createReferenceProvider,
  createRenameProvider,
  createSemanticTokensProvider,
  refreshDiagnostics,
} from "../../src/vscode/providers";
import { SemanticTokenType } from "../../src/core/semanticTokens";

/**
 * Обязательный адаптерный уровень: ядро отдаёт смещения, а пользователь видит
 * результат провайдеров. Ошибки трансляции offset→Position, неверные Uri,
 * диапазон CompletionItem и содержимое WorkspaceEdit ядровые тесты не увидят.
 */

const root = "file:///p/static/references";
const scenarioUri = `${root}/scenarios/main.json`;
const formsUri = `${root}/forms/forms.json`;

// Фикстуры намеренно многострочные: на однострочных ошибка в подсчёте строк
// осталась бы незаметной.
const forms = [
  "{",
  '  "hello_form": {',
  '    "type": "form",',
  '    "fields": {',
  '      "name": {}',
  "    }",
  "  }",
  "}",
].join("\n");

const scenario = [
  "{",
  '  "main": {',
  '    "form": "hello_form",',
  '    "answer": "{{ main_form.name }}"',
  "  }",
  "}",
].join("\n");

/** Фейковый воркспейс: тот же контракт, что у настоящего, без файловой системы. */
const workspace = (() => {
  const index = new SmartAppIndex();
  const texts = new Map<string, string>([
    [formsUri, forms],
    [scenarioUri, scenario],
  ]);
  for (const [uri, text] of texts) index.upsert(uri, text);
  index.markReady();
  return { index, textOf: (uri: string) => texts.get(uri) } as never;
})();

const doc = (uri: string, text: string) =>
  new vscodeMock.TextDocument(vscodeMock.Uri.parse(uri), text) as never;

/** Позиция каретки по подстроке — читаемее, чем ручные номера строк. */
const positionOf = (text: string, needle: string, offsetInNeedle = 0) => {
  const document = new vscodeMock.TextDocument(vscodeMock.Uri.parse("file:///x"), text);
  return document.positionAt(text.indexOf(needle) + offsetInNeedle) as never;
};

const lineOf = (text: string, line: number): string => text.split("\n")[line] as string;

describe("DefinitionProvider", () => {
  it("возвращает Location с правильным Uri и диапазоном в целевом файле", async () => {
    const provider = createDefinitionProvider(workspace);
    const locations = (await provider.provideDefinition(
      doc(scenarioUri, scenario),
      positionOf(scenario, '"hello_form"', 2),
      undefined as never,
    )) as unknown as vscodeMock.Location[];

    expect(locations).toHaveLength(1);
    expect(locations[0]?.uri.toString()).toBe(formsUri);
    expect(locations[0]?.range.start.line).toBe(1);
    expect(
      lineOf(forms, 1).slice(
        locations[0]!.range.start.character,
        locations[0]!.range.end.character,
      ),
    ).toBe('"hello_form"');
  });

  it("для поля формы в Jinja ведёт на определение поля", async () => {
    const provider = createDefinitionProvider(workspace);
    const locations = (await provider.provideDefinition(
      doc(scenarioUri, scenario),
      positionOf(scenario, "main_form.name", 11),
      undefined as never,
    )) as unknown as vscodeMock.Location[];

    expect(locations).toHaveLength(1);
    expect(locations[0]?.uri.toString()).toBe(formsUri);
    expect(locations[0]?.range.start.line).toBe(4);
    expect(
      lineOf(forms, 4).slice(
        locations[0]!.range.start.character,
        locations[0]!.range.end.character,
      ),
    ).toBe('"name"');
  });
});

describe("ReferenceProvider", () => {
  it("находит использование поля из его определения", async () => {
    const provider = createReferenceProvider(workspace);
    const locations = (await provider.provideReferences(
      doc(formsUri, forms),
      positionOf(forms, '"name"', 2),
      { includeDeclaration: false },
      undefined as never,
    )) as unknown as vscodeMock.Location[];

    expect(locations).toHaveLength(1);
    expect(locations[0]?.uri.toString()).toBe(scenarioUri);
    expect(locations[0]?.range.start.line).toBe(3);
    expect(
      lineOf(scenario, 3).slice(
        locations[0]!.range.start.character,
        locations[0]!.range.end.character,
      ),
    ).toBe("name");
  });
});

describe("CompletionItemProvider", () => {
  it("предлагает поля формы и заменяет только набранный префикс", async () => {
    const text = scenario.replace("{{ main_form.name }}", "{{ main_form.na");
    const provider = createCompletionProvider(workspace);
    const items = (await provider.provideCompletionItems(
      doc(scenarioUri, text),
      positionOf(text, "main_form.na", 12),
      undefined as never,
      undefined as never,
    )) as unknown as vscodeMock.CompletionItem[];

    expect(items.map((i) => i.label)).toEqual(["name"]);
    const range = items[0]?.range as vscodeMock.Range;
    expect(range.end.character - range.start.character).toBe(2);
    expect(items[0]?.kind).toBe(vscodeMock.CompletionItemKind.Field);
  });
});

describe("DocumentSemanticTokensProvider", () => {
  it("отдаёт токены в координатах строк, а не смещений", async () => {
    const provider = createSemanticTokensProvider();
    const tokens = (await provider.provideDocumentSemanticTokens(
      doc(scenarioUri, scenario),
      undefined as never,
    )) as unknown as vscodeMock.SemanticTokens;

    const jinjaVariable = SEMANTIC_TOKEN_LEGEND.tokenTypes.indexOf(
      SemanticTokenType.JINJA_VARIABLE,
    );
    const variables = tokens.tokens.filter((t) => t.typeIndex === jinjaVariable);
    expect(variables).toHaveLength(2);
    expect(variables.every((t) => t.line === 3)).toBe(true);
    expect(variables.map((t) => lineOf(scenario, 3).slice(t.char, t.char + t.length))).toEqual([
      "main_form",
      "name",
    ]);
  });
});

describe("RenameProvider", () => {
  it("собирает WorkspaceEdit по всем затронутым файлам", async () => {
    const provider = createRenameProvider(workspace);
    const edit = (await provider.provideRenameEdits(
      doc(formsUri, forms),
      positionOf(forms, '"hello_form"', 2),
      "greeting_form",
      undefined as never,
    )) as unknown as vscodeMock.WorkspaceEdit;

    expect(edit.edits.map((e) => e.uri).sort()).toEqual([formsUri, scenarioUri].sort());
    for (const applied of edit.edits) {
      expect(applied.newText).toBe("greeting_form");
      const source = applied.uri === formsUri ? forms : scenario;
      const line = lineOf(source, applied.range.start.line);
      expect(line.slice(applied.range.start.character, applied.range.end.character)).toBe(
        "hello_form",
      );
    }
  });
});

describe("диагностика", () => {
  it("ставит предупреждение на неразрешённую ссылку с правильным диапазоном", () => {
    const text = scenario.replace("hello_form", "missing_form");
    const collection = new vscodeMock.DiagnosticCollection();
    refreshDiagnostics(workspace, collection as never, doc(scenarioUri, text));

    const items = collection.entries.get(scenarioUri) as vscodeMock.Diagnostic[];
    // Две проблемы, а не одна: неизвестная форма не резолвится сама и лишает
    // смысла обращение к её полю в Jinja.
    expect(items.map((i) => i.message)).toEqual([
      "Не удаётся разрешить form 'missing_form'",
      "Не удаётся разрешить поле 'name' формы 'missing_form'",
    ]);
    expect(items.every((i) => i.severity === vscodeMock.DiagnosticSeverity.Warning)).toBe(true);

    const rangeText = (item: vscodeMock.Diagnostic) =>
      lineOf(text, item.range.start.line).slice(
        item.range.start.character,
        item.range.end.character,
      );
    // Ссылка подчёркивается вместе с кавычками, поле — только именем.
    expect(items.map(rangeText)).toEqual(['"missing_form"', "name"]);
  });

  it("не трогает файлы вне static/references", () => {
    const collection = new vscodeMock.DiagnosticCollection();
    const outsideUri = "file:///p/other/main.json";
    refreshDiagnostics(workspace, collection as never, doc(outsideUri, scenario));
    expect(collection.entries.has(outsideUri)).toBe(false);
  });
});
