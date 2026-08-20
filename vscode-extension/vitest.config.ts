import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";

/**
 * Адаптерные тесты импортируют `src/vscode/**`, которые тянут модуль `vscode` —
 * он существует только внутри редактора. Подменяем его фейком: так уровень
 * адаптера (offset→Position, Uri, WorkspaceEdit) проверяется без запуска VS Code.
 */
export default defineConfig({
  test: {
    // Интеграционный тест исполняется внутри VS Code (`npm run test:integration`),
    // здесь он бы упал на импорте настоящего модуля `vscode`.
    include: ["test/core/**/*.test.ts", "test/vscode/**/*.test.ts", "test/conformance/**/*.test.ts"],
    environment: "node",
  },
  resolve: {
    alias: {
      vscode: fileURLToPath(new URL("./test/mocks/vscode.ts", import.meta.url)),
    },
  },
});
