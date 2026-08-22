/**
 * Минимальный фейк VS Code API для тестов адаптера.
 *
 * Проверять `src/vscode/**` на настоящем редакторе дорого, а именно здесь живут
 * ошибки, которых не видит ядро: трансляция смещений в позиции, `Uri`, диапазоны
 * `CompletionItem` и содержимое `WorkspaceEdit`. Поэтому фейк повторяет ровно ту
 * часть API, которой пользуется адаптер, и ничего сверх неё.
 */

export class Position {
  constructor(
    readonly line: number,
    readonly character: number,
  ) {}
}

export class Range {
  constructor(
    readonly start: Position,
    readonly end: Position,
  ) {}
}

export class Uri {
  private constructor(readonly value: string) {}

  static parse(value: string): Uri {
    return new Uri(value);
  }

  toString(): string {
    return this.value;
  }
}

export class Location {
  readonly range: Range;

  /**
   * Настоящий `vscode.Location` принимает и `Range`, и `Position` (последняя
   * разворачивается в пустой диапазон). Фейк обязан вести себя так же: иначе
   * ветка перехода в файл без хранимого текста, где позиция — начало файла,
   * падала бы только в живом редакторе.
   */
  constructor(
    readonly uri: Uri,
    rangeOrPosition: Range | Position,
  ) {
    this.range =
      rangeOrPosition instanceof Position
        ? new Range(rangeOrPosition, rangeOrPosition)
        : rangeOrPosition;
  }
}

export enum CompletionItemKind {
  Keyword = 14,
  Reference = 18,
  Field = 5,
  File = 16,
  Variable = 6,
}

export class CompletionItem {
  detail?: string;
  range?: Range;
  sortText?: string;

  constructor(
    readonly label: string,
    readonly kind?: CompletionItemKind,
  ) {}
}

export class SemanticTokensLegend {
  constructor(
    readonly tokenTypes: string[],
    readonly tokenModifiers: string[] = [],
  ) {}
}

/** Токен в том виде, в каком его получил builder — удобнее для проверок, чем поток чисел. */
export interface RecordedToken {
  readonly line: number;
  readonly char: number;
  readonly length: number;
  readonly typeIndex: number;
}

export class SemanticTokens {
  constructor(readonly tokens: RecordedToken[]) {}
}

export class SemanticTokensBuilder {
  private readonly tokens: RecordedToken[] = [];

  constructor(readonly legend?: SemanticTokensLegend) {}

  push(line: number, char: number, length: number, typeIndex: number): void {
    this.tokens.push({ line, char, length, typeIndex });
  }

  build(): SemanticTokens {
    return new SemanticTokens(this.tokens);
  }
}

export interface RecordedEdit {
  readonly uri: string;
  readonly range: Range;
  readonly newText: string;
}

export class WorkspaceEdit {
  readonly edits: RecordedEdit[] = [];

  replace(uri: Uri, range: Range, newText: string): void {
    this.edits.push({ uri: uri.toString(), range, newText });
  }
}

export enum DiagnosticSeverity {
  Error = 0,
  Warning = 1,
  Information = 2,
  Hint = 3,
}

export class Diagnostic {
  source?: string;

  constructor(
    readonly range: Range,
    readonly message: string,
    readonly severity?: DiagnosticSeverity,
  ) {}
}

/** Коллекция диагностик, запоминающая последнее состояние по файлам. */
export class DiagnosticCollection {
  readonly entries = new Map<string, Diagnostic[]>();

  set(uri: Uri, diagnostics: Diagnostic[]): void {
    this.entries.set(uri.toString(), diagnostics);
  }

  delete(uri: Uri): void {
    this.entries.delete(uri.toString());
  }
}

/** Документ с той же арифметикой смещений, что и настоящий TextDocument. */
export class TextDocument {
  constructor(
    readonly uri: Uri,
    private readonly text: string,
  ) {}

  getText(): string {
    return this.text;
  }

  offsetAt(position: Position): number {
    const lines = this.text.split("\n");
    let offset = 0;
    for (let i = 0; i < position.line && i < lines.length; i++) {
      offset += (lines[i] as string).length + 1;
    }
    return offset + position.character;
  }

  positionAt(offset: number): Position {
    const clamped = Math.max(0, Math.min(offset, this.text.length));
    const before = this.text.slice(0, clamped);
    const line = before.split("\n").length - 1;
    const lineStart = before.lastIndexOf("\n") + 1;
    return new Position(line, clamped - lineStart);
  }
}

export const workspace = {
  textDocuments: [] as TextDocument[],
};

export const languages = {
  createDiagnosticCollection: (_name: string): DiagnosticCollection => new DiagnosticCollection(),
};

/** Подписка, которую можно снять — как настоящий Disposable. */
export class Disposable {
  constructor(private readonly onDispose: () => void) {}

  dispose(): void {
    this.onDispose();
  }
}

/** Минимальный EventEmitter: подписка плюс ручной fire из теста. */
export class EventEmitter<T> {
  private readonly listeners = new Set<(value: T) => void>();

  readonly event = (listener: (value: T) => void): Disposable => {
    this.listeners.add(listener);
    return new Disposable(() => this.listeners.delete(listener));
  };

  fire(value: T): void {
    for (const listener of [...this.listeners]) listener(value);
  }

  dispose(): void {
    this.listeners.clear();
  }
}

/** Watcher, событиями которого управляет тест. */
export class FileSystemWatcher {
  readonly created = new EventEmitter<Uri>();
  readonly changed = new EventEmitter<Uri>();
  readonly deleted = new EventEmitter<Uri>();

  readonly onDidCreate = this.created.event;
  readonly onDidChange = this.changed.event;
  readonly onDidDelete = this.deleted.event;

  dispose(): void {
    this.created.dispose();
    this.changed.dispose();
    this.deleted.dispose();
  }
}

/**
 * Управляемая часть фейкового workspace: тест задаёт содержимое «диска», момент
 * завершения findFiles и сам дёргает события watcher'а.
 */
export const workspaceControl = {
  files: new Map<string, string>(),
  watchers: [] as FileSystemWatcher[],
  /** Разрешается тестом, когда первичное сканирование должно завершиться. */
  findFilesGate: Promise.resolve(),
  /**
   * Очередь шлюзов для последовательных вызовов readFile: тест задаёт, в каком
   * порядке завершатся чтения, чтобы воспроизвести обгон.
   */
  readGates: [] as Promise<void>[],
  /** URI начатых чтений — тест по ним понимает, что чтение уже стартовало. */
  reads: [] as string[],
  /** Шлюзы и журнал для `fs.stat` — им проверяется существование не-DSL файлов. */
  statGates: [] as Promise<void>[],
  stats: [] as string[],
  /** Подписчики на закрытие документа: тест закрывает документ сам. */
  closeListeners: [] as ((document: TextDocument) => void)[],
  /** Открытые документы, которые расширение видит на старте. */
  set openDocuments(documents: TextDocument[]) {
    workspace.textDocuments = documents;
  },
  get openDocuments(): TextDocument[] {
    return workspace.textDocuments;
  },
  /** Сколько ближайших Python-сканирований должны завершиться ошибкой. */
  findFilesFailures: 0,
  /** Шлюз для Python-сканирования: тест держит его открытым, пока шлёт события. */
  pythonFindFilesGate: Promise.resolve(),
  /** Сколько раз Python-сканирование уже стартовало. */
  pythonFindFilesCalls: 0,

  /** Подписчики на открытие документа. */
  openListeners: [] as ((document: TextDocument) => void)[],

  /** Имитирует открытие документа в редакторе. */
  fireOpen(document: TextDocument): void {
    for (const listener of this.openListeners) listener(document);
  },

  /** Имитирует закрытие документа в редакторе. */
  fireClose(document: TextDocument): void {
    for (const listener of this.closeListeners) listener(document);
  },

  reset(): void {
    this.files.clear();
    this.watchers = [];
    this.closeListeners = [];
    this.openListeners = [];
    this.openDocuments = [];
    this.findFilesFailures = 0;
    this.pythonFindFilesGate = Promise.resolve();
    this.pythonFindFilesCalls = 0;
    this.findFilesGate = Promise.resolve();
    this.readGates = [];
    this.reads = [];
    this.statGates = [];
    this.stats = [];
    workspace.textDocuments = [];
  },
};

Object.assign(workspace, {
  async findFiles(_glob: string, _exclude?: string): Promise<Uri[]> {
    await workspaceControl.findFilesGate;
    if (_glob.includes(".py")) {
      workspaceControl.pythonFindFilesCalls++;
      await workspaceControl.pythonFindFilesGate;
    }
    // Падение имитируется только для Python-сканирования: DSL-скан у теста
    // должен пройти, иначе не с чего появиться корню приложения.
    if (workspaceControl.findFilesFailures > 0 && _glob.includes(".py")) {
      workspaceControl.findFilesFailures--;
      throw new Error("findFiles failed");
    }
    return [...workspaceControl.files.keys()].map((uri) => Uri.parse(uri));
  },

  createFileSystemWatcher(_glob: string): FileSystemWatcher {
    const watcher = new FileSystemWatcher();
    workspaceControl.watchers.push(watcher);
    return watcher;
  },

  fs: {
    async readFile(uri: Uri): Promise<Uint8Array> {
      // Содержимое фиксируется на момент начала чтения — как у настоящего fs,
      // где данные считываются в момент вызова, а промис резолвится позже.
      const text = workspaceControl.files.get(uri.toString());
      workspaceControl.reads.push(uri.toString());
      const gate = workspaceControl.readGates.shift();
      if (gate !== undefined) await gate;
      if (text === undefined) throw new Error(`нет файла ${uri.toString()}`);
      return new TextEncoder().encode(text);
    },

    async stat(uri: Uri): Promise<{ type: number }> {
      // Существование фиксируется на момент вызова — как у настоящего fs,
      // где промис резолвится позже уже снятого состояния.
      const exists = workspaceControl.files.has(uri.toString());
      workspaceControl.stats.push(uri.toString());
      const gate = workspaceControl.statGates.shift();
      if (gate !== undefined) await gate;
      if (!exists) throw new Error(`нет файла ${uri.toString()}`);
      return { type: 1 };
    },
  },

  onDidChangeTextDocument(_listener: unknown): Disposable {
    return new Disposable(() => undefined);
  },

  onDidOpenTextDocument(listener: (document: TextDocument) => void): Disposable {
    workspaceControl.openListeners.push(listener);
    return new Disposable(() => {
      workspaceControl.openListeners = workspaceControl.openListeners.filter(
        (candidate) => candidate !== listener,
      );
    });
  },

  onDidCloseTextDocument(listener: (document: TextDocument) => void): Disposable {
    workspaceControl.closeListeners.push(listener);
    return new Disposable(() => {
      workspaceControl.closeListeners = workspaceControl.closeListeners.filter(
        (candidate) => candidate !== listener,
      );
    });
  },
});
