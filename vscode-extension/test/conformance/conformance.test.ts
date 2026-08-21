import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative, sep } from "node:path";
import { describe, expect, it } from "vitest";
import { SmartAppIndex } from "../../src/core/index";
import { completionAt } from "../../src/core/completion";
import { renameEdits } from "../../src/core/rename";
import { semanticTokens } from "../../src/core/semanticTokens";
import {
  definitionsAt,
  diagnostics,
  documentContext,
  referencesAt,
} from "../../src/core/semantics";

/**
 * Прогон общего conformance-корпуса поверх ядра.
 *
 * Тот же каталог `shared/fixtures` читает `SmartAppConformanceTest` в плагине
 * IDEA. Пока обе стороны зелёные на одном корпусе, их семантика совпадает —
 * общий `rules.json` этого гарантировать не может, он фиксирует только данные.
 */

// vitest запускается из каталога расширения; общий корпус лежит уровнем выше.
const fixturesDir = join(process.cwd(), "..", "shared", "fixtures");

interface TargetSpec {
  readonly file: string;
  readonly name?: string;
  readonly ordinal?: number;
  readonly line: number;
  /**
   * Символ начала диапазона в строке. Необязателен, но обязателен, если текст
   * встречается в строке дважды: иначе ожидание не различает два одинаковых
   * имени на одной строке.
   */
  readonly column?: number;
  readonly rangeText: string;
}

interface Expected {
  readonly description: string;
  readonly definitions?: readonly {
    file: string;
    anchor: string;
    anchorOffset?: number;
    targets: readonly TargetSpec[];
  }[];
  readonly references?: readonly {
    file: string;
    anchor: string;
    anchorOffset?: number;
    includeDeclaration?: boolean;
    targets: readonly TargetSpec[];
  }[];
  readonly diagnostics?: readonly {
    file: string;
    items: readonly { message: string; line: number; rangeText: string }[];
  }[];
  readonly completion?: readonly {
    file: string;
    anchor: string;
    anchorOffset?: number;
    kind?: string;
    items: readonly string[];
    absent?: readonly string[];
  }[];
  readonly semanticTokens?: readonly {
    file: string;
    type: string;
    texts: readonly string[];
    lines: readonly number[];
  }[];
  readonly rename?: readonly {
    file: string;
    anchor: string;
    anchorOffset?: number;
    newName: string;
    edits: readonly { file: string; line: number; column?: number; rangeText: string }[];
  }[];
  readonly indexedNames?: readonly { kind: string; names: readonly string[] }[];
}

interface Fixture {
  readonly name: string;
  readonly expected: Expected;
  readonly files: ReadonlyMap<string, string>;
}

const URI_PREFIX = "file:///workspace/";

const loadFixtures = (): Fixture[] =>
  readdirSync(fixturesDir)
    .filter((entry) => statSync(join(fixturesDir, entry)).isDirectory())
    .sort()
    .map((name) => {
      const dir = join(fixturesDir, name);
      const filesDir = join(dir, "files");
      const files = new Map<string, string>();
      for (const path of walk(filesDir)) {
        files.set(relative(filesDir, path).split(sep).join("/"), readFileSync(path, "utf8"));
      }
      return {
        name,
        expected: JSON.parse(readFileSync(join(dir, "expected.json"), "utf8")) as Expected,
        files,
      };
    });

function walk(dir: string): string[] {
  return readdirSync(dir).flatMap((entry) => {
    const path = join(dir, entry);
    return statSync(path).isDirectory() ? walk(path) : [path];
  });
}

const uriOf = (path: string): string => URI_PREFIX + path;

const buildIndex = (fixture: Fixture): SmartAppIndex => {
  const index = new SmartAppIndex();
  for (const [path, text] of fixture.files) index.upsert(uriOf(path), text);
  index.markReady();
  return index;
};

const textOf = (fixture: Fixture, path: string): string => {
  const text = fixture.files.get(path);
  if (text === undefined) throw new Error(`фикстура '${fixture.name}' не содержит файла ${path}`);
  return text;
};

/** Позиция каретки: смещение якоря плюс сдвиг внутри него. */
const anchorOffset = (text: string, anchor: string, offset = 0): number => {
  const index = text.indexOf(anchor);
  if (index < 0) throw new Error(`якорь '${anchor}' не найден в файле`);
  return index + offset;
};

const lineOf = (text: string, offset: number): number =>
  text.slice(0, offset).split("\n").length - 1;

const columnOf = (text: string, offset: number): number =>
  offset - (text.lastIndexOf("\n", offset - 1) + 1);

interface Described {
  readonly file: string;
  readonly line: number;
  readonly column?: number;
  readonly rangeText: string;
}

const describeLocation = (
  fixture: Fixture,
  location: { uri: string; start: number; end: number },
): Required<Described> => {
  const path = location.uri.slice(URI_PREFIX.length);
  const text = textOf(fixture, path);
  return {
    file: path,
    line: lineOf(text, location.start),
    column: columnOf(text, location.start),
    rangeText: text.slice(location.start, location.end),
  };
};

/** Сколько раз текст встречается в строке файла. */
const occurrencesInLine = (text: string, line: number, needle: string): number =>
  (text.split("\n")[line] ?? "").split(needle).length - 1;

/**
 * Ожидаемая позиция из фикстуры. Без `column` требуем однозначности: два
 * одинаковых имени на строке обязаны различаться колонкой.
 */
const expectedDescribed = (fixture: Fixture, target: TargetSpec | Described): Described => {
  if (target.column === undefined) {
    const occurrences = occurrencesInLine(
      textOf(fixture, target.file),
      target.line,
      target.rangeText,
    );
    expect(
      occurrences,
      `в '${fixture.name}/${target.file}' строка ${target.line} содержит ` +
        `'${target.rangeText}' ${occurrences} раз(а) — укажите 'column'`,
    ).toBe(1);
  }
  return {
    file: target.file,
    line: target.line,
    ...(target.column === undefined ? {} : { column: target.column }),
    rangeText: target.rangeText,
  };
};

/** Приводит фактическую позицию к форме ожидания (с колонкой или без). */
const matching = (actual: Required<Described>, expected: Described): Described =>
  expected.column === undefined
    ? { file: actual.file, line: actual.line, rangeText: actual.rangeText }
    : actual;

const fixtures = loadFixtures();

/**
 * Секции `expected.json`, которые умеет проверять этот раннер. Список обязан
 * совпадать с `SUPPORTED_SECTIONS` в `SmartAppConformanceTest`: секция, которую
 * сторона не проверяет, — это молчаливо непроверенный контракт.
 */
const SUPPORTED_SECTIONS = new Set([
  "description",
  "definitions",
  "references",
  "diagnostics",
  "completion",
  "semanticTokens",
  "rename",
  "indexedNames",
]);

describe("conformance-корпус", () => {
  it("корпус не пуст и прогоняется целиком", () => {
    // Молча пропущенный кейс не должен выглядеть зелёным.
    expect(fixtures.length).toBeGreaterThan(0);
    expect(fixtures.every((f) => f.expected.description.length > 0)).toBe(true);
  });

  it("все секции корпуса поддержаны раннером", () => {
    for (const fixture of fixtures) {
      const unknown = Object.keys(fixture.expected).filter(
        (section) => !SUPPORTED_SECTIONS.has(section),
      );
      expect(unknown, `фикстура '${fixture.name}' объявляет непроверяемые секции`).toEqual([]);
    }
  });

  for (const fixture of fixtures) {
    describe(fixture.name, () => {
      const index = buildIndex(fixture);
      const contextFor = (path: string) => documentContext(uriOf(path), textOf(fixture, path));

      for (const check of fixture.expected.definitions ?? []) {
        it(`definition: ${check.anchor}`, () => {
          const text = textOf(fixture, check.file);
          const offset = anchorOffset(text, check.anchor, check.anchorOffset);
          const found = definitionsAt(index, contextFor(check.file), offset);

          const expectedTargets = check.targets.map((target) => expectedDescribed(fixture, target));
          expect(
            found.map((location, index) => {
              const described = describeLocation(fixture, location);
              const expectation = expectedTargets[index];
              return expectation === undefined ? described : matching(described, expectation);
            }),
          ).toEqual(expectedTargets);
          // Идентичность определения: имя плюс ordinal среди одноимённых ключей.
          const withOrdinals = check.targets.filter((t) => t.ordinal !== undefined);
          if (withOrdinals.length > 0) {
            // FileTarget (ссылка на файл) ни имени, ни ordinal не имеет —
            // такие кейсы ordinal и не описывают.
            expect(found.map((d) => ("ordinal" in d ? { name: nameOf(d), ordinal: d.ordinal } : d))).toEqual(
              withOrdinals.map((t) => ({ name: t.name, ordinal: t.ordinal })),
            );
          }
        });
      }

      for (const check of fixture.expected.references ?? []) {
        it(`references: ${check.anchor}`, () => {
          const text = textOf(fixture, check.file);
          const offset = anchorOffset(text, check.anchor, check.anchorOffset);
          const found = referencesAt(
            index,
            contextFor(check.file),
            offset,
            check.includeDeclaration ?? false,
          );
          // Порядок обхода файлов у двух реализаций свой — сравниваем состав.
          const sortKey = (item: Described) =>
            `${item.file}:${String(item.line).padStart(4, "0")}:` +
            `${String(item.column ?? 0).padStart(4, "0")}:${item.rangeText}`;
          const expectedTargets = check.targets
            .map((target) => expectedDescribed(fixture, target))
            .sort((a, b) => sortKey(a).localeCompare(sortKey(b)));
          expect(
            found
              .map((location) => describeLocation(fixture, location))
              .sort((a, b) => sortKey(a).localeCompare(sortKey(b)))
              .map((described, index) => {
                const expectation = expectedTargets[index];
                return expectation === undefined ? described : matching(described, expectation);
              }),
          ).toEqual(expectedTargets);
        });
      }

      for (const check of fixture.expected.diagnostics ?? []) {
        it(`diagnostics: ${check.file}`, () => {
          const text = textOf(fixture, check.file);
          const found = diagnostics(index, contextFor(check.file));
          expect(
            found.map((item) => ({
              message: item.message,
              line: lineOf(text, item.start),
              rangeText: text.slice(item.start, item.end),
            })),
          ).toEqual(check.items);
        });
      }

      for (const check of fixture.expected.completion ?? []) {
        it(`completion: ${check.anchor}`, () => {
          const text = textOf(fixture, check.file);
          const offset = anchorOffset(text, check.anchor, check.anchorOffset);
          const result = completionAt(index, contextFor(check.file), offset);
          const labels = result.items.map((item) => item.label);
          // Та же проекция, что и на стороне IDEA: сравниваем состав ожидаемых
          // вариантов, отдельно проверяя запрещённые. Порядок не фиксируем — в
          // IDEA его определяет сортировщик lookup'а платформы.
          expect(labels.filter((label) => check.items.includes(label)).sort()).toEqual(
            [...check.items].sort(),
          );
          for (const variant of check.absent ?? []) {
            expect(labels).not.toContain(variant);
          }
          if (check.kind !== undefined && check.items.length > 0) {
            expect(result.kind).toBe(check.kind);
          }
        });
      }

      for (const check of fixture.expected.semanticTokens ?? []) {
        it(`semanticTokens: ${check.file} ${check.type}`, () => {
          const text = textOf(fixture, check.file);
          const tokens = semanticTokens(contextFor(check.file)).filter((t) => t.type === check.type);
          expect(tokens.map((t) => text.slice(t.start, t.end))).toEqual(check.texts);
          expect(tokens.map((t) => lineOf(text, t.start))).toEqual(check.lines);
        });
      }

      for (const check of fixture.expected.rename ?? []) {
        it(`rename: ${check.anchor} -> ${check.newName}`, () => {
          const text = textOf(fixture, check.file);
          const offset = anchorOffset(text, check.anchor, check.anchorOffset);
          const edits = renameEdits(index, contextFor(check.file), offset, check.newName);
          const expectedEdits = check.edits.map((edit) => expectedDescribed(fixture, edit));
          expect(
            edits.map((edit, index) => {
              const described = describeLocation(fixture, edit);
              const expectation = expectedEdits[index];
              return expectation === undefined ? described : matching(described, expectation);
            }),
          ).toEqual(expectedEdits);
        });
      }

      for (const check of fixture.expected.indexedNames ?? []) {
        it(`index: ${check.kind}`, () => {
          const scopeRoot = "workspace/static/references";
          // Порядок обхода файлов у двух реализаций свой — контракт задаёт
          // состав имён, а не их последовательность.
          expect([...index.namesOfKind(check.kind, scopeRoot)].sort()).toEqual(
            [...check.names].sort(),
          );
        });
      }
    });
  }
});

function nameOf(definition: { name?: string; field?: string }): string | undefined {
  return definition.name ?? definition.field;
}
