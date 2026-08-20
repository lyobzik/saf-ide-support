import { jinja } from "./contract";

/**
 * Детерминированный лексер Jinja-фрагментов внутри строкового JSON-литерала.
 * Порт `SmartAppJinjaLexer.kt`, кейс в кейс — расхождение здесь означало бы, что
 * две IDE по-разному подсвечивают и резолвят один и тот же файл.
 *
 * **Не полноценный Jinja-парсер**, а токенизатор для двух целей:
 * 1. найти семантические ссылки `<formVariable>.<id>` — только внутри
 *    интерполяции `{{ … }}` (statement-теги `{% … %}` ссылок не порождают);
 * 2. разметить токены для подсветки.
 *
 * Контракт:
 * - разделители учитываются только парные и вне строк Jinja (`{{ '}}' }}` — ок);
 * - вход — decoded-текст (JSON escape уже раскрыты, см. `jsonDecode.ts`);
 * - идентификаторы внутри строк не токенизируются;
 * - `{% … %}` — только лексическая подсветка;
 * - идентификатор после `|` помечается как FILTER_NAME.
 *
 * Все смещения — относительно переданного decoded-текста.
 */
export enum TokenType {
  /** `{{` — открывающий разделитель интерполяции. */
  INTERP_OPEN = "INTERP_OPEN",
  /** `}}` — закрывающий разделитель интерполяции. */
  INTERP_CLOSE = "INTERP_CLOSE",
  /** `{%` — открывающий разделитель statement-тега (только подсветка). */
  STATEMENT_OPEN = "STATEMENT_OPEN",
  /** `%}` — закрывающий разделитель statement-тега. */
  STATEMENT_CLOSE = "STATEMENT_CLOSE",
  /** Идентификатор-переменная. */
  VAR = "VAR",
  /** Одиночная точка — доступ к полю объекта. */
  DOT = "DOT",
  /** `|` — оператор применения фильтра. */
  FILTER_OP = "FILTER_OP",
  /** Имя фильтра (идентификатор сразу после FILTER_OP). */
  FILTER_NAME = "FILTER_NAME",
  /** Строковый литерал внутри Jinja. */
  STRING = "STRING",
  /** Любой прочий текст (внутри выражения и снаружи). */
  TEXT = "TEXT",
}

export interface Token {
  readonly type: TokenType;
  readonly start: number;
  readonly end: number;
}

/** Кандидат на семантическую ссылку `<formVariable>.<field>` в интерполяции. */
export interface FieldCandidate {
  readonly form: string;
  readonly field: string;
  readonly fieldStart: number;
  readonly fieldEnd: number;
}

const enum DelimKind {
  INTERP,
  STATEMENT,
}

interface OpenDelim {
  readonly start: number;
  readonly end: number;
  readonly kind: DelimKind;
}

const token = (type: TokenType, start: number, end: number): Token => ({ type, start, end });

const isWhitespace = (c: string): boolean => /\s/.test(c);

// Класс идентификатора — часть грамматики, а не DSL-данные: держим в коде.
// Соответствует Character.isJavaIdentifierStart/Part для практического набора
// символов, встречающихся в именах полей и фильтров.
const isIdentifierStart = (c: string): boolean => /[A-Za-z_$À-￿]/.test(c);
const isIdentifierPart = (c: string): boolean => /[A-Za-z0-9_$À-￿]/.test(c);

/** Токенизирует decoded-текст литерала. Возвращает упорядоченный список токенов. */
export function tokenize(decodedText: string): Token[] {
  const tokens: Token[] = [];
  const n = decodedText.length;
  let i = 0;

  while (i < n) {
    const open = findOpenDelim(decodedText, i);
    if (open === undefined) {
      if (i < n) tokens.push(token(TokenType.TEXT, i, n));
      return tokens;
    }
    if (open.start > i) tokens.push(token(TokenType.TEXT, i, open.start));

    const close = findCloseDelim(decodedText, open.end, open.kind);
    if (close === undefined) {
      // Непарный разделитель: весь остаток — TEXT, ошибкой не отмечаем.
      tokens.push(token(TokenType.TEXT, open.start, n));
      return tokens;
    }

    tokens.push(
      token(
        open.kind === DelimKind.INTERP ? TokenType.INTERP_OPEN : TokenType.STATEMENT_OPEN,
        open.start,
        open.end,
      ),
    );
    tokenizeExpr(decodedText, open.end, close.start, tokens);
    tokens.push(
      token(
        open.kind === DelimKind.INTERP ? TokenType.INTERP_CLOSE : TokenType.STATEMENT_CLOSE,
        close.start,
        close.end,
      ),
    );
    i = close.end;
  }
  return tokens;
}

/**
 * Находит семантические кандидаты `<formVariable>.<id>` внутри интерполяций.
 * Допускает whitespace между переменной, `.` и именем поля. Цепочки не
 * разбираются: переменная в позиции чужого поля (`variables.main_form.x`) и
 * подобъекты (`main_form.x.y`) кандидатов не дают.
 */
export function fieldCandidates(decodedText: string): FieldCandidate[] {
  const tokens = tokenize(decodedText);
  const result: FieldCandidate[] = [];

  let i = 0;
  while (i < tokens.length) {
    if (tokens[i]?.type !== TokenType.INTERP_OPEN) {
      i++;
      continue;
    }
    i = collectFromInterp(decodedText, tokens, i, result);
  }
  return result;
}

/** Токенизирует тело выражения — диапазон (from, until) без разделителей. */
function tokenizeExpr(text: string, from: number, until: number, out: Token[]): void {
  let i = from;
  let afterFilter = false;

  while (i < until) {
    const c = text[i] as string;
    if (isWhitespace(c)) {
      const start = i;
      while (i < until && isWhitespace(text[i] as string)) i++;
      out.push(token(TokenType.TEXT, start, i));
      // afterFilter намеренно не сбрасываем: имя фильтра может стоять через
      // whitespace после `|`.
      continue;
    }
    if (c === ".") {
      out.push(token(TokenType.DOT, i, i + 1));
      i++;
      afterFilter = false;
      continue;
    }
    if (c === "|") {
      out.push(token(TokenType.FILTER_OP, i, i + 1));
      i++;
      afterFilter = true;
      continue;
    }
    if (c === '"' || c === "'") {
      const start = i;
      i++;
      while (i < until) {
        const q = text[i] as string;
        if (q === "\\") {
          // Ограничиваем переход, чтобы `\` у самой границы выражения не увёл i
          // за until и не перекрыл закрывающий разделитель.
          i = Math.min(i + 2, until);
          continue;
        }
        i++;
        if (q === c) break;
      }
      out.push(token(TokenType.STRING, start, i));
      afterFilter = false;
      continue;
    }
    if (isIdentifierStart(c)) {
      const start = i;
      i++;
      while (i < until && isIdentifierPart(text[i] as string)) i++;
      out.push(token(afterFilter ? TokenType.FILTER_NAME : TokenType.VAR, start, i));
      afterFilter = false;
      continue;
    }
    const start = i;
    while (
      i < until &&
      !isWhitespace(text[i] as string) &&
      text[i] !== "." &&
      text[i] !== "|" &&
      text[i] !== '"' &&
      text[i] !== "'" &&
      !isIdentifierStart(text[i] as string)
    ) {
      i++;
    }
    out.push(token(TokenType.TEXT, start, i));
    afterFilter = false;
  }
}

/**
 * Собирает кандидатов из одной интерполяции, начатой токеном [interpStartIdx].
 * Возвращает индекс, с которого продолжать обход.
 */
function collectFromInterp(
  text: string,
  tokens: readonly Token[],
  interpStartIdx: number,
  result: FieldCandidate[],
): number {
  let j = interpStartIdx + 1;

  while (j < tokens.length) {
    const t = tokens[j] as Token;
    if (t.type === TokenType.INTERP_CLOSE) return j + 1;
    // Вложенные statement-теги внутри {{ }} невозможны, но защитно выходим.
    if (t.type === TokenType.STATEMENT_OPEN) return j;

    if (t.type === TokenType.VAR && text.slice(t.start, t.end) === jinja.formVariable) {
      // Левая граница: переменная в позиции чужого поля (variables.main_form.x)
      // — не наша семантика.
      const prev = neighborSkippingWhitespace(text, tokens, j - 1, -1);
      if (prev?.type === TokenType.DOT) {
        j++;
        continue;
      }
      const dotIdx = nextIndexSkippingWhitespace(text, tokens, j + 1);
      const dot = dotIdx === undefined ? undefined : tokens[dotIdx];
      const fieldIdx =
        dot?.type === TokenType.DOT
          ? nextIndexSkippingWhitespace(text, tokens, (dotIdx as number) + 1)
          : undefined;
      const field = fieldIdx === undefined ? undefined : tokens[fieldIdx];

      if (field?.type === TokenType.VAR) {
        // Правая граница: подобъекты main_form.x.y не разбираем — ни ссылки,
        // ни предупреждения.
        const next = neighborSkippingWhitespace(text, tokens, (fieldIdx as number) + 1, +1);
        if (next?.type === TokenType.DOT) {
          j = (fieldIdx as number) + 1;
          continue;
        }
        result.push({
          form: jinja.formVariable,
          field: text.slice(field.start, field.end),
          fieldStart: field.start,
          fieldEnd: field.end,
        });
        j = (fieldIdx as number) + 1;
        continue;
      }
    }
    j++;
  }
  return j;
}

/**
 * Индекс следующего токена, пропуская только TEXT из одних пробелов. Прочий
 * TEXT (например `+` между переменной и точкой) останавливает поиск, чтобы
 * `{{ main_form + . unknown }}` не стал ложной ссылкой.
 */
function nextIndexSkippingWhitespace(
  text: string,
  tokens: readonly Token[],
  fromIdx: number,
): number | undefined {
  for (let k = fromIdx; k < tokens.length; k++) {
    const t = tokens[k] as Token;
    if (t.type !== TokenType.TEXT) return k;
    if (!isBlank(text.slice(t.start, t.end))) return undefined;
  }
  return undefined;
}

/**
 * Соседний токен в направлении [step], пропуская только whitespace-TEXT. В
 * отличие от [nextIndexSkippingWhitespace], не-whitespace TEXT — полноценный
 * сосед: для проверки границ триплета важен именно DOT.
 */
function neighborSkippingWhitespace(
  text: string,
  tokens: readonly Token[],
  fromIdx: number,
  step: number,
): Token | undefined {
  for (let k = fromIdx; k >= 0 && k < tokens.length; k += step) {
    const t = tokens[k] as Token;
    if (t.type !== TokenType.TEXT) return t;
    if (!isBlank(text.slice(t.start, t.end))) return t;
  }
  return undefined;
}

const isBlank = (s: string): boolean => s.length === 0 || /^\s+$/.test(s);

/** Находит `{{` или `{%`, начиная с [from]. */
function findOpenDelim(text: string, from: number): OpenDelim | undefined {
  for (let i = from; i < text.length - 1; i++) {
    if (text[i] !== "{") continue;
    if (text[i + 1] === "{") return { start: i, end: i + 2, kind: DelimKind.INTERP };
    if (text[i + 1] === "%") return { start: i, end: i + 2, kind: DelimKind.STATEMENT };
  }
  return undefined;
}

/**
 * Находит парный `}}`/`%}` начиная с [from], пропуская строковые литералы Jinja
 * (с учётом `\`-escape). Иначе `}}` внутри строки закрыл бы выражение раньше.
 */
function findCloseDelim(
  text: string,
  from: number,
  kind: DelimKind,
): { start: number; end: number } | undefined {
  const first = kind === DelimKind.INTERP ? "}" : "%";
  const n = text.length;
  let i = from;

  while (i < n - 1) {
    const c = text[i] as string;
    if (c === '"' || c === "'") {
      i++;
      while (i < n) {
        if (text[i] === "\\") {
          i += 2;
          continue;
        }
        const closing = text[i] === c;
        i++;
        if (closing) break;
      }
      continue;
    }
    if (c === first && text[i + 1] === "}") return { start: i, end: i + 2 };
    i++;
  }
  return undefined;
}
