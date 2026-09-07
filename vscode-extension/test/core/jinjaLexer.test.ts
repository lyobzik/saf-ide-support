import { describe, expect, it } from "vitest";
import { decode } from "../../src/core/jsonDecode";
import {
  fieldCandidates,
  formVariableOccurrences,
  rootAccesses,
  tokenize,
  TokenType,
} from "../../src/core/jinjaLexer";

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

describe("formVariableOccurrences", () => {
  it("переменная формы с полем и без", () => {
    expect(formVariableOccurrences("{{ main_form }} {{ main_form.name }}")).toEqual([
      { start: 3, end: 12 },
      { start: 19, end: 28 },
    ]);
  });

  it("в statement-теге тоже", () => {
    expect(formVariableOccurrences("{% if main_form.a %}").map((o) => o.start)).toEqual([6]);
  });

  it("в позиции чужого поля вхождением не считается", () => {
    expect(formVariableOccurrences("{{ variables.main_form.name }}")).toEqual([]);
  });

  it("вне выражения вхождением не считается", () => {
    expect(formVariableOccurrences("main_form.name")).toEqual([]);
  });
});

describe("fieldCandidates", () => {
  it("кандидаты и из интерполяции, и из statement-тега", () => {
    const candidates = fieldCandidates("{{ main_form.name }} {% set x = main_form.age %}");
    expect(candidates.map((c) => c.field)).toEqual(["name", "age"]);
  });

  it("поле во второй интерполяции", () => {
    // Обход не должен заканчиваться первым фрагментом.
    expect(fieldCandidates("{{ x }} и {{ main_form.name }}").map((c) => c.field)).toEqual(["name"]);
  });

  it("кандидаты из каждой интерполяции", () => {
    const text = "{{ main_form.a }}-{{ main_form.b }}-{{ main_form.c }}";
    expect(fieldCandidates(text).map((c) => c.field)).toEqual(["a", "b", "c"]);
  });

  it("statement-тег между фрагментами не обрывает обход", () => {
    const text = "{% if main_form.a %}{{ main_form.b }}{% endif %}";
    expect(fieldCandidates(text).map((c) => c.field)).toEqual(["a", "b"]);
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

  it("цепочка даёт кандидата на первом сегменте", () => {
    // main_form.name резолвится, а хвост .extra семантики не получает: схемы
    // значений полей нет, резолвить `extra` не во что.
    expect(fieldCandidates("{{ main_form.name.extra }}").map((c) => c.field)).toEqual(["name"]);
  });

  it("операндная позиция кандидата даёт", () => {
    expect(fieldCandidates("{{ x + main_form.name }}").map((c) => c.field)).toEqual(["name"]);
  });
});

describe("rootAccesses", () => {
  it("отдаёт корень и первый сегмент", () => {
    expect(rootAccesses("{{ user.variables.smart_geo }}")).toEqual([
      { root: "user", rootStart: 3, rootEnd: 7, member: "variables", memberStart: 8, memberEnd: 17 },
    ]);
  });

  it("обращение без точки даёт корень без сегмента", () => {
    const access = rootAccesses("{{ user }}")[0];
    expect(access?.root).toBe("user");
    expect(access?.member).toBeUndefined();
  });

  it("корнем становится любое имя, не только переменная формы", () => {
    // Ключевые слова Jinja лексеру неизвестны, поэтому `if` — такой же VAR и
    // такой же корень без сегмента. Отсеивают их потребители по имени корня.
    expect(rootAccesses("{{ a.x }} {% if b.y %}").map((r) => `${r.root}.${r.member}`)).toEqual([
      "a.x",
      "if.undefined",
      "b.y",
    ]);
  });

  it("имя фильтра корнем не становится", () => {
    // `{{ value | user }}` — применение фильтра с таким именем, а не обращение
    // к модели: лексер помечает идентификатор после `|` как FILTER_NAME.
    expect(rootAccesses("{{ value | user }}").map((r) => r.root)).toEqual(["value"]);
  });

  it("внутри аргумента фильтра обращение распознаётся", () => {
    expect(rootAccesses("{{ value | default(user.field) }}").map((r) => `${r.root}.${r.member}`))
      .toEqual(["value.undefined", "user.field"]);
  });

  it("левая граница: сегмент чужого объекта корнем не становится", () => {
    expect(rootAccesses("{{ variables.user.x }}").map((r) => `${r.root}.${r.member}`)).toEqual([
      "variables.user",
    ]);
  });
});

describe("грамматика идентификатора в лексере", () => {
  const memberAfterDot = (text: string) => {
    const tokens = tokenize(text);
    const dot = tokens.findIndex((t) => t.type === TokenType.DOT);
    const next = tokens[dot + 1];
    return next === undefined ? undefined : { type: next.type, text: text.slice(next.start, next.end) };
  };

  it("кириллическое имя — VAR", () => {
    expect(memberAfterDot("{{ user.я }}")).toEqual({ type: TokenType.VAR, text: "я" });
  });

  it("цифра в продолжении имени допустима", () => {
    expect(memberAfterDot("{{ user.имя9 }}")).toEqual({ type: TokenType.VAR, text: "имя9" });
  });

  it("Nd не может начинать имя", () => {
    expect(memberAfterDot("{{ user.٣ }}")).toEqual({ type: TokenType.TEXT, text: "٣" });
  });

  it("категория No именем не является", () => {
    expect(memberAfterDot("{{ user.² }}")).toEqual({ type: TokenType.TEXT, text: "²" });
  });

  it("категория Sm именем не является", () => {
    expect(memberAfterDot("{{ user.× }}")).toEqual({ type: TokenType.TEXT, text: "×" });
  });

  it("символ вне BMP именем не является целиком", () => {
    // Обе UTF-16-единицы суррогатной пары уходят в один TEXT: ограничение BMP
    // и введено ради того, чтобы результат не зависел от способа обхода.
    expect(memberAfterDot("{{ user.\u{10400} }}")).toEqual({
      type: TokenType.TEXT,
      text: "\u{10400}",
    });
  });
});
