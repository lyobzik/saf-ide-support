import { defineConfig } from "@vscode/test-cli";

/**
 * Интеграционный прогон в настоящем VS Code: smoke по одному кейсу на провайдер.
 * Полный корпус гоняется на ядре и адаптере (быстро, без редактора), здесь важно
 * поймать ошибки регистрации, активации и легенды токенов.
 */
export default defineConfig({
  files: "out/test/integration.test.js",
  workspaceFolder: "./test/integration/workspace",
  mocha: { ui: "bdd", timeout: 60_000 },
});
