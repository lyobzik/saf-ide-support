import { beforeEach, describe, expect, it } from "vitest";
import { Uri, workspaceControl } from "../mocks/vscode";
import { SmartAppWorkspace } from "../../src/vscode/workspace";

/**
 * Порядок подписки и сканирования. Watcher обязан существовать до `findFiles`:
 * иначе файл, созданный во время первичного сканирования, не попадёт в индекс
 * до следующей правки — гонка, которую в живом редакторе поймать почти нельзя.
 */

/** Ждёт выполнения условия, прокручивая очередь микрозадач. */
const waitFor = async (condition: () => boolean, attempts = 100): Promise<void> => {
  for (let i = 0; i < attempts; i++) {
    if (condition()) return;
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
  throw new Error("условие не выполнилось за отведённое число попыток");
};

const root = "file:///w/static/references";
const formsUri = `${root}/forms/forms.json`;
const lateUri = `${root}/forms/late.json`;
const templateUri = `${root}/templates/items.jinja2`;

describe("SmartAppWorkspace.start", () => {
  beforeEach(() => workspaceControl.reset());

  it("индексирует файл, созданный во время первичного сканирования", async () => {
    workspaceControl.files.set(formsUri, '{ "early_form": { "type": "form" } }');

    // Сканирование «зависает», пока тест не отпустит шлюз.
    let release = (): void => undefined;
    workspaceControl.findFilesGate = new Promise<void>((resolve) => {
      release = resolve;
    });

    const workspace = new SmartAppWorkspace();
    const started = workspace.start();

    // Пока findFiles не завершился, watcher'ы уже обязаны быть подписаны:
    // первый — на DSL-файлы, второй — на Python-ресурсы приложения.
    expect(workspaceControl.watchers).toHaveLength(2);
    workspaceControl.files.set(lateUri, '{ "late_form": { "type": "form" } }');
    workspaceControl.watchers[0]!.created.fire(Uri.parse(lateUri));

    release();
    await started;
    // Дожидаемся асинхронного чтения файла, порождённого событием watcher'а.
    await new Promise((resolve) => setTimeout(resolve, 0));

    const names = workspace.index.namesOfKind("FORM", "w/static/references");
    expect(names.sort()).toEqual(["early_form", "late_form"]);
    workspace.dispose();
  });

  it("шаблон, удалённый во время сканирования, не остаётся в реестре", async () => {
    // Не-DSL файлы попадают в реестр по факту существования, без чтения. Тот же
    // счётчик поколений обязан отсечь файл, исчезнувший между findFiles и stat:
    // иначе переход вёл бы в удалённый файл.
    workspaceControl.files.set(templateUri, "{{ items }}");
    let releaseStat = (): void => undefined;
    workspaceControl.statGates = [
      new Promise<void>((resolve) => {
        releaseStat = resolve;
      }),
    ];

    const workspace = new SmartAppWorkspace();
    const started = workspace.start();
    await waitFor(() => workspaceControl.stats.includes(templateUri));

    // Файл исчез уже после начала проверки существования.
    workspaceControl.files.delete(templateUri);
    workspaceControl.watchers[0]!.deleted.fire(Uri.parse(templateUri));
    releaseStat();

    await started;
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(workspace.index.findFile(["w/static/references/templates/items.jinja2"])).toBeUndefined();
    workspace.dispose();
  });

  it("шаблон набора попадает в реестр по факту существования", async () => {
    workspaceControl.files.set(templateUri, "{{ items }}");

    const workspace = new SmartAppWorkspace();
    await workspace.start();

    expect(workspace.index.findFile(["w/static/references/templates/items.jinja2"])).toBe(
      templateUri,
    );
    // Содержимое шаблона не читается: важен только факт существования.
    expect(workspaceControl.reads).not.toContain(templateUri);
    workspace.dispose();
  });

  it("удаление файла во время сканирования не оставляет его в индексе", async () => {
    workspaceControl.files.set(formsUri, '{ "early_form": { "type": "form" } }');
    let release = (): void => undefined;
    workspaceControl.findFilesGate = new Promise<void>((resolve) => {
      release = resolve;
    });

    const workspace = new SmartAppWorkspace();
    const started = workspace.start();
    workspaceControl.files.delete(formsUri);
    workspaceControl.watchers[0]!.deleted.fire(Uri.parse(formsUri));

    release();
    await started;
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(workspace.index.namesOfKind("FORM", "w/static/references")).toEqual([]);
    workspace.dispose();
  });
});

describe("порядок применения снимков", () => {
  beforeEach(() => workspaceControl.reset());

  it("позднее завершившееся старое чтение не затирает свежее содержимое", async () => {
    // Сценарий обгона: первичное сканирование читает старое содержимое, watcher
    // успевает прочитать и применить новое, и только потом завершается первое
    // чтение. Без generation token индекс откатился бы к старому состоянию.
    workspaceControl.files.set(formsUri, '{ "old_form": { "type": "form" } }');

    let releaseInitialRead = (): void => undefined;
    const initialRead = new Promise<void>((resolve) => {
      releaseInitialRead = resolve;
    });
    // Первое чтение (из сканирования) ждёт шлюза, второе (из watcher) — нет.
    workspaceControl.readGates = [initialRead];

    const workspace = new SmartAppWorkspace();
    const started = workspace.start();

    // Дожидаемся, что первичное чтение стартовало и захватило СТАРОЕ содержимое:
    // без этого порядок чтений недетерминирован и тест ничего не проверяет.
    await waitFor(() => workspaceControl.reads.length === 1);

    // Файл переписан, watcher сообщил об этом — новое чтение проходит мгновенно.
    workspaceControl.files.set(formsUri, '{ "new_form": { "type": "form" } }');
    workspaceControl.watchers[0]!.changed.fire(Uri.parse(formsUri));
    await waitFor(() => workspace.index.namesOfKind("FORM", "w/static/references").length === 1);

    // Теперь отпускаем «догоняющее» чтение со старым содержимым.
    releaseInitialRead();
    await started;
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(workspace.index.namesOfKind("FORM", "w/static/references")).toEqual(["new_form"]);
    workspace.dispose();
  });
});

describe("жизненный цикл ресурсов приложения", () => {
  beforeEach(() => workspaceControl.reset());

  const appRoot = "file:///w/app_b";
  const appDsl = `${appRoot}/static/references/actions/actions.json`;
  const appConfig = `${appRoot}/app_config.py`;
  const appResources = `${appRoot}/app/resources/custom.py`;
  // Импорт базы обязателен: без него база «ниоткуда», и цепочка по контракту
  // не подтверждается.
  const resourcesText = (action: string): string =>
    "from smart_kit.resources import SmartAppResources\n\n" +
    `class R(SmartAppResources):\n    def init_actions(self):\n        actions["${action}"] = C\n`;

  it("новое приложение сканируется после активации", async () => {
    // На старте приложения ещё нет: есть только чужой набор.
    workspaceControl.files.set(formsUri, '{ "early_form": { "type": "form" } }');
    const workspace = new SmartAppWorkspace();
    await workspace.start();
    expect(workspace.index.customKeywordsOf("w/app_b/static/references")).toEqual([]);

    // Python-файлы приложения уже лежат на диске, а набор появляется сейчас.
    workspaceControl.files.set(appConfig, "from app.resources.custom import R\nRESOURCES = R\n");
    workspaceControl.files.set(appResources, resourcesText("late_action"));
    workspaceControl.files.set(appDsl, '{ "some_action": { "type": "late_action" } }');
    workspaceControl.watchers[0]!.created.fire(Uri.parse(appDsl));

    await waitFor(
      () => workspace.index.customKeywordsOf("w/app_b/static/references").length > 0,
    );
    expect(
      workspace.index.customKeywordsOf("w/app_b/static/references").map((k) => k.name),
    ).toEqual(["late_action"]);
    workspace.dispose();
  });

  it("готовность не наступает раньше конца сканирования Python", async () => {
    // Досканирование, начатое при индексации DSL-файла, обязано завершиться до
    // markReady(): иначе индекс «готов», а словарь приложения пуст.
    workspaceControl.files.set(appConfig, "from app.resources.custom import R\nRESOURCES = R\n");
    workspaceControl.files.set(appResources, resourcesText("ready_action"));
    workspaceControl.files.set(appDsl, '{ "some_action": { "type": "ready_action" } }');

    const workspace = new SmartAppWorkspace();
    await workspace.start();

    expect(workspace.index.isReady()).toBe(true);
    expect(
      workspace.index.customKeywordsOf("w/app_b/static/references").map((k) => k.name),
    ).toEqual(["ready_action"]);
    workspace.dispose();
  });

  it("готовность ждёт и работу, порождённую dirty-документом", async () => {
    // Открытый несохранённый DSL-файл может открыть новый набор уже после
    // первичного сканирования: markReady() обязан дождаться и этой работы.
    workspaceControl.files.set(appConfig, "from app.resources.custom import R\nRESOURCES = R\n");
    workspaceControl.files.set(appResources, resourcesText("dirty_action"));
    workspaceControl.openDocuments = [
      {
        uri: Uri.parse(appDsl),
        isDirty: true,
        getText: () => '{ "some_action": { "type": "dirty_action" } }',
      } as never,
    ];

    const workspace = new SmartAppWorkspace();
    await workspace.start();

    expect(workspace.index.isReady()).toBe(true);
    expect(
      workspace.index.customKeywordsOf("w/app_b/static/references").map((k) => k.name),
    ).toEqual(["dirty_action"]);
    workspace.dispose();
  });

  it("готовность ждёт отложенную переиндексацию открытого документа", async () => {
    // Открытый документ ставит дебаунс-таймер; без ожидания он сработал бы уже
    // после markReady(), и словарь появился бы «потом».
    workspaceControl.files.set(appConfig, "from app.resources.custom import R\nRESOURCES = R\n");
    workspaceControl.files.set(appResources, resourcesText("disk_action"));
    workspaceControl.files.set(appDsl, '{ "some_action": { "type": "disk_action" } }');
    workspaceControl.openDocuments = [
      {
        uri: Uri.parse(appResources),
        isDirty: false,
        getText: () => resourcesText("typed_action"),
      } as never,
    ];

    const workspace = new SmartAppWorkspace();
    const started = workspace.start();
    // Событие открытия приходит во время старта — как в живом редакторе.
    workspaceControl.fireOpen(workspaceControl.openDocuments[0]!);
    await started;

    expect(workspace.index.isReady()).toBe(true);
    expect(
      workspace.index.customKeywordsOf("w/app_b/static/references").map((k) => k.name),
    ).toEqual(["typed_action"]);
    workspace.dispose();
  });

  it("неудачное сканирование не помечает корень навсегда", async () => {
    workspaceControl.files.set(appConfig, "from app.resources.custom import R\nRESOURCES = R\n");
    workspaceControl.files.set(appResources, resourcesText("retried_action"));
    workspaceControl.files.set(appDsl, '{ "some_action": { "type": "retried_action" } }');
    // Все сканирования Python во время старта падают: корень не должен
    // остаться помеченным как просканированный.
    workspaceControl.findFilesFailures = 10;

    const workspace = new SmartAppWorkspace();
    await workspace.start();
    expect(workspace.index.customKeywordsOf("w/app_b/static/references")).toEqual([]);

    // Сканирование снова работает — следующее событие обязано попробовать заново.
    workspaceControl.findFilesFailures = 0;
    workspaceControl.watchers[0]!.created.fire(Uri.parse(appDsl));
    await waitFor(
      () => workspace.index.customKeywordsOf("w/app_b/static/references").length > 0,
    );
    workspace.dispose();
  });

  it("во время сканирования словарь не отдаётся", async () => {
    workspaceControl.files.set(appConfig, "from app.resources.custom import R\nRESOURCES = R\n");
    workspaceControl.files.set(appResources, resourcesText("scanned_action"));
    workspaceControl.files.set(appDsl, '{ "some_action": { "type": "scanned_action" } }');

    const workspace = new SmartAppWorkspace();
    await workspace.start();
    const names = () =>
      workspace.index.customKeywordsOf("w/app_b/static/references").map((k) => k.name);
    expect(names()).toEqual(["scanned_action"]);

    // Пока идёт сканирование, словарь заведомо неполон — отдаём пустой.
    workspace.index.beginPythonScan();
    expect(names()).toEqual([]);
    workspace.index.endPythonScan();
    expect(names()).toEqual(["scanned_action"]);
    workspace.dispose();
  });

  it("закрытие несохранённого Python-файла возвращает содержимое диска", async () => {
    workspaceControl.files.set(appConfig, "from app.resources.custom import R\nRESOURCES = R\n");
    workspaceControl.files.set(appResources, resourcesText("saved_action"));
    workspaceControl.files.set(appDsl, '{ "some_action": { "type": "saved_action" } }');

    const workspace = new SmartAppWorkspace();
    await workspace.start();
    const names = () =>
      workspace.index.customKeywordsOf("w/app_b/static/references").map((k) => k.name);
    expect(names()).toEqual(["saved_action"]);

    // Правка в редакторе видна сразу, до сохранения.
    workspace.index.upsert(appResources, resourcesText("unsaved_action"));
    expect(names()).toEqual(["unsaved_action"]);

    // Закрытие без сохранения обязано вернуть словарь к диску.
    workspaceControl.fireClose({
      uri: Uri.parse(appResources),
      getText: () => resourcesText("unsaved_action"),
      isDirty: false,
    } as never);
    await waitFor(() => names()[0] === "saved_action");
    expect(names()).toEqual(["saved_action"]);
    workspace.dispose();
  });
});
