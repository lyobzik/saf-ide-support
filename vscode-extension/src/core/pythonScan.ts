import { resourceScan, userModel } from "./contract";

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
  /**
   * Разбор словаря параметров — только у метода параметризатора.
   * `undefined` — метод не тот либо правило `<d>` нарушено.
   */
  readonly dictionary?: DictionaryUse;
}

/** Запись в словарь параметров шаблона: ключ и правая часть. */
export interface DictionaryBinding {
  /** Декодированный ключ — в тех же координатах, что значение JSON. */
  readonly key: string;
  /** Значение, если распознано как точечное имя; иначе `undefined` («не знаем»). */
  readonly value: string | undefined;
  /** Сырой диапазон содержимого ключа: цель перехода и вхождение. */
  readonly nameStart: number;
  readonly nameEnd: number;
}

/**
 * Разбор тела метода вокруг словаря `<d>`, который метод возвращает.
 *
 * Существует, только когда правило `<d>` выполнено целиком (план, раздел 3):
 * ровно один `return` барного имени последним оператором прямого тела, ровно
 * одна инициализация, все прочие упоминания `<d>` — записи поддержанной формы
 * на прямом уровне, и ни одного гасителя в теле. `undefined` — правило
 * нарушено, и привязки этого метода считать нельзя: молчаливое «почти
 * доказательство» здесь дало бы WARNING на несуществующем корне.
 */
export interface DictionaryUse {
  readonly name: string;
  /** Инициализация `<d> = super().<метод>(…)`: привязки базы наследуются. */
  readonly inheritsBase: boolean;
  /** Записи по возрастанию смещения: при повторе ключа побеждает последняя. */
  readonly bindings: readonly DictionaryBinding[];
}

/** Откуда взялось имя атрибута модели пользователя. */
export type DeclarationOrigin = "field" | "self" | "def" | "class";

/**
 * Объявление имени в классе: имя и **сырой** диапазон для перехода.
 *
 * Диапазон сырой, а имя декодированное — то же правило, что у регистраций:
 * имя сравнивается со значением JSON, а диапазон служит целью навигации.
 */
export interface PyDeclaration {
  readonly name: string;
  readonly nameStart: number;
  readonly nameEnd: number;
  readonly origin: DeclarationOrigin;
}

/**
 * Состояние свойства `fields` — четыре, и различать надо все четыре.
 *
 * `absent` — свойство унаследовано как есть; `withSuper` — база сохранена;
 * `withoutSuper` — база отброшена; `unparsed` — форму не разобрали, и тогда
 * поля базы сохраняются, а диагностика гасится: молча потерять пол хуже, чем
 * предложить лишнее.
 */
export type FieldsState = "absent" | "withSuper" | "withoutSuper" | "unparsed";

export interface PyClass {
  readonly name: string;
  /** Позиционные базы; ключевые аргументы заголовка (`metaclass=`) базой не считаются. */
  readonly bases: readonly string[];
  readonly methods: readonly PyMethod[];
  /** Сырой диапазон имени класса — цель перехода с корневой переменной. */
  readonly nameStart: number;
  readonly nameEnd: number;
  /** Имена атрибутов модели пользователя со ссылками на места объявления. */
  readonly declarations: readonly PyDeclaration[];
  readonly fieldsState: FieldsState;
  /** Сработавшие гасители диагностики из контракта. */
  readonly blockers: ReadonlySet<string>;
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

/**
 * Идентификатор Python — та же грамматика, что у `identifiers.ts`: буква или
 * `_` в начале, плюс десятичная цифра дальше. ASCII-приближение теряло бы
 * `self.я`, `def имя` и `class Пользователь`, хотя фильтр имён их принимает.
 *
 * Символы вне BMP этот шаблон матчит (флаг `u` работает по code point'ам), а
 * `isAddressableName` — нет. Расхождение безвредно: сканер только находит
 * имена, а годность решает фильтр, и он строже.
 */
const IDENT = "[\\p{L}_][\\p{L}\\p{Nd}_]*";
const DOTTED = `(?:${IDENT}\\.)*${IDENT}`;
const CLASS_RE = new RegExp(`^\\s*class\\s+(${IDENT})\\s*(?:\\(([^)]*)\\))?\\s*:`, "u");
const DEF_RE = new RegExp(`^\\s*(?:async\\s+)?def\\s+(${IDENT})\\s*\\(`, "u");
const FROM_IMPORT_RE = new RegExp(`^\\s*from\\s+(${DOTTED})\\s+import\\s+(.+?)\\s*$`, "u");
const IMPORT_RE = new RegExp(`^\\s*import\\s+(${DOTTED})(?:\\s+as\\s+(${IDENT}))?\\s*$`, "u");
const ASSIGN_DOTTED_RE = new RegExp(`^\\s*(${IDENT})\\s*=\\s*(${DOTTED})\\s*$`, "u");
const ASSIGN_ANY_RE = new RegExp(`^\\s*(${IDENT})\\s*=[^=]`, "u");
/**
 * Конец ключевого слова. Записан лоокэхедом, а не `\b`: словом `\b` считает
 * только ASCII, поэтому в `ifя` (законное имя по грамматике идентификаторов)
 * граница нашлась бы сразу после `if`, и объявление уехало бы в управляющие
 * конструкции.
 */
const KEYWORD_END = "(?![\\p{L}\\p{Nd}_])";
/** Заголовок управляющей конструкции: тело может быть и на этой же строке. */
const CONTROL_RE = new RegExp(
  `^\\s*(?:if|elif|else|for|while|try|except|finally|with|async\\s+(?:for|with))${KEYWORD_END}`,
  "u",
);
/**
 * `match` и `case` — **мягкие** ключевые слова: `match = 1` и `case: int = 2`
 * законные присваивания. Отличает заголовок не хвостовое двоеточие, а то, что
 * стоит за словом (см. [isSoftControlHeader]).
 */
const SOFT_CONTROL_RE = new RegExp(`^\\s*(?:match|case)${KEYWORD_END}`, "u");
/** Присваивание где-то внутри строки — для однострочных `if dev: X = Y`. */
const INLINE_ASSIGN_RE = new RegExp(`(?:^|[^\\p{L}\\p{Nd}_.])(${IDENT})\\s*=[^=]`, "gu");
/** Строка-декоратор: `@property`, `@dataclass(...)`. */
const DECORATOR_RE = /^\s*@/;
/**
 * `self.x = …` и `self.x: T = …`. Аннотация между именем и `=` пропускается:
 * значение создаёт атрибут независимо от неё, а голая аннотация (без `=`) не
 * создаёт ничего и потому не матчится.
 */
const SELF_ASSIGN_RE = new RegExp(
  `(?:^|[^\\p{L}\\p{Nd}_.])self\\.(${IDENT})\\s*(?::[^=\\n]+)?=(?!=)`,
  "gu",
);
/** Имя уровня класса **со значением**: `X = …`, `X: T = …`. Заякорена в начало. */
const CLASS_LEVEL_ASSIGN_RE = new RegExp(`^\\s*(${IDENT})\\s*(?::[^=\\n]+)?=(?!=)`, "u");

interface ClassDraft {
  name: string;
  bases: string[];
  methods: MethodDraft[];
  nameStart: number;
  nameEnd: number;
  declarations: PyDeclaration[];
  fieldsState: FieldsState;
  blockers: Set<string>;
  /** Логические строки прямого тела свойства `fields` — разбираются в конце. */
  fieldsBody: LogicalLine[];
  hasInit: boolean;
  initCallsSuper: boolean;
}
interface MethodDraft {
  name: string;
  callsSuper: boolean;
  registrations: Registration[];
  /**
   * Логические строки тела — с любой глубины и только у метода параметризатора:
   * правило `<d>` формулируется вокруг всего тела, а не вокруг прямых
   * операторов, поэтому разобрать его построчно на лету нельзя.
   */
  bodyLines: BodyLine[];
  /** Есть ли декоратор на самом методе: он способен подменить результат. */
  decorated: boolean;
  dictionary?: DictionaryUse;
}

/** Строка тела метода: `direct` — прямой уровень тела, а не глубже. */
interface BodyLine {
  readonly start: number;
  readonly end: number;
  readonly direct: boolean;
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
  // Декоратор стоит на строке перед `class`/`def`; на самом классе он означает
  // произвольную подмену поведения, которую сканер отследить не может.
  let decoratedIndent: number | undefined;

  for (const line of logicalLines(masked)) {
    while (stack.length > 0 && (stack[stack.length - 1] as Block).indent >= line.indent) stack.pop();
    const maskedLine = masked.slice(line.start, line.end);
    const rawLine = text.slice(line.start, line.end);

    // Отступ тела фиксирует первая строка внутри блока — какой бы она ни была,
    // иначе управляющий заголовок сместил бы границу тела на вложенные строки.
    // Считается до разбора строки: `class`/`def`/декоратор тоже строки тела, а
    // записи тела параметризатора нужен признак «прямой уровень» уже здесь.
    const openBlock = stack[stack.length - 1];
    if (openBlock !== undefined && openBlock.bodyIndent === undefined) {
      openBlock.bodyIndent = line.indent;
    }
    collectBodyLine(stack, line);

    // Класс верхнего уровня, внутри которого идёт строка: только его имена
    // собираются в модель пользователя.
    // Второй class-блок в стеке — вложенный класс: его имена, self-атрибуты и
    // гасители к внешнему отношения не имеют, и сбор для внешнего прекращается.
    const nested = stack.some((block, index) => index > 0 && block.kind === "class");
    const ownerClass = stack[0]?.kind === "class" && !nested ? stack[0].cls : undefined;
    if (ownerClass !== undefined) {
      collectBlockerTokens(maskedLine, ownerClass.blockers);
      // `self.x` собирается с **любой** глубины: условное присваивание всё
      // равно создаёт атрибут в той ветке, где выполнится, и потерять имя
      // хуже, чем предложить лишнее (over-approximation объявлена контрактом).
      if (stack.some((block) => block.kind === "def")) {
        collectMatches(SELF_ASSIGN_RE, maskedLine, line.start, "self", ownerClass.declarations);
      } else {
        // Однострочный suite (`if flag: LIMIT = 5`) — тоже объявление уровня
        // класса, просто со сдвигом: разбор начинается после двоеточия. Без
        // этого условное `fields = […]` терялось молча, вместе с гасителем.
        collectClassLevelBody(ownerClass, maskedLine, line, suiteBodyStart(maskedLine));
      }
    }

    if (DECORATOR_RE.test(maskedLine)) {
      decoratedIndent = line.indent;
      continue;
    }
    const decorated = decoratedIndent === line.indent;
    decoratedIndent = undefined;

    const classMatch = CLASS_RE.exec(maskedLine);
    if (classMatch !== null) {
      const nameStart = line.start + maskedLine.indexOf(classMatch[1] as string, 5);
      const cls: ClassDraft = {
        name: classMatch[1] as string,
        bases: positionalBases(rawLine, classMatch),
        methods: [],
        nameStart,
        nameEnd: nameStart + (classMatch[1] as string).length,
        declarations: [],
        fieldsState: "absent",
        blockers: new Set<string>(),
        fieldsBody: [],
        hasInit: false,
        initCallsSuper: false,
      };
      if (decorated) cls.blockers.add("classDecorator");
      if (/\bmetaclass\s*=/.test(rawLine)) cls.blockers.add("metaclassInBases");
      // Модель отдаёт только классы модульного уровня: вложенный класс не может
      // быть ресурсным, а одноимённый увёл бы резолвер не туда.
      if (stack.length === 0) classes.push(cls);
      stack.push({ indent: line.indent, kind: "class", cls });
      // Тело на строке заголовка (`class C(B): fields = […]`) — это тело
      // класса: сбор имён уровня класса до сюда не дошёл (строка ещё не была
      // внутри класса), и без разбора здесь и имена, и гасители пропали бы.
      const inlineBody = bodyAfterColon(maskedLine);
      if (inlineBody > 0) {
        collectBlockerTokens(maskedLine.slice(inlineBody), cls.blockers);
        collectClassLevelBody(cls, maskedLine, line, inlineBody);
      }
      continue;
    }

    const defMatch = DEF_RE.exec(maskedLine);
    if (defMatch !== null) {
      const owner = stack[stack.length - 1];
      const name = defMatch[1] as string;
      const method: MethodDraft = {
        name,
        callsSuper: false,
        registrations: [],
        bodyLines: [],
        decorated,
      };
      // Метод класса верхнего уровня бывает двух видов: прямой — владелец сам
      // класс, и условный — между классом и `def` стоит управляющий блок.
      // Вложенный `def` внутри метода методом класса не является вовсе.
      const inOwnerClass =
        ownerClass !== undefined && stack.every((block) => block.kind !== "def");
      const directMethod = inOwnerClass && owner?.kind === "class";
      // Однострочное тело (`def __init__(self): self.x = 1`) — это тело метода,
      // просто на строке заголовка: ветка `def` завершает обработку, и без
      // отдельного разбора и объявление, и `super()` терялись бы.
      // Тело `def` разбирается своим вызовом, а не через `suiteBodyStart`: тот
      // отвечает за управляющие конструкции и используется ещё и сбором имён
      // уровня класса, где локальная переменная однострочного метода стала бы
      // атрибутом класса.
      const suite = bodyAfterColon(maskedLine);
      if (suite > 0) {
        if (callsSuperOnLine(maskedLine.slice(suite), name)) method.callsSuper = true;
        if (inOwnerClass && ownerClass !== undefined) {
          collectMatches(
            SELF_ASSIGN_RE,
            maskedLine.slice(suite),
            line.start + suite,
            "self",
            ownerClass.declarations,
          );
        }
        // Тело свойства `fields` на строке заголовка разбирается тем же
        // разбором, что и многострочное: форма здесь ничем не отличается, и
        // терять её поля незачем. Условный `def` сюда не идёт — его форму
        // нельзя считать действующей (см. `conditionalDef`).
        // Тело параметризатора на строке заголовка: в стек метод ещё не
        // положен, поэтому строку записывает сама ветка `def`.
        if (directMethod && name === userModel.parametrizerMethod) {
          method.bodyLines.push({ start: line.start + suite, end: line.end, direct: true });
        }
        if (directMethod && ownerClass !== undefined && name === userModel.fieldsProperty) {
          ownerClass.fieldsBody.push({
            start: line.start + suite,
            end: line.end,
            indent: line.indent,
          });
        }
      }
      if (owner?.kind === "class" && owner.cls !== undefined) owner.cls.methods.push(method);
      if (inOwnerClass && ownerClass !== undefined) {
        const nameStart = line.start + maskedLine.indexOf(name, defMatch.index);
        ownerClass.declarations.push({
          name,
          nameStart,
          nameEnd: nameStart + name.length,
          origin: "def",
        });
        if (directMethod) {
          if (name === "__init__") ownerClass.hasInit = true;
          else if (name.startsWith("__") && name.endsWith("__")) {
            ownerClass.blockers.add("dunderDefExceptInit");
          }
        } else {
          // Условное определение метода. Считать его обычным нельзя: условное
          // `fields` свернуло бы поля базы, а условный `__init__` без
          // `super()` — наоборот, объявил бы класс грязным. Но и молчать
          // нельзя: класс тогда выглядит чистым, хотя переопределение в нём
          // есть. Имя предлагается, право утверждать теряется.
          ownerClass.blockers.add("conditionalDef");
        }
      }
      stack.push({ indent: line.indent, kind: "def", method });
      continue;
    }

    const enclosingBlock = stack[stack.length - 1];

    // Однострочный suite (`if enabled: actions["x"] = C`) стоит на отступе тела,
    // но регистрация в нём условна ровно так же, как в многострочном.
    if (isControlHeader(maskedLine)) {
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
    // Признак нужен и ресурсам (`init_*`), и модели пользователя (`__init__`),
    // поэтому считается для любого метода, а не только внутри ресурсной ветки.
    if (method !== undefined && callsSuperOnLine(maskedLine, method.name)) {
      method.callsSuper = true;
    }
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

    // Прямое тело свойства `fields` класса верхнего уровня: разбирается целиком
    // после обхода, потому что состояние определяется формой всего тела.
    if (
      method !== undefined &&
      directBodyLine &&
      method.name === userModel.fieldsProperty &&
      stack.length === 2 &&
      (stack[0] as Block).cls === ownerClass &&
      ownerClass !== undefined
    ) {
      ownerClass.fieldsBody.push(line);
    }

    if (insideResourceMethod && method !== undefined) {
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

  for (const cls of classes) {
    finishUserModel(text, masked, strings, cls);
    finishParametrizer(text, masked, strings, cls);
  }

  return { classes, imports, moduleImports, topLevelVars, conditionalVars };
}

/**
 * Позиционные базы заголовка класса. Ключевые аргументы (`metaclass=M`) и
 * распаковка базой не считаются: `class C(Base, metaclass=M)` — одна база.
 */
/** Токены-гасители из контракта по маскированной строке. */
function collectBlockerTokens(maskedLine: string, out: Set<string>): void {
  // Маскированной: упоминание в docstring или комментарии гасителем не является.
  for (const token of userModel.blockerTokens) {
    if (maskedLine.includes(token)) out.add(token);
  }
}

/**
 * Записывает строку в тело метода параметризатора.
 *
 * Только прямой метод класса верхнего уровня: правило `<d>` — про этот метод, а
 * не про любой одноимённый. Строки берутся с любой глубины: упоминание `<d>`
 * внутри `if` отменяет доказательство, и увидеть его можно, только собрав тело
 * целиком.
 */
function collectBodyLine(stack: readonly Block[], line: LogicalLine): void {
  const owner = stack[0];
  const block = stack[1];
  if (owner?.kind !== "class" || block?.kind !== "def") return;
  const method = block.method;
  if (method === undefined || method.name !== userModel.parametrizerMethod) return;
  method.bodyLines.push({
    start: line.start,
    end: line.end,
    // Прямой уровень — когда объемлющий блок и есть сам метод.
    direct: stack.length === 2 && block.bodyIndent === line.indent,
  });
}

/** Разбирает словарь параметров у методов параметризатора класса. */
function finishParametrizer(
  text: string,
  masked: string,
  strings: readonly PyString[],
  cls: ClassDraft,
): void {
  for (const method of cls.methods) {
    if (method.name !== userModel.parametrizerMethod) continue;
    // Декоратор на самом методе подменяет его результат целиком: тело может
    // собирать словарь как угодно, а в шаблон уедет то, что вернул декоратор.
    // Для свойства `fields` декоратор наоборот обязателен (`@property` — способ
    // объявления во фреймворке), поэтому запрет только здесь.
    if (method.decorated) continue;
    method.dictionary = dictionaryUse(text, masked, strings, method.bodyLines);
  }
}

/** Оператор тела: `direct` — прямой уровень, не глубже и не в однострочном suite. */
interface BodyStatement {
  readonly text: string;
  readonly at: number;
  readonly direct: boolean;
}

const RETURN_RE = new RegExp(`^\\s*return${KEYWORD_END}`, "u");
const RETURN_NAME_RE = new RegExp(`^\\s*return\\s+(${IDENT})\\s*$`, "u");
/** `dict()` — инициализация пустым словарём наравне с `{}`. */
const EMPTY_DICT_CALL_RE = /^dict\s*\(\s*\)$/;

/**
 * Инициализация словаря вызовом базы — **только** `super()` без аргументов.
 *
 * Аргументированный `super(Base, self)` начинает поиск по MRO **после** `Base`,
 * то есть одноимённый метод самой `Base` не вызывает вовсе: засчитать по нему
 * наследование значило бы приписать словарю привязки, которых в нём нет. Здесь
 * это строже, чем в [isBareSuperCall] (там форма служит признаком «база
 * вызвана», и ошибка ведёт к отбрасыванию слов, а не к ложному утверждению).
 */
const SUPER_DICT_RE = new RegExp(
  `^super\\s*\\(\\s*\\)\\s*\\.\\s*${userModel.parametrizerMethod}\\s*\\(`,
  "u",
);

/** Вызов `super().<метод>(…)`, занимающий всё выражение целиком. */
function initializesFromSuper(value: string): boolean {
  const match = SUPER_DICT_RE.exec(value);
  if (match === null) return false;
  const open = (match[0] as string).length - 1;
  const close = matchBracket(value, open);
  return close >= 0 && value.slice(close + 1).trim().length === 0;
}

/** Упоминание имени по маскированному тексту; точка слева границей не считается. */
function mentionRe(name: string): RegExp {
  return new RegExp(`(?<![\\p{L}\\p{Nd}_])${name}(?![\\p{L}\\p{Nd}_])`, "u");
}

/** Есть ли в строке хоть один контрактный токен-гаситель. */
function hasBlockerToken(maskedLine: string): boolean {
  for (const token of userModel.blockerTokens) {
    if (maskedLine.includes(token)) return true;
  }
  return false;
}

/**
 * Тело метода как плоский список операторов в порядке документа.
 *
 * Заголовок управляющей конструкции, `def`, `class` и декоратор прямыми
 * операторами не считаются: запись в них либо условна, либо принадлежит другому
 * телу. Пустые операторы (хвостовая `;`) отбрасываются — смысла они не несут.
 */
function bodyStatements(masked: string, body: readonly BodyLine[]): BodyStatement[] {
  const result: BodyStatement[] = [];
  for (const line of body) {
    const maskedLine = masked.slice(line.start, line.end);
    const direct =
      line.direct &&
      !isControlHeader(maskedLine) &&
      !DEF_RE.test(maskedLine) &&
      !CLASS_RE.test(maskedLine) &&
      !DECORATOR_RE.test(maskedLine);
    for (const statement of simpleStatements(maskedLine, 0)) {
      const text = maskedLine.slice(statement.start, statement.end);
      if (text.trim().length === 0) continue;
      result.push({ text, at: line.start + statement.start, direct });
    }
  }
  return result;
}

/**
 * Имя словаря `<d>` из `return <d>`.
 *
 * `return` обязан быть единственным во всём теле, прямым оператором, последним
 * оператором тела и возвращать барное имя. Без «последнего» запись после
 * `return` — мёртвый код, который никогда не выполнится, — засчиталась бы за
 * привязку.
 */
function returnedDictionary(
  statements: readonly BodyStatement[],
): { statement: BodyStatement; name: string } | undefined {
  const returns = statements.filter((statement) => RETURN_RE.test(statement.text));
  const only = returns[0];
  if (returns.length !== 1 || only === undefined) return undefined;
  if (only !== statements[statements.length - 1] || !only.direct) return undefined;
  const name = RETURN_NAME_RE.exec(only.text)?.[1];
  return name === undefined ? undefined : { statement: only, name };
}

/**
 * Разбор тела метода параметризатора по правилу `<d>` (план, раздел 3).
 *
 * `undefined` — правило нарушено; привязки такого метода ненадёжны целиком, а
 * не частично: любое упоминание `<d>` вне трёх поддержанных форм означает, что
 * содержимое словаря нам неизвестно.
 */
function dictionaryUse(
  text: string,
  masked: string,
  strings: readonly PyString[],
  body: readonly BodyLine[],
): DictionaryUse | undefined {
  const statements = bodyStatements(masked, body);
  const returned = returnedDictionary(statements);
  if (returned === undefined) return undefined;
  const name = returned.name;
  // Гаситель где угодно в теле меняет содержимое в обход имени, и никакое
  // правило про упоминания его не поймает.
  for (const line of body) {
    if (hasBlockerToken(masked.slice(line.start, line.end))) return undefined;
  }

  const mention = mentionRe(name);
  const bindings: DictionaryBinding[] = [];
  let inheritsBase = false;
  let initialized = false;

  for (const statement of statements) {
    // Сам `return <d>` — по тождеству, а не «последний оператор»: иначе при
    // ослаблении требования «последний» пропускался бы мёртвый код после него.
    if (statement === returned.statement) continue;
    if (!mention.test(statement.text)) continue; // строка без `<d>` словарь не меняет
    if (!statement.direct) return undefined;

    const init = initializationOf(text, masked, strings, statement, name);
    if (init !== undefined) {
      if (initialized) return undefined; // вторая инициализация
      initialized = true;
      inheritsBase = init.inheritsBase;
      bindings.push(...init.bindings);
      continue;
    }
    const written = writtenBindings(text, masked, strings, statement, name);
    if (written === undefined) return undefined;
    bindings.push(...written);
  }
  return initialized ? { name, inheritsBase, bindings } : undefined;
}

interface Initialization {
  readonly inheritsBase: boolean;
  readonly bindings: DictionaryBinding[];
}

/**
 * Инициализация `<d>`: `super().<метод>(…)`, `{}`, `dict()` или словарный
 * литерал с ключами. Аннотация между именем и `=` пропускается — семантики она
 * не меняет, а разойтись в её разборе две реализации не должны.
 */
function initializationOf(
  text: string,
  masked: string,
  strings: readonly PyString[],
  statement: BodyStatement,
  name: string,
): Initialization | undefined {
  const head = new RegExp(`^\\s*${name}\\s*(?::[^=\\n]+)?=(?!=)`, "u").exec(statement.text);
  if (head === null) return undefined;
  const consumed = (head[0] as string).length;
  const tail = statement.text.slice(consumed);
  const offset = consumed + (tail.length - tail.trimStart().length);
  const value = statement.text.slice(offset).trim();
  const at = statement.at + offset;

  // Наследование даёт только эта форма: отдельный вызов-оператор словарь не
  // инициализирует, и засчитывать его значило бы унаследовать привязки,
  // которых в `<d>` нет, — то есть ложное доказательство.
  if (initializesFromSuper(value)) {
    return { inheritsBase: true, bindings: [] };
  }
  if (EMPTY_DICT_CALL_RE.test(value)) return { inheritsBase: false, bindings: [] };
  if (!value.startsWith("{")) return undefined;
  const close = matchBracket(masked, at);
  if (close !== at + value.length - 1) return undefined;
  const pairs = dictLiteralPairs(text, masked, strings, at, close);
  return pairs === undefined ? undefined : { inheritsBase: false, bindings: pairs };
}

/**
 * Запись в `<d>`: `<d>["ключ"] = <значение>` либо `<d>.update({ … })`.
 *
 * Имя обязано быть барным и совпадать с `<d>`: точечный префикс сделал бы
 * источником корня любой посторонний словарь (`other.data["user"] = …`).
 */
function writtenBindings(
  text: string,
  masked: string,
  strings: readonly PyString[],
  statement: BodyStatement,
  name: string,
): DictionaryBinding[] | undefined {
  const subscript = new RegExp(`^\\s*${name}\\s*\\[`, "u").exec(statement.text);
  if (subscript !== null) {
    const open = statement.at + (subscript[0] as string).length - 1;
    const close = matchBracket(masked, open);
    if (close < 0) return undefined;
    // За подпиской обязано стоять присваивание, а не чтение или сравнение.
    const tail = statement.text.slice(close - statement.at + 1);
    const assign = /^\s*=(?!=)/.exec(tail);
    if (assign === null) return undefined;
    const key = keyAt(text, masked, strings, open + 1, close);
    if (key === undefined) return undefined;
    return [{ ...key, value: dottedOrUndefined(tail.slice((assign[0] as string).length)) }];
  }

  const update = new RegExp(`^\\s*${name}\\s*\\.\\s*update\\s*\\(`, "u").exec(statement.text);
  if (update === null) return undefined;
  const open = statement.at + (update[0] as string).length - 1;
  const close = matchBracket(masked, open);
  if (close < 0 || statement.text.slice(close - statement.at + 1).trim().length !== 0) {
    return undefined;
  }
  // Ровно один аргумент, и он словарный литерал: второй аргумент и именованные
  // (`dict.update` имеет сигнатуру `update(m, **kwargs)`) молча переопределяют
  // ключ, поэтому привязкой такая форма считаться не может.
  const inside = masked.slice(open + 1, close);
  const argument = inside.trim();
  if (!argument.startsWith("{")) return undefined;
  const braceOpen = open + 1 + inside.indexOf("{");
  const braceClose = matchBracket(masked, braceOpen);
  if (braceClose !== braceOpen + argument.length - 1) return undefined;
  return dictLiteralPairs(text, masked, strings, braceOpen, braceClose);
}

/**
 * Пары словарного литерала. `undefined` — среди элементов есть тот, что не
 * является парой «строковый ключ: значение»: распаковка `**other`, вычисляемый
 * ключ, comprehension. Такой литерал может переопределить любой ключ, и считать
 * его привязкой нельзя.
 */
function dictLiteralPairs(
  text: string,
  masked: string,
  strings: readonly PyString[],
  braceOpen: number,
  braceClose: number,
): DictionaryBinding[] | undefined {
  const result: DictionaryBinding[] = [];
  for (const element of depthZeroParts(masked, braceOpen + 1, braceClose, ",")) {
    if (masked.slice(element.start, element.end).trim().length === 0) continue;
    const colon = depthZeroIndex(masked, element.start, element.end, ":");
    if (colon < 0) return undefined;
    const key = keyAt(text, masked, strings, element.start, colon);
    if (key === undefined) return undefined;
    // По маскированному: хвостовой комментарий стёрт, а строковое значение
    // заполнителем точечным именем не станет. То же и в ветке подписки.
    result.push({ ...key, value: dottedOrUndefined(masked.slice(colon + 1, element.end)) });
  }
  return result;
}

/** Ключ-литерал в диапазоне: декодированное имя и сырой диапазон содержимого. */
function keyAt(
  text: string,
  masked: string,
  strings: readonly PyString[],
  start: number,
  end: number,
): { key: string; nameStart: number; nameEnd: number } | undefined {
  const literal = soleString(masked, strings, start, end);
  if (literal === undefined) return undefined;
  const key = decodePyString(text, literal);
  if (key === undefined) return undefined;
  return { key, nameStart: literal.contentStart, nameEnd: literal.contentEnd };
}

/** Части диапазона, разделённые [separator] на нулевой глубине скобок. */
function depthZeroParts(
  masked: string,
  start: number,
  end: number,
  separator: string,
): Array<{ start: number; end: number }> {
  const result: Array<{ start: number; end: number }> = [];
  let depth = 0;
  let from = start;
  for (let i = start; i < end; i++) {
    const char = masked[i];
    if (char === "(" || char === "[" || char === "{") depth++;
    else if (char === ")" || char === "]" || char === "}") depth--;
    else if (char === separator && depth === 0) {
      result.push({ start: from, end: i });
      from = i + 1;
    }
  }
  result.push({ start: from, end });
  return result;
}

/** Индекс [char] на нулевой глубине скобок внутри диапазона, либо -1. */
function depthZeroIndex(masked: string, start: number, end: number, char: string): number {
  const parts = depthZeroParts(masked, start, end, char);
  const first = parts[0];
  return parts.length === 1 || first === undefined ? -1 : first.end;
}

/**
 * Имена уровня класса, объявленные начиная с [from].
 *
 * Каждый простой оператор разбирается отдельно: у `if flag: pass; fields = […]`
 * всё после первого оператора иначе пропадает, а вместе с ним и гаситель.
 *
 * `fields = […]` переопределяет свойство и тоже содержит литералы полей: форму
 * разобрать нельзя (это не тело метода), но имена извлекаются — как из любой
 * другой неразобранной формы.
 */
function collectClassLevelBody(
  cls: ClassDraft,
  maskedLine: string,
  line: LogicalLine,
  from: number,
): void {
  const before = cls.declarations.length;
  for (const statement of simpleStatements(maskedLine, from)) {
    collectClassLevelChain(
      maskedLine,
      line.start,
      cls.declarations,
      statement.start,
      statement.end,
    );
  }
  const assignsFields = cls.declarations
    .slice(before)
    .some((declaration) => declaration.name === userModel.fieldsProperty);
  if (assignsFields) cls.fieldsBody.push(line);
}

/**
 * Цепочка целей присваивания уровня класса: `a = b = 1` создаёт **два**
 * атрибута. Каждый следующий ищется в остатке строки после предыдущего `=`;
 * глобальная регулярка здесь не годится — она съедала бы `=`, нужный
 * следующему совпадению.
 */
function collectClassLevelChain(
  maskedLine: string,
  offset: number,
  out: PyDeclaration[],
  from = 0,
  to = maskedLine.length,
): void {
  let position = from;
  for (;;) {
    const rest = maskedLine.slice(position, to);
    const match = CLASS_LEVEL_ASSIGN_RE.exec(rest);
    if (match === null) return;
    const name = match[1] as string;
    const start = offset + position + rest.indexOf(name, match.index);
    out.push({ name, nameStart: start, nameEnd: start + name.length, origin: "class" });
    position += match.index + match[0].length;
  }
}

/**
 * Вызов `super().<method>(...)` **самостоятельным оператором** строки.
 *
 * Одного вхождения мало: `super().__init__() if flag else None`,
 * `flag and super().__init__()` и `x = super().__init__()` — условные или
 * вложенные вызовы, и засчитывать их за доказательство нельзя. Требуется, чтобы
 * строка целиком состояла из вызова: он начинается с `super` и на нём же
 * кончается.
 */
function isBareSuperCall(maskedLine: string, method: string): boolean {
  const trimmed = maskedLine.trim();
  const match = superCallRe(method).exec(trimmed);
  if (match === null || match.index !== 0) return false;
  const open = match[0].length - 1;
  const close = matchBracket(trimmed, open);
  return close >= 0 && trimmed.slice(close + 1).trim().length === 0;
}

/**
 * Заголовок управляющей конструкции, включая `match`/`case`.
 *
 * Без `match` класс, объявленный в его ветке, попадал бы в модель как
 * модульный: блок не кладётся в стек, и `stack.isEmpty()` оказывается истиной —
 * ровно та ошибка, от которой уже защищён `if`.
 */
function isControlHeader(maskedLine: string): boolean {
  return CONTROL_RE.test(maskedLine) || isSoftControlHeader(maskedLine);
}

/**
 * Символы, которыми подлежащее `match`/`case` начаться не может: за ними стоит
 * продолжение выражения, то есть мягкое слово здесь — обычное имя.
 * `match.foo: int = 1`, `match .foo: int = 1`, `match = 1`, `case: int = 2`,
 * `match, x = f()` — присваивания, и заголовком их считать нельзя: строка
 * получала бы «тело» ` int = 1` и приписывала классу атрибут `int`.
 *
 * Трейлеры `[` и `(` в список не входят намеренно: `match [1, 2]:` — законный
 * заголовок, а `match[0]: int = 1` — законное присваивание, и по одной строке
 * они не различаются (Python различает их по тому, идёт ли дальше блок `case`).
 * Выбран заголовок: это направление отказа безопасное — содержимое блока
 * становится условным, а не наоборот.
 */
const EXPRESSION_TAIL = new Set([".", "=", ":", ",", ";"]);

/**
 * Заголовок `match`/`case`: за мягким словом стоит начало подлежащего, а сам
 * заголовок закрыт двоеточием нулевой глубины.
 *
 * Хвостового двоеточия для признака мало: `case "x": USER = C` — заголовок с
 * телом на той же строке, и без него присваивание в ветке `case` считалось бы
 * безусловным, то есть редактор утверждал бы про `USER` то, чего не знает.
 * Проверять же одно двоеточие нельзя: `match = {1: 2}` и `case: int = 2` —
 * присваивания, поэтому символ сразу за словом решает раньше.
 */
function isSoftControlHeader(maskedLine: string): boolean {
  const match = SOFT_CONTROL_RE.exec(maskedLine);
  if (match === null) return false;
  const next = maskedLine.slice((match[0] as string).length).trimStart()[0];
  if (next === undefined || EXPRESSION_TAIL.has(next)) return false;
  return colonAt(maskedLine) >= 0;
}

/**
 * Начало тела однострочного suite: позиция за двоеточием заголовка
 * (`if flag: X = 1`). `0` — строка не управляющая либо тела на ней нет, и
 * разбор идёт с начала.
 */
function suiteBodyStart(maskedLine: string): number {
  return isControlHeader(maskedLine) ? bodyAfterColon(maskedLine) : 0;
}

/**
 * Позиция двоеточия, закрывающего заголовок, либо `-1`.
 *
 * Ищется на нулевой глубине скобок: в `def f(self) -> Dict[str, int]: y = 2`
 * заголовок кончается последним двоеточием, а не тем, что внутри `Dict[...]`.
 */
function colonAt(maskedLine: string): number {
  let depth = 0;
  for (let i = 0; i < maskedLine.length; i++) {
    const c = maskedLine[i];
    if (c === "(" || c === "[" || c === "{") depth++;
    else if (c === ")" || c === "]" || c === "}") depth--;
    else if (c === ":" && depth === 0) return i;
  }
  return -1;
}

/**
 * Позиция за двоеточием заголовка, если тело есть на этой же строке; иначе `0`.
 */
function bodyAfterColon(maskedLine: string): number {
  const colon = colonAt(maskedLine);
  if (colon < 0) return 0;
  return maskedLine.slice(colon + 1).trim().length === 0 ? 0 : colon + 1;
}

/**
 * Простые операторы однострочного тела: у `pass; fields = [1]` их два.
 *
 * Без разбиения виден только первый, и всё остальное тело пропадает молча.
 * Разделитель ищется на нулевой глубине скобок; строки к этому моменту
 * замаскированы, поэтому `;` внутри литерала сюда не попадает.
 */
function simpleStatements(maskedLine: string, from: number): Array<{ start: number; end: number }> {
  const result: Array<{ start: number; end: number }> = [];
  let depth = 0;
  let start = from;
  for (let i = from; i < maskedLine.length; i++) {
    const c = maskedLine[i];
    if (c === "(" || c === "[" || c === "{") depth++;
    else if (c === ")" || c === "]" || c === "}") depth--;
    else if (c === ";" && depth === 0) {
      result.push({ start, end: i });
      start = i + 1;
    }
  }
  result.push({ start, end: maskedLine.length });
  return result;
}

/** Вызов `super().<method>()` отдельным оператором строки — в том числе в `a; b`. */
function callsSuperOnLine(maskedLine: string, method: string): boolean {
  return simpleStatements(maskedLine, 0).some((statement) =>
    isBareSuperCall(maskedLine.slice(statement.start, statement.end), method),
  );
}

/** Все совпадения глобальной [re] как объявления имён, с сырым диапазоном. */
function collectMatches(
  re: RegExp,
  maskedLine: string,
  offset: number,
  origin: DeclarationOrigin,
  out: PyDeclaration[],
): void {
  re.lastIndex = 0;
  for (let match = re.exec(maskedLine); match !== null; match = re.exec(maskedLine)) {
    const name = match[1] as string;
    const start = offset + maskedLine.indexOf(name, match.index);
    out.push({ name, nameStart: start, nameEnd: start + name.length, origin });
  }
}

/**
 * Достраивает модель пользователя: разбирает свойство `fields` и досчитывает
 * структурные гасители, которые видны только по классу целиком.
 */
function finishUserModel(
  text: string,
  masked: string,
  strings: readonly PyString[],
  cls: ClassDraft,
): void {
  // `__init__` без `super().__init__()` — гаситель только у класса С БАЗОЙ:
  // у безбазового обосновать его нечем, базовых `self.*` там не существует.
  if (cls.hasInit && cls.bases.length > 0) {
    // Побеждает **последнее** определение: так работает Python, а `find` вернул
    // бы первое и разрешил диагностику там, где действующий `__init__` super()
    // не вызывает.
    const inits = cls.methods.filter((method) => method.name === "__init__");
    const init = inits[inits.length - 1];
    if (init !== undefined && !init.callsSuper) cls.blockers.add("initWithoutSuper");
  }

  if (cls.fieldsBody.length === 0) {
    // Свойство `fields` в классе есть, но тела у него нет (или оно пустое) —
    // разобрать нечего. Само отсутствие свойства — состояние `absent`.
    if (cls.methods.some((method) => method.name === userModel.fieldsProperty)) {
      cls.fieldsState = "unparsed";
      cls.blockers.add("unparsedFieldsProperty");
    }
    return;
  }

  const parsed = parseFieldsBody(text, masked, strings, cls.fieldsBody);
  cls.fieldsState = parsed.state;
  cls.declarations.push(...parsed.declarations);
  if (parsed.state === "unparsed") cls.blockers.add("unparsedFieldsProperty");
}

interface FieldsParse {
  readonly state: FieldsState;
  readonly declarations: PyDeclaration[];
}

/** Вызов фабрики поля: `Field(` и `field.Field(` — берётся последний сегмент имени. */
const FIELD_CALL_RE = new RegExp(
  `(?:^|[^\\p{L}\\p{Nd}_])(?:${IDENT}\\.)*${userModel.fieldFactory}\\s*\\(`,
  "gu",
);
/** `super().fields` — обращение к атрибуту, а не вызов. */
const SUPER_FIELDS_RE = new RegExp(
  `^super\\s*\\(\\s*\\)\\s*\\.\\s*${userModel.fieldsProperty}\\s*\\+\\s*`,
);

/**
 * Разбирает прямое тело свойства `fields`.
 *
 * Форма признаётся только в двух видах — `return [ … ]` и
 * `return super().fields + [ … ]`, причём каждый элемент списка обязан быть
 * вызовом фабрики поля со строковым первым аргументом. Всё остальное —
 * `unparsed`: поля базы тогда сохраняются, а диагностика гасится.
 *
 * Имена полей извлекаются **всегда**, какой бы ни была форма: литерал в
 * `[*super().fields, Field("x", X)]` виден не хуже, чем в обычном списке, и
 * терять имя, которое точно существует, незачем.
 */
function parseFieldsBody(
  text: string,
  masked: string,
  strings: readonly PyString[],
  lines: readonly LogicalLine[],
): FieldsParse {
  const declarations: PyDeclaration[] = [];
  for (const line of lines) {
    for (const call of fieldCalls(masked, line.start, line.end)) {
      const literal = soleString(masked, strings, call.argStart, call.argEnd);
      if (literal === undefined) continue;
      const name = decodePyString(text, literal);
      if (name === undefined) continue;
      declarations.push({
        name,
        nameStart: literal.contentStart,
        nameEnd: literal.contentEnd,
        origin: "field",
      });
    }
  }

  return { state: fieldsState(masked, strings, lines), declarations };
}

function fieldsState(
  masked: string,
  strings: readonly PyString[],
  lines: readonly LogicalLine[],
): FieldsState {
  const line = lines[0];
  if (lines.length !== 1 || line === undefined) return "unparsed";

  const body = masked.slice(line.start, line.end).trim();
  if (!body.startsWith("return")) return "unparsed";
  const exprOffset = line.start + masked.slice(line.start, line.end).indexOf("return") + 6;
  let expr = masked.slice(exprOffset, line.end).trim();
  let state: FieldsState = "withoutSuper";

  if (SUPER_FIELDS_RE.test(expr)) {
    state = "withSuper";
    expr = expr.replace(SUPER_FIELDS_RE, "");
  }
  if (!expr.startsWith("[") || !expr.endsWith("]")) return "unparsed";

  const listStart = exprOffset + masked.slice(exprOffset, line.end).indexOf(expr);
  const listEnd = matchBracket(masked, listStart);
  if (listEnd !== listStart + expr.length - 1) return "unparsed";

  return allElementsAreFields(masked, strings, listStart + 1, listEnd) ? state : "unparsed";
}

/** Каждый элемент списка — вызов фабрики поля со строковым первым аргументом. */
function allElementsAreFields(
  masked: string,
  strings: readonly PyString[],
  start: number,
  end: number,
): boolean {
  const calls = fieldCalls(masked, start, end);
  let cursor = start;
  for (const call of calls) {
    // Между элементами допустимы только запятые и пробелы: распаковка `*`,
    // comprehension и любой посторонний элемент делают форму неразобранной.
    if (!/^[\s,]*$/.test(masked.slice(cursor, call.callStart))) return false;
    if (soleString(masked, strings, call.argStart, call.argEnd) === undefined) return false;
    cursor = call.callEnd;
  }
  if (calls.length === 0) return /^[\s,]*$/.test(masked.slice(start, end));
  return /^[\s,]*$/.test(masked.slice(cursor, end));
}

interface FieldCall {
  readonly callStart: number;
  readonly callEnd: number;
  readonly argStart: number;
  readonly argEnd: number;
}

/** Вызовы фабрики поля в диапазоне: границы вызова и первого аргумента. */
function fieldCalls(masked: string, start: number, end: number): FieldCall[] {
  const slice = masked.slice(start, end);
  const result: FieldCall[] = [];
  FIELD_CALL_RE.lastIndex = 0;
  for (let match = FIELD_CALL_RE.exec(slice); match !== null; match = FIELD_CALL_RE.exec(slice)) {
    const open = start + match.index + match[0].length - 1;
    const close = matchBracket(masked, open);
    if (close < 0 || close > end) continue;
    const callStart = start + match.index + (match[0][0] === "(" ? 0 : 1);
    result.push({
      callStart: match.index === 0 ? start : callStart,
      callEnd: close + 1,
      argStart: open + 1,
      argEnd: firstArgEnd(masked, open + 1, close),
    });
  }
  return result;
}

/** Конец первого аргумента: запятая верхнего уровня либо закрывающая скобка. */
function firstArgEnd(masked: string, start: number, close: number): number {
  let depth = 0;
  for (let i = start; i < close; i++) {
    const c = masked[i];
    if (c === "(" || c === "[" || c === "{") depth++;
    else if (c === ")" || c === "]" || c === "}") depth--;
    else if (c === "," && depth === 0) return i;
  }
  return close;
}

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
  new RegExp(`^super\\s*\\([^)]*\\)\\s*\\.\\s*${method}\\s*\\(`, "u");


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

  const subscript = new RegExp(`(?:^|[^\\p{L}\\p{Nd}_.])((?:${IDENT}\\.)*)(${IDENT})\\s*\\[`, "gu");
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

  const update = new RegExp(`(?:^|[^\\p{L}\\p{Nd}_.])((?:${IDENT}\\.)*)(${IDENT})\\s*\\.update\\s*\\(`, "gu");
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

/**
 * Точечное имя из текста значения, если это оно; иначе подписи не будет.
 *
 * Хвостовая `}` снимается — значение словарного литерала доходит сюда вместе с
 * закрывающей скобкой. Хвостовая запятая **не** снимается: `x = self._user,` —
 * законный Python и кортеж из одного элемента, а не имя.
 */
function dottedOrUndefined(value: string): string | undefined {
  const trimmed = value.trim().replace(/}+$/, "").trim();
  return new RegExp(`^${DOTTED}$`, "u").test(trimmed) ? trimmed : undefined;
}
