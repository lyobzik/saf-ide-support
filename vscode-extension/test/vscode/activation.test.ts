import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { paths } from "../../src/core/contract";
import { SemanticTokenType } from "../../src/core/semanticTokens";

/**
 * Манифест — тоже место, где DSL-имена могут разъехаться с контрактом.
 * `activationEvents` раньше содержал `static/references` строкой: при изменении
 * корневых сегментов расширение не активировалось бы, пока пользователь не
 * откроет JSON-файл, и об этом никто бы не узнал.
 */
const manifest = JSON.parse(readFileSync(join(process.cwd(), "package.json"), "utf8")) as {
  activationEvents: string[];
  contributes: {
    semanticTokenScopes: { language: string; scopes: Record<string, string[]> }[];
  };
};

describe("манифест расширения", () => {
  it("не зашивает путь из контракта в activationEvents", () => {
    for (const event of manifest.activationEvents) {
      for (const segment of paths.rootSegments) {
        expect(event, `activationEvent '${event}' дублирует сегмент контракта`).not.toContain(
          segment,
        );
      }
    }
  });

  it("активируется по языку JSON — этого достаточно для всех функций", () => {
    expect(manifest.activationEvents).toContain("onLanguage:json");
  });
});

/**
 * Цвет семантических токенов задаёт тема, а не расширение — но только для тех
 * scope'ов, о которых тема знает. Кастомный scope без «знакомого» предка
 * (так было у `punctuation.accessor.smartapp`) рисуется цветом обычного текста
 * и вдобавок перекрывает цвет строки JSON: подсветка пропадает, тесты молчат.
 *
 * Список ниже — scope'ы, которые красят все встроенные темы VS Code
 * (Dark/Light Modern и Dark/Light+); проверено по их tokenColors. Каждый тип
 * токена обязан иметь хотя бы один такой scope в списке приоритетов.
 */
describe("fallback-scope'ы семантических токенов", () => {
  const THEMED_SCOPES = [
    "keyword.control",
    "support.type.property-name",
    "punctuation.section.embedded",
    "variable.other",
    "support.function",
    "string.quoted",
  ];
  const jsonScopes = manifest.contributes.semanticTokenScopes.find(
    (entry) => entry.language === "json",
  )?.scopes;

  it("объявлены для языка json", () => {
    expect(jsonScopes).toBeDefined();
  });

  for (const type of Object.values(SemanticTokenType)) {
    it(`${type} получает цвет во встроенных темах`, () => {
      const scopes = jsonScopes?.[type] ?? [];
      expect(scopes.length, `для '${type}' не объявлено ни одного scope`).toBeGreaterThan(0);
      const themed = scopes.some((scope) =>
        THEMED_SCOPES.some((known) => scope === known || scope.startsWith(`${known}.`)),
      );
      expect(themed, `ни один scope '${type}' не известен встроенным темам: ${scopes.join(", ")}`).toBe(
        true,
      );
    });
  }
});
