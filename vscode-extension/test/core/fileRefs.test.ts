import { describe, expect, it } from "vitest";
import { SmartAppIndex } from "../../src/core/index";
import { isOfferablePath } from "../../src/core/fileRefRules";
import { fileRefRules } from "../../src/core/contract";
import { referencesRoot } from "../../src/core/files";

/**
 * Источник вариантов для файловых ссылок: реестр не-DSL файлов набора.
 *
 * Многокаталожный случай проверяется прямым вызовом `filesInDirs`: в контракте
 * каталог сегодня один, а подделывать общие данные ради теста нельзя.
 */

const rootA = "file:///a/static/references";
const rootB = "file:///b/static/references";

/** Ключи реестра — пути без схемы; координаты берём у самого контракта. */
const scope = (uri: string): string => referencesRoot(`${uri}/forms/forms.json`) as string;
const scopeA = scope(rootA);
const scopeB = scope(rootB);

const indexWith = (...uris: string[]): SmartAppIndex => {
  const index = new SmartAppIndex();
  for (const uri of uris) index.upsert(uri, "");
  index.markReady();
  return index;
};

describe("файлы каталогов правила", () => {
  it("отдаёт пути относительно каталога поиска, включая вложенные", () => {
    const index = indexWith(
      `${rootA}/templates/items.jinja2`,
      `${rootA}/templates/nested/deep.jinja2`,
    );
    expect(index.filesInDirs(scopeA, ["templates"]).sort()).toEqual([
      "items.jinja2",
      "nested/deep.jinja2",
    ]);
  });

  it("объединяет каталоги и схлопывает одинаковый путь", () => {
    const index = indexWith(
      `${rootA}/templates/shared.jinja2`,
      `${rootA}/templates/items.jinja2`,
      `${rootA}/partials/shared.jinja2`,
      `${rootA}/partials/only_partials.jinja2`,
    );
    const paths = index.filesInDirs(scopeA, ["templates", "partials"]);
    expect(paths.sort()).toEqual([
      "items.jinja2",
      "only_partials.jinja2",
      "shared.jinja2",
    ]);
    expect(paths.filter((path) => path === "shared.jinja2")).toHaveLength(1);
  });

  it("каждый предложенный путь резолвится в одном из каталогов", () => {
    // Инвариант вместо «побеждает первый каталог»: приоритета у пути нет —
    // каталог в значение не входит, его выбирает резолв.
    const index = indexWith(
      `${rootA}/templates/shared.jinja2`,
      `${rootA}/partials/only_partials.jinja2`,
    );
    const dirs = ["templates", "partials"];
    for (const path of index.filesInDirs(scopeA, dirs)) {
      expect(index.findFile(dirs.map((dir) => `${scopeA}/${dir}/${path}`))).toBeDefined();
    }
  });

  it("не выходит за пределы своего набора references", () => {
    const index = indexWith(`${rootA}/templates/items.jinja2`, `${rootB}/templates/only_b.jinja2`);
    expect(index.filesInDirs(scopeA, ["templates"])).toEqual(["items.jinja2"]);
    expect(index.filesInDirs(scopeB, ["templates"])).toEqual(["only_b.jinja2"]);
  });

  it("отсутствующий каталог даёт пустой список", () => {
    const index = indexWith(`${rootA}/templates/items.jinja2`);
    expect(index.filesInDirs(scopeA, ["absent"])).toEqual([]);
  });
});

describe("предикат JSON-safe путей", () => {
  // Та же таблица входов, что в SmartAppFileCompletionTest на стороне IDEA:
  // предикат обязан совпадать посимвольно, иначе один редактор предложит имя,
  // а другой нет.
  it.each([
    ["items.jinja2", true],
    ["nested/deep.jinja2", true],
    ["привет.jinja2", true],
    ['q".jinja2', false],
    ["back\\slash.jinja2", false],
    ["bell\u0007.jinja2", false],
  ])("%s -> %s", (path, offerable) => {
    expect(isOfferablePath(path as string)).toBe(offerable);
  });
});

describe("сторож многокаталожного правила", () => {
  it("у каждого правила ровно один каталог поиска", () => {
    // Пока каталог один, «передаёт все searchDirs» и «передаёт первый» дают
    // одинаковый результат, и сквозной тест был бы зелёным при обеих
    // реализациях. Появится второй каталог — этот тест покраснеет и потребует
    // добавить сквозной кейс корпуса вместе с ним.
    for (const rule of fileRefRules) {
      expect(rule.searchDirs).toHaveLength(1);
    }
  });
});
