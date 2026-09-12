import { describe, expect, it } from "vitest";
import { classKeywordUsages, classKeywordUsagesAtCaret } from "../../src/core/classUsages";
import { SmartAppIndex } from "../../src/core/index";

/**
 * Поиск использований класса Python в ядре: какие вхождения DSL получает класс,
 * объявленный в заданном диапазоне файла.
 *
 * Таблица входов та же, что у `SmartAppCustomKeywordNavigationTest` плагина IDEA:
 * класс по объявлению, категории регистрации, одно слово в двух категориях,
 * одноимённые функция и переменная, класс из каталога-зависимости.
 */

const APP = "file:///w/app";

const CONFIG = "from app.resources.custom import AppResources\nRESOURCES = AppResources\n";

const RESOURCES = [
  "from smart_kit.resources import SmartAppResources",
  "",
  "class AppResources(SmartAppResources):",
  "    def init_actions(self):",
  '        actions["custom_action"] = CustomAction',
  '        actions["shared"] = SharedAction',
  '        actions["dual"] = Dual',
  "",
  "    def init_requirements(self):",
  '        requirements["shared"] = SharedRequirement',
  '        requirements["dual"] = Dual',
  "",
].join("\n");

const DSL: Record<string, string> = {
  [`${APP}/static/references/actions/actions.json`]:
    '{ "a": { "type": "custom_action" }, "b": { "type": "shared" }, "d": { "type": "dual" } }',
  [`${APP}/static/references/scenarios/main.json`]:
    '{ "s": { "actions": [ { "type": "custom_action" } ], "requirement": { "type": "shared" } },' +
    ' "t": { "requirement": { "type": "dual" } } }',
};

const indexWith = (ready = true): SmartAppIndex => {
  const index = new SmartAppIndex();
  index.upsert(`${APP}/app_config.py`, CONFIG);
  index.upsert(`${APP}/app/resources/custom.py`, RESOURCES);
  for (const [uri, text] of Object.entries(DSL)) index.upsert(uri, text);
  if (ready) index.markReady();
  return index;
};

/** Вхождения как «файл: текст», в стабильном порядке. */
const describeUsages = (locations: readonly { uri: string; start: number; end: number }[]) =>
  locations
    .map(({ uri, start, end }) => `${uri.split("/").pop()}: ${(DSL[uri] as string).slice(start, end)}`)
    .sort();

/** Вхождения для объявления [text] целиком в файле [path] приложения. */
const usagesOfDeclaration = (index: SmartAppIndex, path: string, text: string) =>
  describeUsages(classKeywordUsages(index, `${APP}/${path}`, text, 0, text.length));

const classFile = (name: string) => `class ${name}(Action):\n    pass\n`;

describe("поиск использований класса в Python", () => {
  it("находит вхождения слова, под которым класс зарегистрирован", () => {
    expect(usagesOfDeclaration(indexWith(), "app/entities/actions.py", classFile("CustomAction"))).toEqual([
      "actions.json: custom_action",
      "main.json: custom_action",
    ]);
  });

  it("соблюдает категорию регистрации: одно слово у двух классов не смешивается", () => {
    const index = indexWith();
    expect(usagesOfDeclaration(index, "app/entities/a.py", classFile("SharedAction"))).toEqual([
      "actions.json: shared",
    ]);
    expect(usagesOfDeclaration(index, "app/entities/r.py", classFile("SharedRequirement"))).toEqual([
      "main.json: shared",
    ]);
  });

  it("слово, зарегистрированное в двух категориях, ищется в обеих", () => {
    // Возьми поиск только первую категорию регистрации — пропало бы одно из
    // двух вхождений.
    expect(usagesOfDeclaration(indexWith(), "app/entities/dual.py", classFile("Dual"))).toEqual([
      "actions.json: dual",
      "main.json: dual",
    ]);
  });

  it("одноимённая функция классом не является", () => {
    expect(
      usagesOfDeclaration(indexWith(), "app/entities/helpers.py", "def CustomAction():\n    pass\n"),
    ).toEqual([]);
  });

  it("одноимённая переменная классом не является", () => {
    expect(usagesOfDeclaration(indexWith(), "app/entities/consts.py", "CustomAction = 1\n")).toEqual([]);
  });

  it("класс из каталога-зависимости приложению не принадлежит", () => {
    expect(
      usagesOfDeclaration(indexWith(), "venv/lib/python3.12/site-packages/vendor/actions.py", classFile("CustomAction")),
    ).toEqual([]);
  });

  it("объявление вне Python-модуля вхождений не даёт", () => {
    // Словарь приложения собирается только из модулей `.py`: текст, похожий на
    // объявление класса, в файле другого вида кодом приложения не является.
    expect(usagesOfDeclaration(indexWith(), "app/entities/actions.txt", classFile("CustomAction"))).toEqual([]);
  });

  it("файл вне приложения вхождений не получает", () => {
    const text = classFile("CustomAction");
    expect(classKeywordUsages(indexWith(), "file:///elsewhere/actions.py", text, 0, text.length)).toEqual([]);
  });

  it("класс не верхнего уровня модуля не распознаётся", () => {
    // Сканер записывает только классы модуля: вложенный в класс, функцию или
    // `if` ему не виден, и поиск от такого объявления ничего не даёт.
    for (const text of [
      "class Outer:\n    class CustomAction(Action):\n        pass\n",
      "def build():\n    class CustomAction(Action):\n        pass\n",
      "if FLAG:\n    class CustomAction(Action):\n        pass\n",
    ]) {
      const start = text.indexOf("class CustomAction");
      const found = classKeywordUsages(indexWith(), `${APP}/app/entities/nested.py`, text, start, text.length);
      expect(describeUsages(found), text).toEqual([]);
    }
  });

  it("до готовности индекса и во время сканирования Python молчит", () => {
    const text = classFile("CustomAction");
    const uri = `${APP}/app/entities/actions.py`;
    expect(classKeywordUsages(indexWith(false), uri, text, 0, text.length)).toEqual([]);

    const scanning = indexWith();
    scanning.beginPythonScan();
    expect(classKeywordUsages(scanning, uri, text, 0, text.length)).toEqual([]);
  });
});

describe("поиск использований класса с каретки", () => {
  const text = "from x import y\n\nclass CustomAction(Action):\n    pass\n";
  const uri = `${APP}/app/entities/actions.py`;
  const nameStart = text.indexOf("CustomAction");

  it("находит с любой позиции внутри имени и сразу за ним", () => {
    const index = indexWith();
    for (const offset of [nameStart, nameStart + 5, nameStart + "CustomAction".length]) {
      expect(describeUsages(classKeywordUsagesAtCaret(index, uri, text, offset))).toEqual([
        "actions.json: custom_action",
        "main.json: custom_action",
      ]);
    }
  });

  it("вне идентификатора не находит ничего", () => {
    expect(classKeywordUsagesAtCaret(indexWith(), uri, text, text.indexOf("(") + 1)).toEqual([]);
  });

  it("на ключевом слове class объявления нет", () => {
    expect(classKeywordUsagesAtCaret(indexWith(), uri, text, text.indexOf("class") + 1)).toEqual([]);
  });
});
