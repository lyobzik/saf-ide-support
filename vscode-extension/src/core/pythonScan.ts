import { resourceScan } from "./contract";

/**
 * Минимальный разбор Python — ровно столько, сколько нужно, чтобы прочитать
 * ресурсы приложения: кто кого наследует, какие `init_*` есть у класса,
 * вызывают ли они `super()`, что регистрируют и что импортируют.
 *
 * Это не парсер Python и не претендует им быть: поддерживаемые и отвергаемые
 * формы перечислены в плане (`docs/plans/2026-08-22-custom-app-resources.md`)
 * и закреплены одной таблицей входов в тестах обеих реализаций. Порт 1:1 —
 * `SmartAppResourceScanner.kt`.
 *
 * Разбор идёт в два шага. Сначала текст **маскируется**: содержимое строк и
 * комментариев заменяется заполнителем той же длины, поэтому скобки и кавычки
 * внутри литералов не ломают структуру, а смещения остаются настоящими. Затем
 * маскированный текст режется на логические строки (перенос внутри скобок и
 * хвостовой backslash продолжают строку), и каждая разбирается регулярками.
 */

/** Строковый литерал: свой диапазон, диапазон содержимого и префикс (`r`, `f`, …). */
export interface PyString {
  readonly start: number;
  readonly end: number;
  readonly contentStart: number;
  readonly contentEnd: number;
  readonly prefix: string;
}

export interface LogicalLine {
  readonly start: number;
  readonly end: number;
  readonly indent: number;
}

/** Символ-заполнитель: занимает место содержимого строк и комментариев. */
export const FILLER = "\u0001";

/**
 * Заменяет содержимое строк и комментариев заполнителем той же длины.
 * Длина текста сохраняется, поэтому смещения в маске — настоящие смещения файла.
 */
export function maskPython(text: string): { masked: string; strings: PyString[] } {
  const out = text.split("");
  const strings: PyString[] = [];
  let i = 0;
  while (i < text.length) {
    const char = text[i] as string;
    if (char === "#") {
      while (i < text.length && text[i] !== "\n") out[i++] = " ";
      continue;
    }
    if (char === '"' || char === "'") {
      const prefixStart = prefixStartAt(text, i);
      const prefix = text.slice(prefixStart, i);
      const triple = text.startsWith(char.repeat(3), i);
      const quote = triple ? char.repeat(3) : char;
      const contentStart = i + quote.length;
      let j = contentStart;
      while (j < text.length) {
        // В Python обратный слэш не даёт кавычке завершить строку даже в
        // raw-литерале (`r"a\"b"` — валидно): для поиска конца это учитывается
        // всегда, а вот содержимое raw-строки при декодировании не меняется.
        if (text[j] === "\\") {
          j += 2;
          continue;
        }
        if (text.startsWith(quote, j)) break;
        // Незакрытая однострочная строка обрывается концом строки — так же,
        // как её видит Python, и структура файла не съезжает.
        if (!triple && text[j] === "\n") break;
        j++;
      }
      const contentEnd = Math.min(j, text.length);
      // Переводы строк внутри литерала тоже маскируются: многострочная строка —
      // одна логическая строка, иначе её продолжения выглядели бы как код с
      // нулевым отступом и выбрасывали бы разбор из тела класса.
      for (let k = contentStart; k < contentEnd; k++) out[k] = FILLER;
      for (let k = prefixStart; k < i; k++) out[k] = FILLER;
      strings.push({
        start: prefixStart,
        end: contentEnd + quote.length,
        contentStart,
        contentEnd,
        prefix,
      });
      i = contentEnd + (text.startsWith(quote, contentEnd) ? quote.length : 0);
      continue;
    }
    i++;
  }
  return { masked: out.join(""), strings };
}

/** Буквенный префикс строкового литерала (`r`, `rb`, `f`, …) перед кавычкой. */
function prefixStartAt(text: string, quote: number): number {
  let start = quote;
  while (start > 0 && /[A-Za-z]/.test(text[start - 1] as string)) start--;
  // Больше трёх букв префикса в Python не бывает; иначе это конец идентификатора.
  return quote - start <= 3 ? start : quote;
}


/**
 * Логические строки маскированного текста: перенос внутри скобок и хвостовой
 * backslash продолжают строку. Отступ считается по первой физической строке.
 */
export function logicalLines(masked: string): LogicalLine[] {
  const lines: LogicalLine[] = [];
  let depth = 0;
  let start = 0;
  let indent = 0;
  let measured = false;
  for (let i = 0; i < masked.length; i++) {
    const char = masked[i] as string;
    if (!measured) {
      indent = indentAt(masked, start);
      measured = true;
    }
    if (char === "(" || char === "[" || char === "{") depth++;
    else if (char === ")" || char === "]" || char === "}") depth = Math.max(0, depth - 1);
    else if (char === "\n") {
      const continued = depth > 0 || masked.slice(start, i).trimEnd().endsWith("\\");
      if (!continued) {
        if (masked.slice(start, i).trim().length > 0) lines.push({ start, end: i, indent });
        start = i + 1;
        measured = false;
      }
    }
  }
  if (masked.slice(start).trim().length > 0) {
    lines.push({ start, end: masked.length, indent: measured ? indent : indentAt(masked, start) });
  }
  return lines;
}

function indentAt(masked: string, offset: number): number {
  let indent = 0;
  for (let i = offset; i < masked.length; i++) {
    if (masked[i] === " ") indent += 1;
    else if (masked[i] === "\t") indent += 4;
    else break;
  }
  return indent;
}


/** Регистрация ключевого слова: `registry["name"] = Class`. */
export interface Registration {
  readonly registry: string;
  /** Декодированное значение литерала — в тех же координатах, что значение JSON. */
  readonly name: string;
  /** Сырой диапазон содержимого литерала (без кавычек): цель перехода и вхождение. */
  readonly nameStart: number;
  readonly nameEnd: number;
  /** Правая часть, если это идентификатор: только для подписи варианта. */
  readonly className: string | undefined;
}

export interface PyMethod {
  readonly name: string;
  /** Вызывает ли метод одноимённый `super().<name>()`. */
  readonly callsSuper: boolean;
  readonly registrations: readonly Registration[];
}

export interface PyClass {
  readonly name: string;
  /** Позиционные базы; ключевые аргументы заголовка (`metaclass=`) базой не считаются. */
  readonly bases: readonly string[];
  readonly methods: readonly PyMethod[];
}

export interface ImportedName {
  readonly module: string;
  /** Имя в исходном модуле: `from a import B as C` даёт `C -> {a, B}`. */
  readonly name: string;
}

export interface PyModule {
  readonly classes: readonly PyClass[];
  readonly imports: ReadonlyMap<string, ImportedName>;
  /** Алиас модуля -> модуль (`import a.b as c`, `import a.b`). */
  readonly moduleImports: ReadonlyMap<string, string>;
  /** Безусловные присваивания верхнего уровня: имя -> значение как текст. */
  readonly topLevelVars: ReadonlyMap<string, string>;
  /** Имена, присвоенные не на верхнем уровне: значение таких считать нельзя. */
  readonly conditionalVars: ReadonlySet<string>;
}

const IDENT = "[A-Za-z_][A-Za-z0-9_]*";
const DOTTED = `(?:${IDENT}\\.)*${IDENT}`;
const CLASS_RE = new RegExp(`^\\s*class\\s+(${IDENT})\\s*(?:\\(([^)]*)\\))?\\s*:`);
const DEF_RE = new RegExp(`^\\s*(?:async\\s+)?def\\s+(${IDENT})\\s*\\(`);
const FROM_IMPORT_RE = new RegExp(`^\\s*from\\s+(${DOTTED})\\s+import\\s+(.+?)\\s*$`);
const IMPORT_RE = new RegExp(`^\\s*import\\s+(${DOTTED})(?:\\s+as\\s+(${IDENT}))?\\s*$`);
const ASSIGN_DOTTED_RE = new RegExp(`^\\s*(${IDENT})\\s*=\\s*(${DOTTED})\\s*$`);
const ASSIGN_ANY_RE = new RegExp(`^\\s*(${IDENT})\\s*=[^=]`);
/** Заголовок управляющей конструкции: тело может быть и на этой же строке. */
const CONTROL_RE =
  /^\s*(?:if|elif|else|for|while|try|except|finally|with|async\s+(?:for|with))\b/;
/** Присваивание где-то внутри строки — для однострочных `if dev: X = Y`. */
const INLINE_ASSIGN_RE = new RegExp(`(?:^|[^A-Za-z0-9_.])(${IDENT})\\s*=[^=]`, "g");

interface ClassDraft {
  name: string;
  bases: string[];
  methods: MethodDraft[];
}
interface MethodDraft {
  name: string;
  callsSuper: boolean;
  registrations: Registration[];
}
interface Block {
  readonly indent: number;
  readonly kind: "class" | "def" | "control";
  readonly cls?: ClassDraft;
  readonly method?: MethodDraft;
  /** Отступ тела: у `def` — отступ первой строки тела, дальше он фиксирован. */
  bodyIndent?: number;
}

/** Разбирает модуль настолько, насколько нужно для чтения ресурсов приложения. */
export function parseModule(text: string): PyModule {
  const { masked, strings } = maskPython(text);
  const classes: ClassDraft[] = [];
  const imports = new Map<string, ImportedName>();
  const moduleImports = new Map<string, string>();
  const topLevelVars = new Map<string, string>();
  const conditionalVars = new Set<string>();
  const stack: Block[] = [];

  for (const line of logicalLines(masked)) {
    while (stack.length > 0 && (stack[stack.length - 1] as Block).indent >= line.indent) stack.pop();
    const maskedLine = masked.slice(line.start, line.end);
    const rawLine = text.slice(line.start, line.end);

    const classMatch = CLASS_RE.exec(maskedLine);
    if (classMatch !== null) {
      const cls: ClassDraft = {
        name: classMatch[1] as string,
        bases: positionalBases(rawLine, classMatch),
        methods: [],
      };
      // Модель отдаёт только классы модульного уровня: вложенный класс не может
      // быть ресурсным, а одноимённый увёл бы резолвер не туда.
      if (stack.length === 0) classes.push(cls);
      stack.push({ indent: line.indent, kind: "class", cls });
      continue;
    }

    const defMatch = DEF_RE.exec(maskedLine);
    if (defMatch !== null) {
      const owner = stack[stack.length - 1];
      const method: MethodDraft = { name: defMatch[1] as string, callsSuper: false, registrations: [] };
      if (owner?.kind === "class" && owner.cls !== undefined) owner.cls.methods.push(method);
      stack.push({ indent: line.indent, kind: "def", method });
      continue;
    }

    const enclosingBlock = stack[stack.length - 1];
    // Отступ тела фиксирует первая строка внутри блока — какой бы она ни была,
    // иначе управляющий заголовок сместил бы границу тела на вложенные строки.
    if (enclosingBlock !== undefined && enclosingBlock.bodyIndent === undefined) {
      enclosingBlock.bodyIndent = line.indent;
    }

    // Однострочный suite (`if enabled: actions["x"] = C`) стоит на отступе тела,
    // но регистрация в нём условна ровно так же, как в многострочном.
    if (CONTROL_RE.test(maskedLine)) {
      for (
        let match = INLINE_ASSIGN_RE.exec(maskedLine);
        match !== null;
        match = INLINE_ASSIGN_RE.exec(maskedLine)
      ) {
        conditionalVars.add(match[1] as string);
      }
      // Блок кладётся в стек: иначе `class` внутри многострочного `if` увиделся
      // бы при пустом стеке и попал в модель как модульный, хотя его
      // существование условно.
      stack.push({ indent: line.indent, kind: "control" });
      continue;
    }

    const enclosing = enclosingBlock;
    const method = enclosing?.kind === "def" ? enclosing.method : undefined;
    // Только прямые операторы тела метода. Строка глубже — это `if`, `for`,
    // `try`, `with` или вложенный `def`: регистрация там условная, и принять её
    // значило бы обещать слово, которого в рантайме может не быть.
    const directBodyLine = enclosing !== undefined && enclosing.bodyIndent === line.indent;
    const insideResourceMethod =
      method !== undefined &&
      directBodyLine &&
      method.name.startsWith(resourceScan.methodPrefix) &&
      // Ровно «класс -> метод»: вложенный класс внутри init_* ресурсным не является.
      stack.length === 2 &&
      (stack[0] as Block).kind === "class";

    if (insideResourceMethod && method !== undefined) {
      if (superCallRe(method.name).test(maskedLine)) method.callsSuper = true;
      method.registrations.push(...registrationsIn(text, masked, strings, line));
      continue;
    }

    const fromImport = FROM_IMPORT_RE.exec(maskedLine);
    if (fromImport !== null) {
      // Список имён берётся из маскированной строки: там комментарий уже стёрт,
      // а имена — идентификаторы, маскирование их не меняет.
      const items = fromImport[2] as string;
      for (const item of items.replace(/[()]/g, "").split(",")) {
        const parts = item.trim().split(/\s+as\s+/);
        const name = (parts[0] ?? "").trim();
        if (name.length === 0 || name === "*") continue;
        imports.set((parts[1] ?? name).trim(), { module: fromImport[1] as string, name });
      }
      continue;
    }

    const moduleImport = IMPORT_RE.exec(maskedLine);
    if (moduleImport !== null) {
      const module = moduleImport[1] as string;
      moduleImports.set(moduleImport[2] ?? module, module);
      continue;
    }

    const anyAssign = ASSIGN_ANY_RE.exec(maskedLine);
    if (anyAssign === null) continue;
    const dotted = ASSIGN_DOTTED_RE.exec(maskedLine);
    if (line.indent === 0 && stack.length === 0 && dotted !== null) {
      // Последнее безусловное присваивание верхнего уровня побеждает — как в Python.
      topLevelVars.set(dotted[1] as string, dotted[2] as string);
    } else {
      // Присваивание в ветке `if`, в функции или в классе: значение статически
      // неизвестно, и полагаться на него нельзя (см. план, раздел 1).
      conditionalVars.add(anyAssign[1] as string);
    }
  }

  return { classes, imports, moduleImports, topLevelVars, conditionalVars };
}

/**
 * Позиционные базы заголовка класса. Ключевые аргументы (`metaclass=M`) и
 * распаковка базой не считаются: `class C(Base, metaclass=M)` — одна база.
 */
function positionalBases(rawLine: string, classMatch: RegExpExecArray): string[] {
  const header = classMatch[2] === undefined ? "" : rawHeader(rawLine, classMatch);
  return header
    .split(",")
    .map((part) => part.trim())
    .filter((part) => part.length > 0 && !part.includes("=") && !part.startsWith("*"));
}

function rawHeader(rawLine: string, classMatch: RegExpExecArray): string {
  const open = rawLine.indexOf("(");
  const close = rawLine.lastIndexOf(")");
  return open >= 0 && close > open ? rawLine.slice(open + 1, close) : (classMatch[2] as string);
}

/**
 * Вызов именно `super()`, а не `my_super()` или `obj.super()`: слева от имени
 * обязана быть граница — начало строки или символ, не входящий в идентификатор
 * и не точка.
 */
const superCallRe = (method: string): RegExp =>
  new RegExp(`(?:^|[^A-Za-z0-9_.])super\\s*\\([^)]*\\)\\s*\\.\\s*${method}\\s*\\(`);


/** Индекс закрывающей скобки для скобки в [open], либо -1. */
function matchBracket(masked: string, open: number): number {
  const pairs: Record<string, string> = { "(": ")", "[": "]", "{": "}" };
  const close = pairs[masked[open] as string];
  if (close === undefined) return -1;
  let depth = 0;
  for (let i = open; i < masked.length; i++) {
    const char = masked[i] as string;
    if (char === "(" || char === "[" || char === "{") depth++;
    else if (char === ")" || char === "]" || char === "}") {
      depth--;
      if (depth === 0) return char === close ? i : -1;
    }
  }
  return -1;
}

/** Единственный строковый литерал внутри [start, end), если там больше ничего нет. */
function soleString(masked: string, strings: readonly PyString[], start: number, end: number): PyString | undefined {
  const inside = strings.filter((s) => s.start >= start && s.end <= end);
  if (inside.length !== 1) return undefined;
  const only = inside[0] as PyString;
  const before = masked.slice(start, only.start).trim();
  const after = masked.slice(only.end, end).trim();
  return before.length === 0 && after.length === 0 ? only : undefined;
}

/**
 * Декодирует строковый литерал Python. `f`- и `b`-строки не поддерживаются
 * (имя вычисляется или это байты), сырые строки отдаются как есть.
 */
export function decodePyString(text: string, literal: PyString): string | undefined {
  const prefix = literal.prefix.toLowerCase();
  if (prefix.includes("f") || prefix.includes("b")) return undefined;
  const content = text.slice(literal.contentStart, literal.contentEnd);
  if (prefix.includes("r")) return content;

  let out = "";
  for (let i = 0; i < content.length; i++) {
    if (content[i] !== "\\") {
      out += content[i];
      continue;
    }
    const next = content[i + 1];
    if (next === undefined) return undefined;
    i++;
    switch (next) {
      case "n": out += "\n"; break;
      case "t": out += "\t"; break;
      case "r": out += "\r"; break;
      case "0": {
        // `\0` — NUL, но `\012` — восьмеричная последовательность, а она вне
        // контракта: принять её частично значило бы получить не то имя.
        if (/[0-7]/.test(content[i + 1] ?? "")) return undefined;
        out += "\0";
        break;
      }
      case "\\": out += "\\"; break;
      case "'": out += "'"; break;
      case '"': out += '"'; break;
      case "\n": break; // перенос строки, экранированный обратным слэшем
      case "x":
      case "u":
      case "U": {
        const width = next === "x" ? 2 : next === "u" ? 4 : 8;
        const digits = content.slice(i + 1, i + 1 + width);
        if (digits.length < width || !/^[0-9a-fA-F]+$/.test(digits)) return undefined;
        const code = parseInt(digits, 16);
        if (code > 0x10ffff) return undefined;
        out += String.fromCodePoint(code);
        i += width;
        break;
      }
      default:
        // Контракт escape сознательно узкий (см. план): всё прочее — восьмеричные
        // последовательности, \a, \b, \f, \v, \N{...} — не поддерживается.
        // Отвергаем регистрацию целиком: подставить не то имя хуже, чем не
        // подставить никакого.
        return undefined;
    }
  }
  return out;
}

/** Регистрации в одной логической строке: `registry["x"] = C` и `registry.update({...})`. */
function registrationsIn(
  text: string,
  masked: string,
  strings: readonly PyString[],
  line: LogicalLine,
): Registration[] {
  const result: Registration[] = [];
  const slice = masked.slice(line.start, line.end);

  const subscript = new RegExp(`(?:^|[^A-Za-z0-9_.])((?:${IDENT}\\.)*)(${IDENT})\\s*\\[`, "g");
  for (let match = subscript.exec(slice); match !== null; match = subscript.exec(slice)) {
    const registry = match[2] as string;
    const open = line.start + match.index + (match[0] as string).length - 1;
    const close = matchBracket(masked, open);
    if (close < 0) continue;
    // За подпиской должно стоять присваивание, а не сравнение или чтение.
    const tail = masked.slice(close + 1, line.end);
    if (!/^\s*=[^=]/.test(tail)) continue;
    const literal = soleString(masked, strings, open + 1, close);
    if (literal === undefined) continue;
    const name = decodePyString(text, literal);
    if (name === undefined) continue;
    result.push({
      registry,
      name,
      nameStart: literal.contentStart,
      nameEnd: literal.contentEnd,
      className: dottedOrUndefined(text.slice(close + 1, line.end).replace(/^\s*=\s*/, "")),
    });
  }

  const update = new RegExp(`(?:^|[^A-Za-z0-9_.])((?:${IDENT}\\.)*)(${IDENT})\\s*\\.update\\s*\\(`, "g");
  for (let match = update.exec(slice); match !== null; match = update.exec(slice)) {
    const registry = match[2] as string;
    const open = line.start + match.index + (match[0] as string).length - 1;
    const close = matchBracket(masked, open);
    if (close < 0) continue;
    const braceOpen = masked.indexOf("{", open);
    if (braceOpen < 0 || braceOpen > close || masked.slice(open + 1, braceOpen).trim().length > 0) {
      // Аргумент не словарный литерал: имена вычисляются, поддержать нечем.
      continue;
    }
    const braceClose = matchBracket(masked, braceOpen);
    if (braceClose < 0) continue;
    for (const literal of strings) {
      if (literal.start < braceOpen || literal.end > braceClose) continue;
      // Только непосредственные ключи внешнего словаря: строка внутри
      // вложенного объекта — не имя ключевого слова.
      if (bracketDepthBetween(masked, braceOpen + 1, literal.start) !== 0) continue;
      const after = masked.slice(literal.end, braceClose);
      if (!after.trimStart().startsWith(":")) continue;
      const name = decodePyString(text, literal);
      if (name === undefined) continue;
      const valueText = text.slice(literal.end, braceClose).replace(/^\s*:\s*/, "");
      result.push({
        registry,
        name,
        nameStart: literal.contentStart,
        nameEnd: literal.contentEnd,
        className: dottedOrUndefined(valueText.split(",")[0] ?? ""),
      });
    }
  }
  return result;
}

/** Глубина вложенности скобок на участке [start, end) — 0 значит «верхний уровень». */
function bracketDepthBetween(masked: string, start: number, end: number): number {
  let depth = 0;
  for (let i = start; i < end; i++) {
    const char = masked[i] as string;
    if (char === "(" || char === "[" || char === "{") depth++;
    else if (char === ")" || char === "]" || char === "}") depth--;
  }
  return depth;
}

/** Точечное имя из текста значения, если это оно; иначе подписи не будет. */
function dottedOrUndefined(value: string): string | undefined {
  const trimmed = value.trim().replace(/[,}]+$/, "").trim();
  return new RegExp(`^${DOTTED}$`).test(trimmed) ? trimmed : undefined;
}
