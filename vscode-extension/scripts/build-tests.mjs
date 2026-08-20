#!/usr/bin/env node
/** Сборка интеграционных тестов: mocha в VS Code запускает готовый CommonJS. */
import * as esbuild from "esbuild";

await esbuild.build({
  entryPoints: ["test/integration/integration.test.ts"],
  bundle: true,
  outfile: "out/test/integration.test.js",
  external: ["vscode", "mocha"],
  format: "cjs",
  platform: "node",
  // jsonc-parser объявляет главным UMD-вариант, который делает require во время
  // инициализации и падает в extension host. Берём ESM-сборку.
  mainFields: ["module", "main"],
  target: "node20",
  sourcemap: true,
  logLevel: "info",
});
