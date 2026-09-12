import * as vscode from "vscode";

/**
 * Перевод смещений ядра в позиции VS Code.
 *
 * Ядро работает в смещениях, потому что так же устроен PSI в IDEA и так проще
 * сравнивать поведение двух реализаций. Для файлов, не открытых в редакторе,
 * `TextDocument` недоступен, поэтому позиция считается по тексту из хранилища —
 * без асинхронного открытия документа на каждый переход к определению.
 */

/** Позиция (строка, символ) для смещения в тексте. */
export function offsetToPosition(text: string, offset: number): vscode.Position {
  const clamped = Math.max(0, Math.min(offset, text.length));
  let line = 0;
  let lineStart = 0;

  for (let i = 0; i < clamped; i++) {
    if (text.charCodeAt(i) !== 10 /* \n */) continue;
    line++;
    lineStart = i + 1;
  }
  return new vscode.Position(line, clamped - lineStart);
}

/**
 * Смещение в тексте для позиции — обратное [offsetToPosition]. Символ за концом
 * строки прижимается к её концу: позиция не должна перетекать на следующую.
 */
export function positionToOffset(text: string, position: vscode.Position): number {
  let lineStart = 0;
  for (let line = 0; line < position.line; line++) {
    const newline = text.indexOf("\n", lineStart);
    if (newline < 0) return text.length;
    lineStart = newline + 1;
  }
  const newline = text.indexOf("\n", lineStart);
  const lineEnd = newline < 0 ? text.length : newline;
  return Math.min(lineStart + position.character, lineEnd);
}

export function rangeOf(text: string, start: number, end: number): vscode.Range {
  return new vscode.Range(offsetToPosition(text, start), offsetToPosition(text, end));
}
