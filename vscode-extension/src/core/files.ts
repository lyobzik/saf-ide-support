import { kindByDir, paths, type RefKind } from "./contract";

/**
 * Определяет, является ли файл reference-файлом SmartApp DSL, и если да — к
 * какому виду относится. Порт `SmartAppFiles.kt`: те же правила пути, только
 * поверх строкового URI вместо `VirtualFile`.
 *
 * Файл подходит, если его путь содержит подряд идущие сегменты
 * `static` -> `references` -> `<kind-dir>` (набор сегментов и расширение
 * приходят из контракта). После каталога вида допускаются произвольные
 * вложенные директории.
 */

/** Сегменты пути от корня вниз; пустые сегменты и схема URI отбрасываются. */
export function pathSegments(uri: string): string[] {
  const withoutScheme = uri.replace(/^[a-zA-Z][a-zA-Z0-9+.-]*:\/\//, "");
  const withoutQuery = withoutScheme.split(/[?#]/, 1)[0] ?? "";
  return decodeURIComponent(withoutQuery)
    .split("/")
    .filter((segment) => segment.length > 0);
}

function hasDslExtension(fileName: string): boolean {
  const { fileExtension, extensionIgnoreCase } = paths;
  if (fileName.length < fileExtension.length) return false;
  const tail = fileName.slice(-fileExtension.length);
  return extensionIgnoreCase
    ? tail.toLowerCase() === fileExtension.toLowerCase()
    : tail === fileExtension;
}

/** Вид сущности для [uri] или `undefined`, если файл не относится к DSL. */
export function kindOf(uri: string): RefKind | undefined {
  const segments = pathSegments(uri);
  const fileName = segments[segments.length - 1];
  if (fileName === undefined || !hasDslExtension(fileName)) return undefined;
  return kindFromSegments(segments);
}

export function isDslFile(uri: string): boolean {
  return kindOf(uri) !== undefined;
}

/**
 * Путь каталога `references`, которому принадлежит [uri], либо `undefined`.
 * Нужен, чтобы ограничивать межфайловый резолв одним набором определений: в
 * монорепозитории с несколькими `static/references` ссылки не должны утекать в
 * чужой набор.
 */
export function referencesRoot(uri: string): string | undefined {
  const segments = pathSegments(uri);
  const root = paths.rootSegments;
  // Файл сам сегментом каталога не является — начинаем с его родителя.
  for (let end = segments.length - 1; end >= root.length; end--) {
    const start = end - root.length;
    if (root.every((name, i) => segments[start + i] === name)) {
      return segments.slice(0, end).join("/");
    }
  }
  return undefined;
}

/**
 * Находит подряд идущие корневые сегменты, за которыми стоит каталог
 * известного вида.
 */
function kindFromSegments(segments: readonly string[]): RefKind | undefined {
  const root = paths.rootSegments;
  for (let i = 0; i + root.length < segments.length; i++) {
    if (!root.every((name, offset) => segments[i + offset] === name)) continue;
    const kind = kindByDir.get(segments[i + root.length] ?? "");
    if (kind !== undefined) return kind;
  }
  return undefined;
}

/**
 * Корень приложения: каталог, содержащий `static/references`. Относительно него
 * разрешаются `app_config.py`, модули и Python-файлы ресурсов.
 */
export function applicationRootOf(referencesRootPath: string): string {
  const segments = referencesRootPath.split("/");
  return segments.slice(0, Math.max(0, segments.length - paths.rootSegments.length)).join("/");
}

/**
 * Ближайший (самый глубокий) корень приложения, которому принадлежит путь.
 *
 * Корни могут вкладываться друг в друга (`project` и `project/subapp`), и файл
 * обязан принадлежать ровно одному: иначе Python-файл внутреннего приложения
 * попал бы в словарь внешнего.
 */
export function ownerApplicationRoot(
  path: string,
  applicationRoots: Iterable<string>,
): string | undefined {
  let owner: string | undefined;
  for (const root of applicationRoots) {
    if (path !== root && !path.startsWith(`${root}/`)) continue;
    if (owner === undefined || root.length > owner.length) owner = root;
  }
  return owner;
}
