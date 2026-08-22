import * as vscode from "vscode";
import { paths } from "../core/contract";
import { SmartAppIndex } from "../core/index";
import { isDslFile } from "../core/files";
import { resourceScan } from "../core/contract";

/**
 * Адаптер воркспейса: находит DSL-файлы, следит за изменениями и кормит ядро
 * снимками текста. Ядро о существовании VS Code и файловой системы не знает —
 * вся эта граница живёт здесь.
 *
 * Хранилище текстов нужно, чтобы переводить смещения в позиции для файлов, не
 * открытых в редакторе: иначе каждый переход к определению требовал бы
 * асинхронного `openTextDocument`.
 */
export class SmartAppWorkspace implements vscode.Disposable {
  readonly index = new SmartAppIndex();

  private readonly texts = new Map<string, string>();
  /**
   * Номер последнего применённого снимка по URI. Чтения файлов асинхронны и
   * могут завершиться не в том порядке, в каком начались: первичное
   * сканирование способно догнать и затереть более свежее содержимое, уже
   * применённое watcher'ом. Поэтому результат чтения применяется, только если
   * с его начала никто другой не обновил тот же URI.
   */
  private readonly generations = new Map<string, number>();
  private readonly disposables: vscode.Disposable[] = [];
  /**
   * Отложенные переиндексации по правкам в редакторе. Кроме таймера хранится
   * его завершение: `settle()` обязан дождаться и дебаунса, иначе callback
   * сработает уже после `markReady()`.
   */
  private readonly debounced = new Map<string, { timeout: NodeJS.Timeout; done: () => void }>();
  private readonly onIndexed = new vscode.EventEmitter<void>();

  /**
   * Корни приложений, для которых Python уже просканирован. Новый набор
   * `static/references` может появиться после активации, а Python-файлы в нём
   * — лежать давно: без повторного скана слова такого приложения не появились
   * бы до ручной правки .py.
   */
  private readonly scannedRoots = new Set<string>();

  /** Идёт ли сканирование Python прямо сейчас. */
  private scanning = false;

  /**
   * Просьба пересканировать, пришедшая во время идущего сканирования. Событие
   * иначе теряется: вызов видит корень уже помеченным и завершается, а идущий
   * scan падает и пометку снимает — повторить оказывается некому.
   */
  private rescanRequested = false;

  /**
   * Все начатые асинхронные операции: чтения файлов и сканирования Python.
   * `start()` обязан дождаться и тех, что запустил не он сам — досканирование
   * из-за нового набора, чтение по событию watcher'а, обработку dirty-документа.
   * Иначе индекс объявляет себя готовым, пока работа ещё идёт.
   */
  private readonly inFlight = new Set<Promise<void>>();

  /** Срабатывает после изменения индекса — подписчики пересчитывают диагностику. */
  readonly onDidUpdate = this.onIndexed.event;

  /** Текст проиндексированного файла (для трансляции смещений). */
  textOf(uri: string): string | undefined {
    return this.texts.get(uri);
  }

  /**
   * Подписка на изменения и первичное сканирование — именно в этом порядке.
   *
   * Watcher регистрируется **до** `findFiles`: файл, созданный или изменённый во
   * время сканирования, иначе не попал бы в индекс до следующей правки. Порядок
   * безопасен — `upsert` идемпотентен, а более поздний по времени снимок текста
   * просто заменит более ранний.
   */
  async start(): Promise<void> {
    const watcher = vscode.workspace.createFileSystemWatcher(FILE_GLOB);
    // Ресурсы приложения лежат вне static/references, поэтому у Python свой
    // watcher — один на воркспейс. Отдельные watcher'ы на корень приложения
    // пришлось бы пересоздавать при каждом изменении набора корней.
    const pythonWatcher = vscode.workspace.createFileSystemWatcher(PYTHON_GLOB);
    this.disposables.push(
      watcher,
      pythonWatcher,
      watcher.onDidCreate((uri) => this.track(this.reload(uri, true))),
      watcher.onDidChange((uri) => this.track(this.reload(uri, true))),
      watcher.onDidDelete((uri) => this.forget(uri)),
      pythonWatcher.onDidCreate((uri) => this.track(this.reloadPython(uri, true))),
      pythonWatcher.onDidChange((uri) => this.track(this.reloadPython(uri, true))),
      pythonWatcher.onDidDelete((uri) => this.forget(uri)),
      // Правки в редакторе видны до сохранения — как в IDEA, где индекс
      // работает по PSI незасохранённого документа.
      vscode.workspace.onDidChangeTextDocument((event) => this.scheduleFromDocument(event.document)),
      vscode.workspace.onDidOpenTextDocument((document) => this.scheduleFromDocument(document)),
      // Закрытие без сохранения возвращает индекс к содержимому диска: иначе
      // слово, добавленное и не сохранённое, осталось бы в словаре навсегда.
      vscode.workspace.onDidCloseTextDocument((document) =>
        this.track(this.restoreFromDisk(document)),
      ),
    );

    const files = await vscode.workspace.findFiles(FILE_GLOB);
    await Promise.all(files.map((uri) => this.reload(uri)));

    // Python сканируется после DSL: корни приложений известны только по
    // найденным наборам static/references.
    await this.scanPython();

    // Уже открытые документы могли измениться до активации расширения: их
    // содержимое в редакторе новее того, что лежит на диске.
    for (const document of vscode.workspace.textDocuments) {
      const key = document.uri.toString();
      if ((isDslFile(key) || isPythonFile(key)) && document.isDirty) {
        this.apply(key, document.getText(), false);
      }
    }

    // Готовность — только после обоих первичных сканирований: иначе кастомные
    // слова первые секунды выглядели бы неизвестными, а потом «вдруг»
    // становились ключевыми.
    // Работа могла породить работу: dirty-документ способен открыть новый
    // набор, событие watcher'а — новый файл. Ждём, пока очередь опустеет.
    await this.settle();

    this.index.markReady();
    this.onIndexed.fire();
  }

  dispose(): void {
    for (const key of [...this.debounced.keys()]) this.cancelScheduled(key);
    for (const disposable of this.disposables) disposable.dispose();
    this.onIndexed.dispose();
  }

  /** Перечитывает файл в индекс, если его не обогнало более свежее обновление. */
  private async reload(uri: vscode.Uri, notify = false): Promise<void> {
    const key = uri.toString();
    if (!isDslFile(key)) {
      await this.noteAsset(uri, notify);
      return;
    }

    const generation = this.nextGeneration(key);
    try {
      const bytes = await vscode.workspace.fs.readFile(uri);
      if (this.generations.get(key) !== generation) return;
      this.apply(key, new TextDecoder().decode(bytes), notify);
    } catch {
      if (this.generations.get(key) !== generation) return;
      // Файл исчез или недоступен — просто убираем его из индекса.
      this.forget(uri);
    }
  }

  /**
   * Сканирует Python-файлы, если появился корень приложения, которого раньше не
   * было. Перечитываются все найденные файлы: цепочка ресурсов собирается из
   * нескольких модулей, и какие из них относятся к новому корню, заранее не
   * известно.
   */
  private async scanPython(notify = false): Promise<void> {
    const roots = this.index.applicationRoots();
    const fresh = [...roots].filter((root) => !this.scannedRoots.has(root));
    if (fresh.length === 0) {
      // Помечено — но, возможно, тем сканированием, которое ещё идёт и может
      // упасть. Тогда наше событие обязано пережить его провал.
      if (this.scanning) this.rescanRequested = true;
      return;
    }
    for (const root of fresh) this.scannedRoots.add(root);

    this.scanning = true;
    this.index.beginPythonScan();
    try {
      const files = await vscode.workspace.findFiles(PYTHON_GLOB, PYTHON_EXCLUDE);
      await Promise.all(files.map((uri) => this.reloadPython(uri)));
    } catch {
      // Сканирование не удалось: корень нельзя оставлять помеченным, иначе
      // приложение навсегда останется без словаря — повторной попытки не будет.
      for (const root of fresh) this.scannedRoots.delete(root);
    } finally {
      this.scanning = false;
      this.index.endPythonScan();
    }

    if (this.rescanRequested) {
      this.rescanRequested = false;
      await this.scanPython(notify);
      return;
    }
    if (notify) this.onIndexed.fire();
  }

  /** Запоминает начатую операцию, чтобы `start()` мог её дождаться. */
  private track(operation: Promise<void>): void {
    this.inFlight.add(operation);
    void operation.finally(() => this.inFlight.delete(operation));
  }

  /** Ждёт, пока очередь начатых операций опустеет, включая порождённые ими. */
  private async settle(): Promise<void> {
    while (this.inFlight.size > 0) await Promise.all([...this.inFlight]);
  }

  /** Возвращает файл к содержимому диска после закрытия несохранённого документа. */
  private async restoreFromDisk(document: vscode.TextDocument): Promise<void> {
    const key = document.uri.toString();
    if (!isDslFile(key) && !isPythonFile(key)) return;
    this.cancelScheduled(key);
    if (isPythonFile(key)) await this.reloadPython(document.uri, true);
    else await this.reload(document.uri, true);
  }

  /** Перечитывает Python-файл приложения: его содержимое нужно целиком. */
  private async reloadPython(uri: vscode.Uri, notify = false): Promise<void> {
    const key = uri.toString();
    if (!isPythonFile(key)) return;
    const generation = this.nextGeneration(key);
    try {
      const bytes = await vscode.workspace.fs.readFile(uri);
      if (this.generations.get(key) !== generation) return;
      this.apply(key, new TextDecoder().decode(bytes), notify);
    } catch {
      if (this.generations.get(key) !== generation) return;
      this.forget(uri);
    }
  }

  /**
   * Регистрирует не-DSL файл набора (шаблон Jinja): содержимое не нужно, важен
   * только факт существования — по нему резолвятся ссылки вида `"file": "…"`.
   *
   * Существование проверяется `stat`, а результат применяется под тем же
   * счётчиком поколений, что и чтение DSL-файлов: `findFiles` отдаёт снимок, и
   * файл, удалённый между сканированием и этим вызовом, иначе остался бы в
   * реестре — переход вёл бы в несуществующий файл.
   */
  private async noteAsset(uri: vscode.Uri, notify: boolean): Promise<void> {
    const key = uri.toString();
    const generation = this.nextGeneration(key);
    try {
      await vscode.workspace.fs.stat(uri);
      if (this.generations.get(key) !== generation) return;
      this.index.noteFile(key);
      if (notify) this.onIndexed.fire();
    } catch {
      if (this.generations.get(key) !== generation) return;
      this.forget(uri);
    }
  }

  /** Отмечает начало нового обновления URI и возвращает его номер. */
  private nextGeneration(uri: string): number {
    const generation = (this.generations.get(uri) ?? 0) + 1;
    this.generations.set(uri, generation);
    return generation;
  }

  private forget(uri: vscode.Uri): void {
    const key = uri.toString();
    this.nextGeneration(key);
    this.texts.delete(key);
    this.index.remove(key);
    this.onIndexed.fire();
  }

  /** Дебаунс переиндексации по правкам в редакторе. */
  private scheduleFromDocument(document: vscode.TextDocument): void {
    const key = document.uri.toString();
    if (!isDslFile(key) && !isPythonFile(key)) return;

    // Новая правка отменяет прежнюю: её работа поглощена этой, поэтому ожидание
    // старого таймера завершается — иначе `settle()` ждал бы отменённое.
    this.cancelScheduled(key);
    this.track(
      new Promise<void>((resolve) => {
        const timeout = setTimeout(() => {
          this.debounced.delete(key);
          this.apply(key, document.getText(), true);
          resolve();
        }, REINDEX_DELAY_MS);
        this.debounced.set(key, { timeout, done: resolve });
      }),
    );
  }

  /** Снимает отложенную переиндексацию и закрывает её ожидание. */
  private cancelScheduled(key: string): void {
    const scheduled = this.debounced.get(key);
    if (scheduled === undefined) return;
    clearTimeout(scheduled.timeout);
    this.debounced.delete(key);
    scheduled.done();
  }

  private apply(uri: string, text: string, notify: boolean): void {
    this.nextGeneration(uri);
    this.texts.set(uri, text);
    this.index.upsert(uri, text);
    if (isDslFile(uri)) this.track(this.scanPython(notify));
    if (notify) this.onIndexed.fire();
  }
}

/** Пауза перед переиндексацией: набор текста не должен дёргать индекс на каждый символ. */
const REINDEX_DELAY_MS = 250;

/**
 * Что искать в воркспейсе. Каталоги берутся из контракта, а расширение файла
 * намеренно не включено в шаблон: контракт допускает игнорирование регистра
 * (`.JSON`), а glob в VS Code регистрозависим. Отбор делает `isDslFile`, который
 * применяет ровно то же правило, что и плагин IDEA.
 */
const FILE_GLOB = `**/${paths.rootSegments.join("/")}/**/*`;

/** Ресурсы приложения: Python-файлы вне зависимостей и артефактов сборки. */
const PYTHON_GLOB = `**/*${resourceScan.fileExtension}`;
const PYTHON_EXCLUDE = `**/{${[...resourceScan.excludedDirs].join(",")}}/**`;

/**
 * Python-файл приложения. Исключённые каталоги отсекаются и здесь, а не только
 * в глобе: watcher срабатывает и на файлы внутри `venv`.
 */
const isPythonFile = (uri: string): boolean => SmartAppIndex.isPythonFile(uri);
