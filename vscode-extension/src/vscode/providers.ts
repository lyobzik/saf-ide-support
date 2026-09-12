import * as vscode from "vscode";
import { classKeywordUsages, classKeywordUsagesAtCaret } from "../core/classUsages";
import { completionAt, type CompletionKind } from "../core/completion";
import type { Location } from "../core/index";
import { renameEdits, renameLookupAt } from "../core/rename";
import { SemanticTokenType, semanticTokens } from "../core/semanticTokens";
import {
  definitionsAt,
  diagnostics,
  documentContext,
  referencesAt,
  type DocumentContext,
} from "../core/semantics";
import { offsetToPosition, positionToOffset, rangeOf } from "./positions";
import type { SmartAppWorkspace } from "./workspace";

/**
 * Провайдеры VS Code — тонкая оболочка над ядром: вся семантика уже посчитана,
 * здесь остаётся перевод смещений в позиции и сборка объектов редактора.
 */

/** Легенда семантических токенов; порядок обязан совпадать с `contributes`. */
export const SEMANTIC_TOKEN_LEGEND = new vscode.SemanticTokensLegend([
  SemanticTokenType.KEYWORD,
  SemanticTokenType.STRUCTURAL_KEY,
  SemanticTokenType.JINJA_DELIMITER,
  SemanticTokenType.JINJA_VARIABLE,
  SemanticTokenType.JINJA_OPERATOR,
  SemanticTokenType.JINJA_FILTER,
  SemanticTokenType.JINJA_STRING,
]);

const contextOf = (document: vscode.TextDocument): DocumentContext =>
  documentContext(document.uri.toString(), document.getText());

/** Переводит результат ядра в `vscode.Location`, зная текст целевого файла. */
function toVsLocation(workspace: SmartAppWorkspace, location: Location): vscode.Location | undefined {
  const text = workspace.textOf(location.uri);
  const uri = vscode.Uri.parse(location.uri);
  if (text === undefined) {
    // Текст файлов, которые индекс знает только по существованию (шаблоны), не
    // хранится: единственная осмысленная позиция в них — начало файла.
    if (location.start === 0 && location.end === 0) {
      return new vscode.Location(uri, new vscode.Position(0, 0));
    }
    return undefined;
  }
  return new vscode.Location(uri, rangeOf(text, location.start, location.end));
}

const toVsLocations = (workspace: SmartAppWorkspace, locations: readonly Location[]) =>
  locations
    .map((location) => toVsLocation(workspace, location))
    .filter((location): location is vscode.Location => location !== undefined);

export function createDefinitionProvider(
  workspace: SmartAppWorkspace,
): vscode.DefinitionProvider {
  return {
    provideDefinition(document, position) {
      const context = contextOf(document);
      const found = definitionsAt(workspace.index, context, document.offsetAt(position));
      return toVsLocations(workspace, found);
    },
  };
}

export function createReferenceProvider(workspace: SmartAppWorkspace): vscode.ReferenceProvider {
  return {
    provideReferences(document, position, options) {
      const context = contextOf(document);
      const found = referencesAt(
        workspace.index,
        context,
        document.offsetAt(position),
        options.includeDeclaration,
      );
      return toVsLocations(workspace, found);
    },
  };
}

/**
 * Поиск использований класса в Python-файле: к ссылкам, которые находит
 * Python-расширение, добавляются строки DSL со словами, под которыми класс
 * зарегистрирован. Порт `SmartAppKeywordClassUsagesSearcher`: VS Code склеивает
 * ответы всех провайдеров, поэтому этот провайдер дополняет чужой результат, а не
 * заменяет его.
 *
 * Класс ищется так же, как его отдаёт платформа в IDEA, — по объявлению символа
 * под кареткой. Объявление сообщает Python-расширение
 * (`vscode.executeDefinitionProvider`), и поиск работает с любого места, где стоит
 * имя: с `class X`, с импорта, со строки регистрации. Без Python-расширения
 * объявлений нет, и остаётся одна точка — каретка на имени в самом `class X`.
 */
export function createClassReferenceProvider(
  workspace: SmartAppWorkspace,
): vscode.ReferenceProvider {
  return {
    async provideReferences(document, position) {
      // Ключ — файл и начало: одно объявление может прийти несколькими
      // ответами (класс и импорт, `Location` и `LocationLink` от разных
      // провайдеров), а вхождение в списке должно стоять один раз.
      const found = new Map<string, Location>();
      const add = (locations: readonly Location[]): void => {
        for (const location of locations) found.set(`${location.uri}\n${location.start}`, location);
      };

      const declarations = await declarationsAt(document, position);
      for (const declaration of declarations) {
        const text =
          declaration.uri === document.uri.toString()
            ? document.getText()
            : workspace.textOf(declaration.uri);
        if (text === undefined) continue;
        add(
          classKeywordUsages(
            workspace.index,
            declaration.uri,
            text,
            positionToOffset(text, declaration.range.start),
            positionToOffset(text, declaration.range.end),
          ),
        );
      }
      if (declarations.length === 0) {
        add(
          classKeywordUsagesAtCaret(
            workspace.index,
            document.uri.toString(),
            document.getText(),
            document.offsetAt(position),
          ),
        );
      }
      return toVsLocations(workspace, [...found.values()]);
    },
  };
}

/** Объявления символа под кареткой — по данным Python-расширения, если оно есть. */
async function declarationsAt(
  document: vscode.TextDocument,
  position: vscode.Position,
): Promise<{ uri: string; range: vscode.Range }[]> {
  const found = await vscode.commands.executeCommand<
    (vscode.Location | vscode.LocationLink)[] | undefined
  >("vscode.executeDefinitionProvider", document.uri, position);
  return (found ?? []).map((item) =>
    "targetUri" in item
      ? // Берётся объявление целиком: имя класса лежит внутри него, а
        // необязательный `targetSelectionRange` к ответу ничего не добавляет.
        { uri: item.targetUri.toString(), range: item.targetRange }
      : { uri: item.uri.toString(), range: item.range },
  );
}

export function createCompletionProvider(workspace: SmartAppWorkspace): vscode.CompletionItemProvider {
  return {
    provideCompletionItems(document, position) {
      const context = contextOf(document);
      const result = completionAt(workspace.index, context, document.offsetAt(position));
      if (result.items.length === 0) return undefined;

      const range = new vscode.Range(
        document.positionAt(result.replaceStart),
        document.positionAt(result.replaceEnd),
      );
      return result.items.map((item) => {
        const completion = new vscode.CompletionItem(item.label, completionKind(result.kind));
        completion.detail = item.detail;
        completion.range = range;
        // Порядок важнее алфавита: варианты уже отсортированы по смыслу.
        completion.sortText = String(result.items.indexOf(item)).padStart(4, "0");
        return completion;
      });
    },
  };
}

function completionKind(kind: CompletionKind): vscode.CompletionItemKind {
  switch (kind) {
    case "keyword":
      return vscode.CompletionItemKind.Keyword;
    case "field":
      return vscode.CompletionItemKind.Field;
    case "file":
      return vscode.CompletionItemKind.File;
    case "variable":
      return vscode.CompletionItemKind.Variable;
    case "user_field":
      // Тот же вид, что у поля формы: для пользователя это такое же поле, и
      // значок в списке обязан совпадать.
      return vscode.CompletionItemKind.Field;
    default:
      return vscode.CompletionItemKind.Reference;
  }
}

export function createSemanticTokensProvider(
  workspace: SmartAppWorkspace,
): vscode.DocumentSemanticTokensProvider {
  return {
    provideDocumentSemanticTokens(document) {
      const context = contextOf(document);
      const custom = workspace.index.customKeywordsOf(context.scopeRoot);
      const builder = new vscode.SemanticTokensBuilder(SEMANTIC_TOKEN_LEGEND);
      for (const token of semanticTokens(context, custom)) {
        // Токен может пересекать перевод строки только в патологическом JSON;
        // такие пропускаем — VS Code не принимает многострочные токены.
        const start = document.positionAt(token.start);
        const end = document.positionAt(token.end);
        if (start.line !== end.line) continue;
        builder.push(start.line, start.character, end.character - start.character, tokenIndex(token.type));
      }
      return builder.build();
    },
  };
}

function tokenIndex(type: SemanticTokenType): number {
  const index = SEMANTIC_TOKEN_LEGEND.tokenTypes.indexOf(type);
  return index < 0 ? 0 : index;
}

export function createRenameProvider(workspace: SmartAppWorkspace): vscode.RenameProvider {
  return {
    prepareRename(document, position) {
      const context = contextOf(document);
      const lookup = renameLookupAt(workspace.index, context, document.offsetAt(position));
      const target = lookup.target;
      if (target === undefined) {
        // Причина есть не всегда: в обычной позиции переименовывать просто нечего.
        throw new Error(
          lookup.reason ??
            "Здесь нечего переименовывать: это не определение и не ссылка SmartApp DSL",
        );
      }
      return {
        range: new vscode.Range(
          document.positionAt(target.start),
          document.positionAt(target.end),
        ),
        placeholder: target.name,
      };
    },

    provideRenameEdits(document, position, newName) {
      const context = contextOf(document);
      const edits = renameEdits(workspace.index, context, document.offsetAt(position), newName);
      if (edits.length === 0) return undefined;

      const workspaceEdit = new vscode.WorkspaceEdit();
      for (const edit of edits) {
        const text = workspace.textOf(edit.uri);
        if (text === undefined) continue;
        workspaceEdit.replace(
          vscode.Uri.parse(edit.uri),
          rangeOf(text, edit.start, edit.end),
          edit.newText,
        );
      }
      return workspaceEdit;
    },
  };
}

/**
 * Диагностика неразрешённых ссылок и полей. Severity — Warning: целевая
 * сущность может быть ещё не создана, ошибкой это не является.
 */
export function refreshDiagnostics(
  workspace: SmartAppWorkspace,
  collection: vscode.DiagnosticCollection,
  document: vscode.TextDocument,
): void {
  const context = documentContext(document.uri.toString(), document.getText());
  if (context.kind === undefined) {
    collection.delete(document.uri);
    return;
  }
  const items = diagnostics(workspace.index, context).map((item) => {
    const diagnostic = new vscode.Diagnostic(
      new vscode.Range(
        offsetToPosition(context.text, item.start),
        offsetToPosition(context.text, item.end),
      ),
      item.message,
      vscode.DiagnosticSeverity.Warning,
    );
    diagnostic.source = "SmartApp DSL";
    return diagnostic;
  });
  collection.set(document.uri, items);
}
