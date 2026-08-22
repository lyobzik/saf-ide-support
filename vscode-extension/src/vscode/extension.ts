import * as vscode from "vscode";
import { isDslFile } from "../core/files";
import {
  SEMANTIC_TOKEN_LEGEND,
  createCompletionProvider,
  createDefinitionProvider,
  createReferenceProvider,
  createRenameProvider,
  createSemanticTokensProvider,
  refreshDiagnostics,
} from "./providers";
import { SmartAppWorkspace } from "./workspace";

/**
 * Точка входа расширения SmartApp DSL.
 *
 * Семантика целиком живёт в `src/core` и проверяется conformance-корпусом
 * вместе с плагином IDEA; здесь — только регистрация провайдеров и связка с
 * жизненным циклом редактора.
 */

/** Расширение работает поверх встроенного JSON, своего языка не вводит. */
const JSON_SELECTOR: vscode.DocumentSelector = [
  { language: "json", scheme: "file" },
  { language: "json", scheme: "untitled" },
];

export async function activate(context: vscode.ExtensionContext): Promise<void> {
  const workspace = new SmartAppWorkspace();
  const diagnostics = vscode.languages.createDiagnosticCollection("smartapp-dsl");

  context.subscriptions.push(
    workspace,
    diagnostics,
    vscode.languages.registerDefinitionProvider(JSON_SELECTOR, createDefinitionProvider(workspace)),
    vscode.languages.registerReferenceProvider(JSON_SELECTOR, createReferenceProvider(workspace)),
    vscode.languages.registerRenameProvider(JSON_SELECTOR, createRenameProvider(workspace)),
    vscode.languages.registerDocumentSemanticTokensProvider(
      JSON_SELECTOR,
      createSemanticTokensProvider(workspace),
      SEMANTIC_TOKEN_LEGEND,
    ),
    vscode.languages.registerCompletionItemProvider(
      JSON_SELECTOR,
      createCompletionProvider(workspace),
      // `"` открывает значение, `.` — доступ к полю формы внутри Jinja.
      '"',
      ".",
    ),
  );

  const refreshOpenDocuments = (): void => {
    for (const document of vscode.workspace.textDocuments) {
      if (isDslFile(document.uri.toString())) refreshDiagnostics(workspace, diagnostics, document);
    }
  };

  context.subscriptions.push(
    // Индекс меняется — пересчитываем предупреждения во всех открытых файлах:
    // добавление определения в одном файле снимает предупреждение в другом.
    workspace.onDidUpdate(refreshOpenDocuments),
    vscode.workspace.onDidOpenTextDocument((document) => {
      if (isDslFile(document.uri.toString())) refreshDiagnostics(workspace, diagnostics, document);
    }),
    // Файл, открытый во время индексации, успел получить пустой набор
    // предупреждений: события открытия для него больше не будет, поэтому
    // пересчитываем и при смене видимых редакторов.
    vscode.window.onDidChangeVisibleTextEditors(refreshOpenDocuments),
    vscode.workspace.onDidCloseTextDocument((document) => diagnostics.delete(document.uri)),
  );

  await workspace.start();
  refreshOpenDocuments();
}

export function deactivate(): void {
  // Всё освобождается через context.subscriptions.
}
