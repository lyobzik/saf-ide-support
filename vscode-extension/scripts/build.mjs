#!/usr/bin/env node
/** Сборка расширения одним бандлом: общий контракт инлайнится в артефакт. */
import * as esbuild from "esbuild";

const watch = process.argv.includes("--watch");

const options = {
  entryPoints: ["src/vscode/extension.ts"],
  bundle: true,
  outfile: "out/extension.js",
  external: ["vscode"],
  format: "cjs",
  platform: "node",
  // jsonc-parser объявляет главным UMD-вариант, который делает require во время
  // инициализации и падает в extension host. Берём ESM-сборку.
  mainFields: ["module", "main"],
  target: "node20",
  sourcemap: true,
  logLevel: "info",
};

if (watch) {
  const ctx = await esbuild.context(options);
  await ctx.watch();
} else {
  await esbuild.build(options);
}
