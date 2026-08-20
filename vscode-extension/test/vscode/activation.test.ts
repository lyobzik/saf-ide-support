import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { paths } from "../../src/core/contract";

/**
 * Манифест — тоже место, где DSL-имена могут разъехаться с контрактом.
 * `activationEvents` раньше содержал `static/references` строкой: при изменении
 * корневых сегментов расширение не активировалось бы, пока пользователь не
 * откроет JSON-файл, и об этом никто бы не узнал.
 */
describe("манифест расширения", () => {
  const manifest = JSON.parse(
    readFileSync(join(process.cwd(), "package.json"), "utf8"),
  ) as { activationEvents: string[] };

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
