import type { ParsedDocument } from "./ast";
import { kindOf } from "./files";
import type { RefKind } from "./contract";

/**
 * Политика строгости: какие файлы вносят определения в индекс.
 *
 * Строгость применяется **только к вкладу в индекс** — ровно как в плагине
 * IDEA, где `JsonPsi.hasError` вызывается лишь в индексаторах. Подсветка,
 * ссылки, диагностика и автодополнение продолжают работать и на битом файле:
 * гасить их на каждой незакрытой скобке во время набора — не то поведение,
 * которое есть сегодня у пользователей IDEA.
 */
export interface IndexEligibility {
  readonly eligible: boolean;
  readonly kind: RefKind | undefined;
}

/**
 * Файл вносит определения, если он лежит в `static/references/<kind>/`,
 * распарсился без ошибок, его корень — объект и после корня нет значимого
 * непробельного хвоста (`{ "a": {} } junk` — невалидный файл целиком).
 */
export function indexEligibility(uri: string, parsed: ParsedDocument): IndexEligibility {
  const kind = kindOf(uri);
  if (kind === undefined) return { eligible: false, kind: undefined };
  if (parsed.errors.length > 0) return { eligible: false, kind };
  if (parsed.root?.type !== "object") return { eligible: false, kind };
  if (hasTrailingContent(parsed)) return { eligible: false, kind };
  return { eligible: true, kind };
}

/**
 * Значимый контент после корневого значения. `jsonc-parser` такой хвост уже
 * помечает ошибкой, но проверка оставлена явной: в IDEA парсер его молча
 * игнорировал, и именно она там ловит `{ "a": {} } junk`.
 */
function hasTrailingContent(parsed: ParsedDocument): boolean {
  const root = parsed.root;
  if (root === undefined) return false;
  return parsed.text.slice(root.offset + root.length).trim().length > 0;
}
