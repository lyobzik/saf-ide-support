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

const labelOf = (item: vscode.CompletionItem): string =>
  typeof item.label === "string" ? item.label : item.label.label;

/**
 * Ждёт, пока предложены **все** ожидаемые пары «метка + вид».
 *
 * Проверять одну метку нельзя: слова из документа (`name`, `main_form`,
 * `hello_form`) VS Code предлагает и сам — word-based suggestions включаются,
 * когда провайдеры молчат, поэтому такой тест прошёл бы и с выключенным
 * провайдером. Вид варианта задаём только мы.
 *
 * Ждать надо все пары сразу: индекс наполняется файл за файлом, и ожидание
 * первого варианта завершилось бы, пока второго ещё нет, — тест падал бы
 * случайно. По этой же причине здесь не проверяется просто непустой список.
 */
async function waitForCompletion(
  document: vscode.TextDocument,
  needle: string,
  offsetInNeedle: number,
  expected: ReadonlyArray<readonly [string, vscode.CompletionItemKind]>,
): Promise<vscode.CompletionItem[]> {
  let items: vscode.CompletionItem[] = [];
  for (let attempt = 0; attempt < 40; attempt++) {
    const list = await vscode.commands.executeCommand<vscode.CompletionList>(
      "vscode.executeCompletionItemProvider",
      document.uri,
      positionOf(document, needle, offsetInNeedle),
    );
    items = list?.items ?? [];
    const missing = expected.filter(
      ([label, kind]) => !items.some((item) => labelOf(item) === label && item.kind === kind),
    );
    if (missing.length === 0) return items;
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  const offered = items.map((item) => `${labelOf(item)}:${item.kind}`).join(", ");
  const wanted = expected.map(([label, kind]) => `${label}:${kind}`).join(", ");
  assert.fail(`не дождались вариантов [${wanted}] в '${needle}'; предложено: [${offered}]`);
}

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

  it("находит строки DSL от класса в Python-файле", async () => {
    // Python-расширения в тестовом VS Code нет, поэтому здесь проверяется
    // запасной путь — каретка на имени в самом `class X`. Путь через объявление
    // символа закрывают тесты адаптера с подменённой командой; здесь важно, что
    // провайдер вообще зарегистрирован для `.py`.
    const document = await openWorkspaceFile("app/basic_entities/actions.py");
    assert.equal(document.languageId, "python", "VS Code не распознал .py как python");
    const locations = await waitFor(
      () =>
        vscode.commands.executeCommand<vscode.Location[]>(
          "vscode.executeReferenceProvider",
          document.uri,
          positionOf(document, "CustomAction", 2),
        ),
      (found) => (found ?? []).some((location) => location.uri.path.endsWith("actions/actions.json")),
    );
    assert.ok(locations.some((location) => location.uri.path.endsWith("actions/actions.json")));
  });

  it("дополняет ссылки другого провайдера для python, а не заменяет их", async () => {
    // Роль Python-расширения играют провайдеры, зарегистрированные самим тестом:
    // настоящего Pylance в тестовом VS Code нет. Здесь видно то, чего не видят
    // адаптерные тесты: склеивает ли VS Code ответы провайдеров и доходит ли
    // `executeDefinitionProvider` из нашего провайдера до чужого.
    const entities = await openWorkspaceFile("app/basic_entities/actions.py");
    const nameStart = positionOf(entities, "CustomAction");
    const resources = await openWorkspaceFile("app/resources/custom_app_resources.py");
    const caret = positionOf(resources, "= CustomAction", 4);
    const selector: vscode.DocumentSelector = { language: "python", scheme: "file" };
    const foreignReference = new vscode.Location(resources.uri, caret);
    const registrations = [
      vscode.languages.registerDefinitionProvider(selector, {
        provideDefinition: () => [
          new vscode.Location(
            entities.uri,
            new vscode.Range(nameStart, nameStart.translate(0, "CustomAction".length)),
          ),
        ],
      }),
      vscode.languages.registerReferenceProvider(selector, {
        provideReferences: () => [foreignReference],
      }),
    ];
    try {
      // Каретка на строке регистрации: запасной путь по каретке здесь ничего не
      // нашёл бы, так что строка DSL в ответе означает, что сработало объявление.
      const locations = await waitFor(
        () =>
          vscode.commands.executeCommand<vscode.Location[]>(
            "vscode.executeReferenceProvider",
            resources.uri,
            caret,
          ),
        (found) => (found ?? []).some((location) => location.uri.path.endsWith("actions/actions.json")),
      );
      assert.ok(
        locations.some((location) => location.uri.path.endsWith("custom_app_resources.py")),
        `ссылка другого провайдера пропала из ответа: ${locations.map((l) => l.uri.path).join(", ")}`,
      );
    } finally {
      for (const registration of registrations) registration.dispose();
    }
  });

  it("предлагает имена форм в позиции form", async () => {
    // hello_form есть и в тексте документа, поэтому ждём именно наш вариант:
    // ссылку на сущность, а не совпавшее слово.
    await waitForCompletion(await openWorkspaceFile(scenarioPath), '"form": "hello_form"', 9, [
      ["hello_form", vscode.CompletionItemKind.Reference],
    ]);
  });

  it("предлагает поля и переменную формы внутри Jinja", async () => {
    // Обе жалобы на «No suggestions» пришли из живого редактора, а smoke-кейсов
    // на Jinja-completion не было вовсе: корпус проверяет ядро, а не провайдер.
    // Слова name и main_form стоят в самом документе, поэтому вид варианта здесь
    // не украшение, а единственное отличие нашего предложения от word-based.
    const document = await openWorkspaceFile(scenarioPath);

    await waitForCompletion(document, '"{% if main_form. %}"', '"{% if main_form.'.length, [
      ["name", vscode.CompletionItemKind.Field],
    ]);
    await waitForCompletion(document, '"{% if  %}"', '"{% if '.length, [
      ["main_form", vscode.CompletionItemKind.Variable],
    ]);
  });

  it("предлагает имена файлов шаблонов в позиции file", async () => {
    // До этой ветки VS Code показывал здесь word-based suggestions — слова из
    // документа. Проверяем в живом редакторе: варианты наши.
    // Обе пары ждём вместе: реестр наполняется файл за файлом, и ожидание одного
    // items.jinja2 завершилось бы раньше, чем в него попадёт other.jinja2.
    await waitForCompletion(await openWorkspaceFile(formsPath), '"file": ""', 9, [
      ["items.jinja2", vscode.CompletionItemKind.File],
      ["other.jinja2", vscode.CompletionItemKind.File],
    ]);
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
