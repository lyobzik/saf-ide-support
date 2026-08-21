import { fileRefRules } from "./contract";
import { ownerObject, ownerType, propertyName, propertyOf, propertyValue, type Node } from "./ast";
import { isJinja } from "./jinja";

/**
 * Применяет таблицу файловых ссылок контракта к AST. Порт
 * `SmartAppFileRefRules.kt`: правила — данные, здесь только алгоритм.
 *
 * Отличие от `refRules`: цель — файл, найденный по пути внутри набора
 * `references`, а не top-level ключ из индекса. Само существование файла
 * проверяет вызывающий по реестру индекса — ядро в файловую систему не ходит.
 */

/** Каталоги поиска для узла в позиции файловой ссылки, иначе пустой список. */
export function searchDirs(node: Node): readonly string[] {
  if (node.type !== "string") return [];
  const property = propertyOf(node);
  if (property === undefined) return [];
  if (propertyValue(property) !== node) return [];

  const owner = ownerObject(property);
  if (owner === undefined) return [];

  const name = propertyName(property);
  const rule = fileRefRules.find((candidate) => candidate.property === name);
  if (rule === undefined) return [];
  if (rule.ownerTypes !== null) {
    const type = ownerType(owner);
    if (type === undefined || !rule.ownerTypes.includes(type)) return [];
  }
  return rule.searchDirs;
}

/** `true`, если узел стоит в позиции файловой ссылки. */
export function isFileReference(node: Node): boolean {
  return searchDirs(node).length > 0;
}

/**
 * URI-кандидаты файла для значения [value] относительно корня набора
 * [referencesRoot] — по одному на каталог поиска, в порядке правила.
 *
 * Значение — путь вместе с расширением: контракт имя файла не достраивает.
 * Вложенные пути допускаются, выход за пределы каталога (`..`, ведущий `/`,
 * обратный слэш) — нет: чем такое значение является, из имеющихся данных не
 * следует, и молчание честнее ложной ошибки. Динамическое имя (Jinja) кандидатов
 * не даёт — как и везде.
 */
export function candidateUris(
  node: Node,
  value: string,
  referencesRoot: string | undefined,
): string[] {
  const dirs = searchDirs(node);
  if (dirs.length === 0 || referencesRoot === undefined) return [];
  if (!isSafeRelativePath(value) || isJinja(value)) return [];
  return dirs.map((dir) => `${referencesRoot}/${dir}/${value}`);
}

function isSafeRelativePath(value: string): boolean {
  if (value.length === 0 || value.startsWith("/") || value.includes("\\")) return false;
  return value.split("/").every((segment) => segment.length > 0 && segment !== "." && segment !== "..");
}
