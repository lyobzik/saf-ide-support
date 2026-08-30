#!/usr/bin/env node
/**
 * Упаковка расширения в `.vsix` и проверка состава пакета.
 *
 * Артефакт кладётся в общий каталог `dist/` рядом с zip-сборкой плагина IDEA:
 * у релиза две стороны, и искать их в одном месте удобнее, чем в каталогах двух
 * разных систем сборки.
 *
 * Состав пакета проверяется здесь же. `.vscodeignore` — обычный список
 * исключений: опечатка в нём не ломает сборку, а молча кладёт в пакет исходники
 * и тесты либо, наоборот, выбрасывает бандл — и расширение не активируется уже
 * у пользователя. Поэтому список файлов сверяется с ожиданиями.
 *
 * Пакет собирается в подкаталог `dist/.staging/` и переносится в `dist/` только
 * после успешной проверки: файл в `dist/` — это то, что ставят и публикуют, и
 * непроверенного (или устаревшего) артефакта там оказаться не должно.
 */
import { execFileSync } from "node:child_process";
import { mkdirSync, readFileSync, readdirSync, renameSync, rmSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const extensionRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const distDir = resolve(extensionRoot, "..", "dist");
const stagingDir = join(distDir, ".staging");

const manifest = JSON.parse(readFileSync(join(extensionRoot, "package.json"), "utf8"));
const vsixName = `${manifest.name}-${manifest.version}.vsix`;

/**
 * Точный состав пакета: и лишнего быть не должно, и недостающего.
 *
 * Раньше обязательными были только манифест и бандл, поэтому пакет без
 * `README.md` и `LICENSE` считался нормальным — а это то, что пользователь
 * видит в карточке расширения и по чему судит о лицензии.
 */
const EXPECTED = ["package.json", "README.md", "LICENSE", "out/extension.js"];

/**
 * Почему список точный, а не набор запретов и не маска.
 *
 * Чёрный список уже подвёл: `build/test-results/vitest.xml` не подходил ни под
 * один запрет и уехал в пакет — 80 КБ внутреннего JUnit-отчёта у пользователя.
 * Маска `out/*.js` подвела бы следующей: `build.mjs` не чистит `out/` перед
 * сборкой, поэтому там может лежать файл от прошлой команды — и он бы прошёл.
 */

const vsceBin = join(
  extensionRoot,
  "node_modules",
  ".bin",
  process.platform === "win32" ? "vsce.cmd" : "vsce",
);

const vsce = (args) =>
  execFileSync(vsceBin, args, { cwd: extensionRoot, encoding: "utf8", stdio: ["ignore", "pipe", "inherit"] });

mkdirSync(stagingDir, { recursive: true });
const stagedVsix = join(stagingDir, vsixName);

try {
  // Бандл пересобирает сам vsce через хук `vscode:prepublish`: пакет не должен
  // собираться из того, что осталось в out/ от прошлой команды (там мог лежать
  // тестовый билд).
  process.stdout.write(vsce(["package", "--out", stagedVsix]));

  const files = vsce(["ls"])
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean);

  const missing = EXPECTED.filter((name) => !files.includes(name));
  const unexpected = files.filter((name) => !EXPECTED.includes(name));

  if (missing.length > 0 || unexpected.length > 0) {
    if (missing.length > 0) console.error(`Package is missing required files: ${missing.join(", ")}`);
    if (unexpected.length > 0) console.error(`Package must not contain: ${unexpected.join(", ")}`);
    console.error("Check .vscodeignore in vscode-extension/.");
    // Именно код возврата, а не process.exit: тот завершает процесс немедленно,
    // мимо finally, и незачищенный `.staging` остаётся в dist/.
    process.exitCode = 1;
  } else {
    renameSync(stagedVsix, join(distDir, vsixName));

    // Пакеты прошлых версий убираем после публикации нового и только свои:
    // `dist/` — каталог релиза, но не собственность этой сборки, и удалять
    // здесь чужой `.vsix` не за что. Свои же лишними быть не должны: и
    // локальная установка, и артефакты CI берут файл маской `dist/*.vsix`.
    for (const name of readdirSync(distDir)) {
      const ours = name.startsWith(`${manifest.name}-`) && name.endsWith(".vsix");
      if (ours && name !== vsixName) rmSync(join(distDir, name));
    }

    console.log(`Packaged ${files.length} files into ${join(distDir, vsixName)}`);
  }
} finally {
  rmSync(stagingDir, { recursive: true, force: true });
}
