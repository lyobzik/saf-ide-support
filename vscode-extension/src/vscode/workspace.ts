import * as vscode from "vscode";
import { paths } from "../core/contract";
import { SmartAppIndex } from "../core/index";
import { isDslFile } from "../core/files";

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
  private readonly pending = new Map<string, NodeJS.Timeout>();
  private readonly onIndexed = new vscode.EventEmitter<void>();

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
    this.disposables.push(
      watcher,
      watcher.onDidCreate((uri) => void this.reload(uri, true)),
      watcher.onDidChange((uri) => void this.reload(uri, true)),
      watcher.onDidDelete((uri) => this.forget(uri)),
      // Правки в редакторе видны до сохранения — как в IDEA, где индекс
      // работает по PSI незасохранённого документа.
      vscode.workspace.onDidChangeTextDocument((event) => this.scheduleFromDocument(event.document)),
      vscode.workspace.onDidOpenTextDocument((document) => this.scheduleFromDocument(document)),
    );

    const files = await vscode.workspace.findFiles(FILE_GLOB);
    await Promise.all(files.map((uri) => this.reload(uri)));

    // Уже открытые документы могли измениться до активации расширения: их
    // содержимое в редакторе новее того, что лежит на диске.
    for (const document of vscode.workspace.textDocuments) {
      const key = document.uri.toString();
      if (isDslFile(key) && document.isDirty) this.apply(key, document.getText(), false);
    }

    this.index.markReady();
    this.onIndexed.fire();
  }

  dispose(): void {
    for (const timeout of this.pending.values()) clearTimeout(timeout);
    this.pending.clear();
    for (const disposable of this.disposables) disposable.dispose();
    this.onIndexed.dispose();
  }

  /** Перечитывает файл в индекс, если его не обогнало более свежее обновление. */
  private async reload(uri: vscode.Uri, notify = false): Promise<void> {
    const key = uri.toString();
    if (!isDslFile(key)) return;

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
    if (!isDslFile(key)) return;

    const existing = this.pending.get(key);
    if (existing !== undefined) clearTimeout(existing);
    this.pending.set(
      key,
      setTimeout(() => {
        this.pending.delete(key);
        this.apply(key, document.getText(), true);
      }, REINDEX_DELAY_MS),
    );
  }

  private apply(uri: string, text: string, notify: boolean): void {
    this.nextGeneration(uri);
    this.texts.set(uri, text);
    this.index.upsert(uri, text);
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
