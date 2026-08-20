/**
 * Декодер JSON-строкового литерала: строит decoded-текст (с раскрытыми escape)
 * и карту смещений decoded→raw, чтобы лексер работал с раскрытым Jinja-кодом, а
 * диапазоны токенов транслировались обратно в координаты исходного JSON.
 *
 * Порт `JsonStringLiteralDecoder.kt`. Правила escape — часть грамматики JSON,
 * поэтому живут в коде, а не в контракте.
 *
 * Решает проблему: в JSON-представлении Jinja с двойной строкой выглядит как
 * `{{ x | default(\"y\") }}`; лексер по raw воспринял бы `\"` неверно.
 */
export interface Decoded {
  /** Текст с раскрытыми escape — то, что пользователь видит как Jinja. */
  readonly text: string;
  /** `decodedToRaw[i]` — raw-смещение, с которого начинается decoded-символ `i`. */
  readonly decodedToRaw: readonly number[];
}

const SIMPLE_ESCAPES = new Map<string, string>([
  ['"', '"'],
  ["\\", "\\"],
  ["/", "/"],
  ["b", "\b"],
  ["f", "\f"],
  ["n", "\n"],
  ["r", "\r"],
  ["t", "\t"],
]);

/**
 * Декодирует raw-содержимое литерала (без крайних кавычек).
 *
 * Карта длиннее decoded-текста на один элемент: последний элемент — sentinel с
 * end-смещением, чтобы транслировать `endOffset` диапазонов.
 */
export function decode(raw: string): Decoded {
  const out: string[] = [];
  const rawStarts: number[] = new Array<number>(raw.length + 1).fill(0);
  let decodedLength = 0;
  let i = 0;

  while (i < raw.length) {
    const c = raw[i] as string;
    if (c === "\\" && i + 1 < raw.length) {
      const next = raw[i + 1] as string;
      const simple = SIMPLE_ESCAPES.get(next);
      if (simple !== undefined) {
        rawStarts[decodedLength] = i;
        out.push(simple);
        decodedLength += simple.length;
        i += 2;
        continue;
      }
      // \uXXXX — 6 символов исходника, один decoded-символ (пара суррогатов
      // считается двумя UTF-16 code unit'ами, как и в Kotlin-версии).
      if (next === "u" && i + 5 < raw.length) {
        const codePoint = Number.parseInt(raw.slice(i + 2, i + 6), 16);
        if (!Number.isNaN(codePoint)) {
          const decodedChar = String.fromCodePoint(codePoint);
          rawStarts[decodedLength] = i;
          for (let k = 1; k < decodedChar.length; k++) rawStarts[decodedLength + k] = i;
          out.push(decodedChar);
          decodedLength += decodedChar.length;
          i += 6;
          continue;
        }
      }
    }
    rawStarts[decodedLength] = i;
    out.push(c);
    decodedLength += 1;
    i += 1;
  }

  rawStarts.length = decodedLength + 1;
  rawStarts[decodedLength] = raw.length;
  return { text: out.join(""), decodedToRaw: rawStarts };
}

/**
 * Raw-содержимое строкового литерала (без крайних кавычек) или `undefined`,
 * если текст не выглядит как JSON-строка.
 */
export function rawText(literalText: string): string | undefined {
  if (literalText.length < 2) return undefined;
  if (!literalText.startsWith('"') || !literalText.endsWith('"')) return undefined;
  return literalText.slice(1, -1);
}
