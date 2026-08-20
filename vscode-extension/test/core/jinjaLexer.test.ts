import { describe, expect, it } from "vitest";
import { decode } from "../../src/core/jsonDecode";
import { fieldCandidates, tokenize, TokenType } from "../../src/core/jinjaLexer";

/**
 * Порт `SmartAppJinjaLexerTest.kt` — кейс в кейс. Лексер Jinja самая тонкая часть
 * порта, поэтому тесты пишутся раньше реализации и держат обе IDE в одной
 * семантике: парные/непарные разделители, statement vs интерполяция,
 * FILTER_NAME, идентификаторы внутри строк, границы `main_form.<field>`.
 */

const nonText = (text: string) =>
  tokenize(text)
    .filter((t) => t.type !== TokenType.TEXT)
    .map((t) => t.type);

describe("tokenize", () => {
  it("текст без Jinja — один TEXT", () => {
    expect(tokenize("just text").map((t) => t.type)).toEqual([TokenType.TEXT]);
  });

  it("простая ссылка на поле", () => {
    const text = "{{ main_form.name }}";
    expect(nonText(text)).toEqual([
      TokenType.INTERP_OPEN,
      TokenType.VAR,
      TokenType.DOT,
      TokenType.VAR,
      TokenType.INTERP_CLOSE,
    ]);
    const nameVar = tokenize(text).find((t) => t.type === TokenType.VAR && t.start === 13);
    expect(nameVar).toBeDefined();
    expect([nameVar?.start, nameVar?.end]).toEqual([13, 17]);
  });

  it("statement-тег не даёт INTERP-токенов", () => {
    const types = new Set(tokenize("{% if x %}").map((t) => t.type));
    expect(types.has(TokenType.STATEMENT_OPEN)).toBe(true);
    expect(types.has(TokenType.STATEMENT_CLOSE)).toBe(true);
    expect(types.has(TokenType.INTERP_OPEN)).toBe(false);
    expect(types.has(TokenType.INTERP_CLOSE)).toBe(false);
  });

  it("непарный разделитель — только TEXT", () => {
    const tokens = tokenize("a {{ x");
    expect(tokens.every((t) => t.type === TokenType.TEXT)).toBe(true);
  });

  it("несколько фрагментов", () => {
    expect(nonText("{{ a }} and {{ b }}")).toEqual([
      TokenType.INTERP_OPEN,
      TokenType.VAR,
      TokenType.INTERP_CLOSE,
      TokenType.INTERP_OPEN,
      TokenType.VAR,
      TokenType.INTERP_CLOSE,
    ]);
  });

  it("идентификаторы внутри строки не токенизируются", () => {
    expect(nonText('{{ "main_form.name" }}')).toEqual([
      TokenType.INTERP_OPEN,
      TokenType.STRING,
      TokenType.INTERP_CLOSE,
    ]);
  });

  it("оператор фильтра и имя фильтра", () => {
    const text = "{{ x | default('') }}";
    expect(nonText(text)).toEqual([
      TokenType.INTERP_OPEN,
      TokenType.VAR,
      TokenType.FILTER_OP,
      TokenType.FILTER_NAME,
      TokenType.STRING,
      TokenType.INTERP_CLOSE,
    ]);
    const filterName = tokenize(text).find((t) => t.type === TokenType.FILTER_NAME);
    expect(text.slice(filterName!.start, filterName!.end)).toBe("default");
  });

  it("закрывающий разделитель внутри одинарной строки игнорируется", () => {
    expect(nonText("{{ '}}' }}")).toEqual([
      TokenType.INTERP_OPEN,
      TokenType.STRING,
      TokenType.INTERP_CLOSE,
    ]);
  });

  it("закрывающий разделитель внутри двойной строки игнорируется", () => {
    expect(nonText('{{ "}}" }}')).toEqual([
      TokenType.INTERP_OPEN,
      TokenType.STRING,
      TokenType.INTERP_CLOSE,
    ]);
  });

  it("%} внутри строки не закрывает statement", () => {
    expect(nonText("{% if x == '%}' %}")).toEqual([
      TokenType.STATEMENT_OPEN,
      TokenType.VAR,
      TokenType.VAR,
      TokenType.STRING,
      TokenType.STATEMENT_CLOSE,
    ]);
  });

  it("JSON-escape раскрывается до лексинга", () => {
    const decoded = decode('{{ x | default(\\"y\\") }}');
    expect(
      tokenize(decoded.text)
        .filter((t) => t.type !== TokenType.TEXT)
        .map((t) => t.type),
    ).toEqual([
      TokenType.INTERP_OPEN,
      TokenType.VAR,
      TokenType.FILTER_OP,
      TokenType.FILTER_NAME,
      TokenType.STRING,
      TokenType.INTERP_CLOSE,
    ]);
  });

  it("'\\' перед закрывающим разделителем оставляет фрагмент текстом", () => {
    expect(tokenize("{{ 'a\\}}").map((t) => t.type)).toEqual([TokenType.TEXT]);
  });

  it("на малформированном входе диапазоны упорядочены и в границах", () => {
    const inputs = [
      "{{ 'a\\}}",
      '{{ "x\\',
      "{{ '",
      "{{ main_form.",
      "{% if",
      "{{ '\\'%}",
      "{{ 'a\\' }}",
      "text {{ x }} {{ 'y\\' }} z",
    ];
    for (const input of inputs) {
      let prevEnd = 0;
      for (const token of tokenize(input)) {
        expect(token.start).toBeGreaterThanOrEqual(prevEnd);
        expect(token.end).toBeLessThanOrEqual(input.length);
        prevEnd = token.end;
      }
    }
  });
});

describe("fieldCandidates", () => {
  it("кандидаты только из интерполяции", () => {
    const candidates = fieldCandidates("{{ main_form.name }} {% set x = main_form.age %}");
    expect(candidates.map((c) => c.field)).toEqual(["name"]);
  });

  it("whitespace вокруг точки допустим", () => {
    expect(fieldCandidates("{{ main_form . name }}").map((c) => c.field)).toEqual(["name"]);
  });

  it("мусор между main_form и точкой убирает кандидата", () => {
    expect(fieldCandidates("{{ main_form + . unknown }}")).toEqual([]);
  });

  it("main_form в позиции чужого поля кандидата не даёт", () => {
    expect(fieldCandidates("{{ variables.main_form.unknown }}")).toEqual([]);
  });

  it("цепочка подобъектов кандидата не даёт", () => {
    expect(fieldCandidates("{{ main_form.name.extra }}")).toEqual([]);
  });

  it("операндная позиция кандидата даёт", () => {
    expect(fieldCandidates("{{ x + main_form.name }}").map((c) => c.field)).toEqual(["name"]);
  });
});
