import * as assert from "node:assert/strict";
import * as vscode from "vscode";

/**
 * Smoke-прогон в настоящем VS Code: по одному кейсу на провайдер.
 *
 * Полное поведение проверяют conformance-корпус (на ядре) и адаптерные тесты
 * (на фейковом `vscode`) — они быстрые и не требуют редактора. Здесь ловится то,
 * что видно только в живом VS Code: регистрация провайдеров, активация
 * расширения, соответствие легенды семантических токенов и работа команд.
 */

const scenarioPath = "static/references/scenarios/main.json";
const formsPath = "static/references/forms/forms.json";

async function openWorkspaceFile(relativePath: string): Promise<vscode.TextDocument> {
  const [folder] = vscode.workspace.workspaceFolders ?? [];
  assert.ok(folder, "тестовый воркспейс не открыт");
  const uri = vscode.Uri.joinPath(folder.uri, ...relativePath.split("/"));
  const document = await vscode.workspace.openTextDocument(uri);
  await vscode.window.showTextDocument(document);
  return document;
}

/** Ждёт условия: индексация воркспейса асинхронна. */
async function waitFor<T>(probe: () => PromiseLike<T> | T, ok: (value: T) => boolean): Promise<T> {
  for (let attempt = 0; attempt < 40; attempt++) {
    const value = await probe();
    if (ok(value)) return value;
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw new Error("условие не выполнилось за отведённое время");
}

const positionOf = (document: vscode.TextDocument, needle: string, offsetInNeedle = 0) =>
  document.positionAt(document.getText().indexOf(needle) + offsetInNeedle);

describe("SmartApp DSL в VS Code", () => {
  before(async () => {
    const extension = vscode.extensions.getExtension("ru-sber-smartapp.smartapp-dsl");
    assert.ok(extension, "расширение не найдено в списке установленных");

    // Активацию не вызываем вручную: проверяем, что её выполняет сам VS Code по
    // `onLanguage:json`. Ручной `activate()` маскировал бы ошибку в манифесте —
    // например, потерянное событие активации.
    assert.equal(
      extension.isActive,
      false,
      "расширение не должно быть активно до открытия JSON-файла",
    );
    await openWorkspaceFile(scenarioPath);
    await waitFor(
      () => extension.isActive,
      (active) => active,
    );
  });

  it("переходит к определению формы", async () => {
    const document = await openWorkspaceFile(scenarioPath);
    const locations = await waitFor(
      () =>
        vscode.commands.executeCommand<vscode.Location[]>(
          "vscode.executeDefinitionProvider",
          document.uri,
          positionOf(document, '"hello_form"', 2),
        ),
      (found) => (found?.length ?? 0) > 0,
    );
    assert.ok(locations[0]?.uri.path.endsWith("forms/forms.json"));
  });

  it("переходит к определению поля формы из Jinja", async () => {
    const document = await openWorkspaceFile(scenarioPath);
    const locations = await waitFor(
      () =>
        vscode.commands.executeCommand<vscode.Location[]>(
          "vscode.executeDefinitionProvider",
          document.uri,
          positionOf(document, "main_form.name", 11),
        ),
      (found) => (found?.length ?? 0) > 0,
    );
    assert.ok(locations[0]?.uri.path.endsWith("forms/forms.json"));
  });

  it("переходит с main_form к определению формы", async () => {
    const document = await openWorkspaceFile(scenarioPath);
    const locations = await waitFor(
      () =>
        vscode.commands.executeCommand<vscode.Location[]>(
          "vscode.executeDefinitionProvider",
          document.uri,
          positionOf(document, "main_form.name", 2),
        ),
      (found) => (found?.length ?? 0) > 0,
    );
    assert.ok(locations[0]?.uri.path.endsWith("forms/forms.json"));
  });

  it("переходит к файлу шаблона", async () => {
    // Цель — не JSON-определение, а файл целиком, текст которого расширение не
    // хранит: единственное место, где эта ветка проверяется в живом редакторе.
    const document = await openWorkspaceFile(formsPath);
    const locations = await waitFor(
      () =>
        vscode.commands.executeCommand<vscode.Location[]>(
          "vscode.executeDefinitionProvider",
          document.uri,
          positionOf(document, '"items.jinja2"', 2),
        ),
      (found) => (found?.length ?? 0) > 0,
    );
    assert.ok(locations[0]?.uri.path.endsWith("templates/items.jinja2"));
  });

  it("находит использования определения", async () => {
    const document = await openWorkspaceFile(formsPath);
    const locations = await waitFor(
      () =>
        vscode.commands.executeCommand<vscode.Location[]>(
          "vscode.executeReferenceProvider",
          document.uri,
          positionOf(document, '"hello_form"', 2),
        ),
      (found) => (found?.length ?? 0) > 0,
    );
    assert.ok(locations.some((location) => location.uri.path.endsWith("scenarios/main.json")));
  });

  it("предлагает имена форм в позиции form", async () => {
    const document = await openWorkspaceFile(scenarioPath);
    const list = await waitFor(
      () =>
        vscode.commands.executeCommand<vscode.CompletionList>(
          "vscode.executeCompletionItemProvider",
          document.uri,
          positionOf(document, '"form": "hello_form"', 9),
        ),
      (found) => (found?.items.length ?? 0) > 0,
    );
    const labels = list.items.map((item) =>
      typeof item.label === "string" ? item.label : item.label.label,
    );
    assert.ok(labels.includes("hello_form"), `в списке нет hello_form: ${labels.join(", ")}`);
  });

  it("предлагает имена файлов шаблонов в позиции file", async () => {
    // До этой ветки VS Code показывал здесь word-based suggestions — слова из
    // документа. Проверяем в живом редакторе: варианты наши.
    const document = await openWorkspaceFile(formsPath);
    const list = await waitFor(
      () =>
        vscode.commands.executeCommand<vscode.CompletionList>(
          "vscode.executeCompletionItemProvider",
          document.uri,
          positionOf(document, '"file": ""', 9),
        ),
      (found) => (found?.items.length ?? 0) > 0,
    );
    const labels = list.items.map((item) =>
      typeof item.label === "string" ? item.label : item.label.label,
    );
    assert.ok(labels.includes("items.jinja2"), `в списке нет items.jinja2: ${labels.join(", ")}`);
    assert.ok(labels.includes("other.jinja2"), `в списке нет other.jinja2: ${labels.join(", ")}`);
  });

  it("отдаёт семантические токены по зарегистрированной легенде", async () => {
    const document = await openWorkspaceFile(scenarioPath);
    const tokens = await waitFor(
      () =>
        vscode.commands.executeCommand<vscode.SemanticTokens>(
          "vscode.provideDocumentSemanticTokens",
          document.uri,
        ),
      (found) => (found?.data.length ?? 0) > 0,
    );
    // Пять чисел на токен — формат VS Code; сам факт непустых данных означает,
    // что провайдер зарегистрирован с совместимой легендой.
    assert.equal(tokens.data.length % 5, 0);
  });

  it("показывает предупреждение о неразрешённой ссылке", async () => {
    const document = await openWorkspaceFile(scenarioPath);
    const diagnostics = await waitFor(
      () => vscode.languages.getDiagnostics(document.uri),
      (found) => found.some((item) => item.source === "SmartApp DSL"),
    );
    const ours = diagnostics.filter((item) => item.source === "SmartApp DSL");
    assert.ok(
      ours.some((item) => item.message === "Не удаётся разрешить form 'missing_form'"),
      `нет ожидаемого предупреждения: ${ours.map((d) => d.message).join(" | ")}`,
    );
    assert.equal(ours[0]?.severity, vscode.DiagnosticSeverity.Warning);
  });

  it("переименовывает определение вместе со ссылками", async () => {
    const document = await openWorkspaceFile(formsPath);
    const edit = await waitFor(
      () =>
        vscode.commands.executeCommand<vscode.WorkspaceEdit>(
          "vscode.executeDocumentRenameProvider",
          document.uri,
          positionOf(document, '"hello_form"', 2),
          "greeting_form",
        ),
      (found) => (found?.size ?? 0) > 0,
    );
    const files = edit.entries().map(([uri]) => uri.path);
    assert.ok(files.some((path) => path.endsWith("scenarios/main.json")));
  });
});
