import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

import { isAddressableName, isIdentifierPart, isIdentifierStart } from "../../src/core/identifiers";

/**
 * Общая таблица входов предиката. Тот же файл читают тесты плагина IDEA и тест
 * генератора снимка: сторон три, и три копии таблицы разошлись бы быстрее двух.
 */
interface NameCase {
  readonly input: string;
  readonly addressable: boolean;
  readonly field: boolean;
  readonly why: string;
}

const table = JSON.parse(
  readFileSync(join(process.cwd(), "..", "shared", "predicates", "attribute-names.json"), "utf8"),
) as { names: NameCase[] };

describe("общая таблица имён", () => {
  it("таблица непуста и покрывает обе стороны предиката", () => {
    expect(table.names.length).toBeGreaterThan(10);
    expect(table.names.some((c) => c.addressable)).toBe(true);
    expect(table.names.some((c) => !c.addressable)).toBe(true);
    // Ведущее подчёркивание — единственный вход, где addressable и field
    // расходятся; без него колонка `field` не проверяла бы ничего.
    expect(table.names.some((c) => c.addressable && !c.field)).toBe(true);
  });

  for (const testCase of table.names) {
    it(`адресуемость ${JSON.stringify(testCase.input)} — ${testCase.why}`, () => {
      expect(isAddressableName(testCase.input)).toBe(testCase.addressable);
    });
  }

  for (const testCase of table.names) {
    it(`годность как имени поля ${JSON.stringify(testCase.input)}`, () => {
      const asField = isAddressableName(testCase.input) && !testCase.input.startsWith("_");
      expect(asField).toBe(testCase.field);
    });
  }
});

describe("грамматика идентификатора", () => {
  it("буква и подчёркивание начинают идентификатор", () => {
    expect(isIdentifierStart("a")).toBe(true);
    expect(isIdentifierStart("я")).toBe(true);
    expect(isIdentifierStart("_")).toBe(true);
  });

  it("десятичная цифра продолжает, но не начинает", () => {
    expect(isIdentifierStart("9")).toBe(false);
    expect(isIdentifierPart("9")).toBe(true);
    // U+0663 — арабская десятичная, категория Nd: те же правила, что у ASCII.
    expect(isIdentifierStart("٣")).toBe(false);
    expect(isIdentifierPart("٣")).toBe(true);
  });

  it("не-буквенные символы не входят в грамматику", () => {
    // × ÷ — категория Sm, ² — No, ¡ — Po, $ — Sc: старое приближение
    // диапазоном [À-￿] принимало первые три.
    for (const c of ["×", "÷", "²", "¡", "$"]) {
      expect(isIdentifierStart(c)).toBe(false);
      expect(isIdentifierPart(c)).toBe(false);
    }
  });

  it("аргумент длиннее одной UTF-16-единицы отвергается", () => {
    // Контракт функций — одна единица. Без якорей в регулярках и без проверки
    // длины "ab", "a9" и "-x" проходили бы как идентификатор, а "𐐀" обходил бы
    // ограничение BMP: буква нашлась бы во второй половине суррогатной пары.
    for (const s of ["ab", "a9", "-x", "𐐀", "9a"]) {
      expect(isIdentifierStart(s)).toBe(false);
      expect(isIdentifierPart(s)).toBe(false);
    }
  });

  it("одиночный суррогат идентификатором не является", () => {
    expect(isIdentifierStart("\ud800")).toBe(false);
    expect(isIdentifierPart("\ud800")).toBe(false);
  });
});
