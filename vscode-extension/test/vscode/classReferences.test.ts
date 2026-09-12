import { beforeEach, describe, expect, it } from "vitest";
import * as vscodeMock from "../mocks/vscode";
import { commandsControl } from "../mocks/vscode";
import { SmartAppIndex } from "../../src/core/index";
import { createClassReferenceProvider } from "../../src/vscode/providers";

/**
 * Поиск использований класса в Python-файле — адаптерный уровень.
 *
 * Ядро получает диапазон объявления в смещениях, а объявление приходит из
 * `vscode.executeDefinitionProvider` в строках и символах, иногда `LocationLink`
 * вместо `Location`. Ошибки этого перевода, выбор диапазона ссылки и склейка
 * дублей видны только здесь.
 */

const APP = "file:///p";
const CONFIG_URI = `${APP}/app_config.py`;
const RESOURCES_URI = `${APP}/app/resources/custom_app_resources.py`;
const ENTITIES_URI = `${APP}/app/basic_entities/actions.py`;
const ACTIONS_URI = `${APP}/static/references/actions/actions.json`;
const SCENARIO_URI = `${APP}/static/references/scenarios/main.json`;

const config = [
  "from app.resources.custom_app_resources import CustomAppResources",
  "RESOURCES = CustomAppResources",
  "",
].join("\n");

const resources = [
  "from smart_kit.resources import SmartAppResources",
  "from app.basic_entities.actions import CustomAction",
  "",
  "class CustomAppResources(SmartAppResources):",
  "    def init_actions(self):",
  '        actions["custom_action"] = CustomAction',
  "",
].join("\n");

// Класс не первый в файле и с декоратором: ошибка в переводе строк и столбцов
// не пройдёт незамеченной.
const entities = [
  "from core.basic_models.actions.basic_actions import Action",
  "",
  "",
  "def helper():",
  "    pass",
  "",
  "",
  "@registered",
  "class CustomAction(Action):",
  "    def run(self):",
  "        pass",
  "",
].join("\n");

const actions = ["{", '  "custom": {', '    "type": "custom_action"', "  }", "}"].join("\n");

const scenario = [
  "{",
  '  "main": {',
  '    "actions": [',
  '      { "type": "custom_action" }',
  "    ]",
  "  }",
  "}",
].join("\n");

const texts = new Map<string, string>([
  [CONFIG_URI, config],
  [RESOURCES_URI, resources],
  [ENTITIES_URI, entities],
  [ACTIONS_URI, actions],
  [SCENARIO_URI, scenario],
]);

const index = (() => {
  const built = new SmartAppIndex();
  for (const [uri, text] of texts) built.upsert(uri, text);
  built.markReady();
  return built;
})();

const workspaceWith = (textOf: (uri: string) => string | undefined) => ({ index, textOf }) as never;
const workspace = workspaceWith((uri) => texts.get(uri));

const document = (uri: string) =>
  new vscodeMock.TextDocument(vscodeMock.Uri.parse(uri), texts.get(uri) as string);

/** Диапазон подстроки [needle] в файле [uri] — в строках и символах, как у VS Code. */
const rangeIn = (uri: string, needle: string, length = needle.length) => {
  const doc = document(uri);
  const start = (texts.get(uri) as string).indexOf(needle);
  return new vscodeMock.Range(doc.positionAt(start), doc.positionAt(start + length));
};

const caretIn = (uri: string, needle: string) => rangeIn(uri, needle).start;

/** Результат провайдера как «файл:строка:символ-символ», в стабильном порядке. */
async function referencesFrom(uri: string, needle: string, from = workspace): Promise<string[]> {
  const provider = createClassReferenceProvider(from);
  const found = (await provider.provideReferences(
    document(uri) as never,
    caretIn(uri, needle) as never,
    { includeDeclaration: false },
    undefined as never,
  )) as unknown as vscodeMock.Location[];
  return found
    .map((location) => {
      const file = location.uri.toString().replace(`${APP}/`, "");
      const { start, end } = location.range;
      return `${file}:${start.line}:${start.character}-${end.character}`;
    })
    .sort();
}

/** Ожидаемые вхождения `custom_action` — имя без кавычек, как у любой ссылки. */
const CUSTOM_ACTION_USAGES = [
  "static/references/actions/actions.json:2:13-26",
  "static/references/scenarios/main.json:3:17-30",
];

const nameOfClass = () => rangeIn(ENTITIES_URI, "CustomAction(", "CustomAction".length);
const wholeClass = () => rangeIn(ENTITIES_URI, "@registered", entities.length - entities.indexOf("@registered"));

describe("поиск использований класса в Python-файле", () => {
  beforeEach(() => commandsControl.reset());

  it("по объявлению из Location находит строки DSL в других файлах", async () => {
    commandsControl.definitions = [new vscodeMock.Location(vscodeMock.Uri.parse(ENTITIES_URI), nameOfClass())];
    expect(await referencesFrom(RESOURCES_URI, "= CustomAction")).toEqual(CUSTOM_ACTION_USAGES);
    expect(commandsControl.calls.map((call) => call.command)).toEqual(["vscode.executeDefinitionProvider"]);
  });

  it("понимает LocationLink: объявление целиком, с декоратором", async () => {
    commandsControl.definitions = [
      { targetUri: vscodeMock.Uri.parse(ENTITIES_URI), targetRange: wholeClass() },
    ];
    expect(await referencesFrom(RESOURCES_URI, "import CustomAction")).toEqual(CUSTOM_ACTION_USAGES);
  });

  it("одно объявление из двух ответов даёт вхождения по одному разу", async () => {
    commandsControl.definitions = [
      new vscodeMock.Location(vscodeMock.Uri.parse(ENTITIES_URI), nameOfClass()),
      { targetUri: vscodeMock.Uri.parse(ENTITIES_URI), targetRange: wholeClass() },
    ];
    expect(await referencesFrom(RESOURCES_URI, "= CustomAction")).toEqual(CUSTOM_ACTION_USAGES);
  });

  it("объявление не класса вхождений не даёт и к каретке не откатывается", async () => {
    // Python-расширение увело к функции: запасной путь по каретке здесь не
    // нужен — объявление известно, и это не класс.
    commandsControl.definitions = [
      new vscodeMock.Location(vscodeMock.Uri.parse(ENTITIES_URI), rangeIn(ENTITIES_URI, "helper")),
    ];
    expect(await referencesFrom(ENTITIES_URI, "CustomAction(")).toEqual([]);
  });

  it("объявление в файле, текста которого у расширения нет, пропускается", async () => {
    commandsControl.definitions = [
      new vscodeMock.Location(
        vscodeMock.Uri.parse(`${APP}/venv/lib/site-packages/vendor/actions.py`),
        nameOfClass(),
      ),
    ];
    expect(await referencesFrom(RESOURCES_URI, "= CustomAction")).toEqual([]);
  });

  it("объявление в текущем документе читается из самого документа, а не из хранилища", async () => {
    // Правка ещё не дошла до хранилища — переиндексация идёт с задержкой, — а
    // диапазон объявления Python-расширение посчитало по тексту в редакторе.
    // Возьми провайдер текст из хранилища, смещения легли бы не туда.
    const stale = entities.replace("def helper():\n    pass\n\n\n", "");
    const lagging = workspaceWith((uri) => (uri === ENTITIES_URI ? stale : texts.get(uri)));
    commandsControl.definitions = [new vscodeMock.Location(vscodeMock.Uri.parse(ENTITIES_URI), nameOfClass())];
    expect(await referencesFrom(ENTITIES_URI, "CustomAction(", lagging)).toEqual(CUSTOM_ACTION_USAGES);
  });

  describe("без Python-расширения", () => {
    it("работает с каретки на имени в самом объявлении класса", async () => {
      expect(await referencesFrom(ENTITIES_URI, "CustomAction(")).toEqual(CUSTOM_ACTION_USAGES);
    });

    it("со строки регистрации класса не находит — объявления там нет", async () => {
      expect(await referencesFrom(RESOURCES_URI, "= CustomAction")).toEqual([]);
    });
  });
});
