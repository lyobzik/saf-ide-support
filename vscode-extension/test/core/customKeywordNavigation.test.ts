import { describe, expect, it } from "vitest";
import { SmartAppIndex } from "../../src/core/index";
import { definitionsAt, documentContext, referencesAt } from "../../src/core/semantics";
import { renameLookupAt } from "../../src/core/rename";

/**
 * Навигация и поиск использований для слов, зарегистрированных приложением:
 * переход со значения `type` на строку регистрации в Python и обратный список
 * вхождений по приложению.
 */

const APP = "file:///w/app";
const NESTED = "file:///w/app/subapp";

const CONFIG = "from app.resources.custom import AppResources\nRESOURCES = AppResources\n";

/**
 * Одно и то же имя в двух реестрах: слово `shared` зарегистрировано и как
 * action, и как requirement — на нём проверяется, что категория позиции
 * выбирает регистрацию.
 */
const RESOURCES = [
  "from smart_kit.resources import SmartAppResources",
  "",
  "class AppResources(SmartAppResources):",
  "    def init_actions(self):",
  '        actions["custom_action"] = CustomAction',
  '        actions["shared"] = SharedAction',
  "",
  "    def init_requirements(self):",
  '        requirements["shared"] = SharedRequirement',
  "",
].join("\n");

const RESOURCES_URI = `${APP}/app/resources/custom.py`;

/** Сырой диапазон имени внутри Python-литерала — ожидание для позиции цели. */
const nameRange = (name: string): { start: number; end: number } => {
  const start = RESOURCES.indexOf(`"${name}"`) + 1;
  return { start, end: start + name.length };
};

const ACTIONS_URI = `${APP}/static/references/actions/actions.json`;
const ACTIONS_JSON = '{ "a": { "type": "custom_action" }, "b": { "type": "shared" } }';

const indexWith = (files: Record<string, string>): SmartAppIndex => {
  const index = new SmartAppIndex();
  index.upsert(`${APP}/app_config.py`, CONFIG);
  index.upsert(RESOURCES_URI, RESOURCES);
  index.upsert(ACTIONS_URI, ACTIONS_JSON);
  for (const [uri, text] of Object.entries(files)) index.upsert(uri, text);
  index.markReady();
  return index;
};

const contextOf = (uri: string, text: string) => documentContext(uri, text);

/** Смещение внутри значения — каретка ставится на первый символ имени. */
const caretAt = (text: string, name: string): number => text.indexOf(`"${name}"`) + 1;

describe("переход со значения type на регистрацию", () => {
  it("ведёт на строку регистрации в Python-файле приложения", () => {
    const index = indexWith({});
    const found = definitionsAt(
      index,
      contextOf(ACTIONS_URI, ACTIONS_JSON),
      caretAt(ACTIONS_JSON, "custom_action"),
    );

    expect(found).toEqual([
      {
        target: "registration",
        uri: RESOURCES_URI,
        ...nameRange("custom_action"),
        category: "action",
        name: "custom_action",
      },
    ]);
  });

  it("категория позиции выбирает регистрацию среди одноимённых", () => {
    const index = indexWith({});
    // Файл действий: категория позиции — action, значит цель одна, из init_actions.
    const found = definitionsAt(
      index,
      contextOf(ACTIONS_URI, ACTIONS_JSON),
      caretAt(ACTIONS_JSON, "shared"),
    );
    expect(found.map((target) => ("category" in target ? target.category : undefined))).toEqual([
      "action",
    ]);

    // Тот же `shared` в позиции requirement сценария ведёт в init_requirements.
    const scenarioUri = `${APP}/static/references/scenarios/s.json`;
    const scenarioJson = '{ "s": { "requirement": { "type": "shared" } } }';
    index.upsert(scenarioUri, scenarioJson);
    const inScenario = definitionsAt(
      index,
      contextOf(scenarioUri, scenarioJson),
      caretAt(scenarioJson, "shared"),
    );
    expect(inScenario.map((target) => ("category" in target ? target.category : undefined))).toEqual(
      ["requirement"],
    );
  });

  it("слово фреймворка целей не даёт", () => {
    const json = '{ "a": { "type": "sdk_answer" } }';
    const index = indexWith({ [ACTIONS_URI]: json });
    expect(definitionsAt(index, contextOf(ACTIONS_URI, json), caretAt(json, "sdk_answer"))).toEqual(
      [],
    );
  });

  it("во время сканирования Python целей нет", () => {
    const index = indexWith({});
    index.beginPythonScan();
    expect(
      definitionsAt(index, contextOf(ACTIONS_URI, ACTIONS_JSON), caretAt(ACTIONS_JSON, "custom_action")),
    ).toEqual([]);
    index.endPythonScan();
  });
});

describe("использования ключевого слова приложения", () => {
  const SECOND_URI = `${APP}/static/references/scenarios/s.json`;
  const SECOND_JSON = '{ "s": { "actions": [ { "type": "custom_action" } ] } }';
  const NESTED_URI = `${NESTED}/static/references/actions/actions.json`;
  const NESTED_JSON = '{ "n": { "type": "custom_action" } }';
  // Зависимость, вендоренная внутрь самого набора: приложение у файла то же,
  // и отсекает его только список исключённых каталогов.
  const VENDOR_URI = `${APP}/static/references/actions/venv/vendored.json`;
  const VENDOR_JSON = '{ "v": { "type": "custom_action" } }';
  // Набор внутри venv рядом с приложением: у него свой корень приложения,
  // поэтому его отсекает правило владения, а не список каталогов.
  const OUTSIDE_URI = `${APP}/venv/lib/pkg/static/references/actions/actions.json`;
  const OUTSIDE_JSON = '{ "o": { "type": "custom_action" } }';

  const withNeighbours = (): SmartAppIndex =>
    indexWith({
      [SECOND_URI]: SECOND_JSON,
      [NESTED_URI]: NESTED_JSON,
      [`${NESTED}/app_config.py`]: CONFIG,
      [VENDOR_URI]: VENDOR_JSON,
      [OUTSIDE_URI]: OUTSIDE_JSON,
    });

  it("собираются по всему приложению, включая другие виды файлов", () => {
    const index = withNeighbours();
    const usages = referencesAt(
      index,
      contextOf(ACTIONS_URI, ACTIONS_JSON),
      caretAt(ACTIONS_JSON, "custom_action"),
      false,
    );

    expect(usages).toEqual([
      { uri: ACTIONS_URI, start: caretAt(ACTIONS_JSON, "custom_action"), end: caretAt(ACTIONS_JSON, "custom_action") + "custom_action".length },
      { uri: SECOND_URI, start: caretAt(SECOND_JSON, "custom_action"), end: caretAt(SECOND_JSON, "custom_action") + "custom_action".length },
    ]);
  });

  it("файл вложенного приложения и каталоги зависимостей во вхождения не попадают", () => {
    const index = withNeighbours();
    const uris = referencesAt(
      index,
      contextOf(ACTIONS_URI, ACTIONS_JSON),
      caretAt(ACTIONS_JSON, "custom_action"),
      false,
    ).map((usage) => usage.uri);

    expect(uris).not.toContain(NESTED_URI);
    expect(uris).not.toContain(VENDOR_URI);
    expect(uris).not.toContain(OUTSIDE_URI);
  });

  it("одноимённое слово другой категории использованием не считается", () => {
    const scenarioUri = `${APP}/static/references/scenarios/s.json`;
    const scenarioJson = '{ "s": { "requirement": { "type": "shared" } } }';
    const index = indexWith({ [scenarioUri]: scenarioJson });

    // Каретка на `shared` в файле действий: вхождение в позиции requirement —
    // другое слово, у него своя регистрация.
    const usages = referencesAt(
      index,
      contextOf(ACTIONS_URI, ACTIONS_JSON),
      caretAt(ACTIONS_JSON, "shared"),
      false,
    );
    expect(usages.map((usage) => usage.uri)).toEqual([ACTIONS_URI]);
  });

  it("includeDeclaration добавляет саму строку регистрации", () => {
    const index = indexWith({});
    const withDeclaration = referencesAt(
      index,
      contextOf(ACTIONS_URI, ACTIONS_JSON),
      caretAt(ACTIONS_JSON, "custom_action"),
      true,
    );

    expect(withDeclaration).toContainEqual({
      target: "registration",
      uri: RESOURCES_URI,
      ...nameRange("custom_action"),
      category: "action",
      name: "custom_action",
    });
  });
});

describe("переименование ключевого слова приложения", () => {
  it("отклоняется с объяснением", () => {
    const index = indexWith({});
    const lookup = renameLookupAt(
      index,
      contextOf(ACTIONS_URI, ACTIONS_JSON),
      caretAt(ACTIONS_JSON, "custom_action"),
    );

    expect(lookup.target).toBeUndefined();
    expect(lookup.reason).toContain("переименование не поддерживается");
  });

  it("слово фреймворка в той же позиции причины не даёт", () => {
    const json = '{ "a": { "type": "sdk_answer" } }';
    const index = indexWith({ [ACTIONS_URI]: json });
    expect(renameLookupAt(index, contextOf(ACTIONS_URI, json), caretAt(json, "sdk_answer"))).toEqual(
      {},
    );
  });
});
