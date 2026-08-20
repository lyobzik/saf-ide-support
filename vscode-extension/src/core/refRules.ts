import { refRules, type RefKind } from "./contract";
import { isValueNode, ownerObject, ownerType, propertyName, propertyOf, propertyValue, type Node } from "./ast";

/**
 * Применяет таблицу ссылочных правил контракта к AST. Порт `SmartAppRefRules.kt`:
 * сами правила — данные, здесь только алгоритм сопоставления.
 *
 * Jinja-значения отсекаются вызывающим до резолва; содержимое текста тут не
 * анализируется.
 */
export function targetKinds(node: Node, fileKind: RefKind | undefined): readonly RefKind[] {
  if (node.type !== "string") return [];
  const property = propertyOf(node);
  if (property === undefined) return [];
  // Узел должен быть *значением* свойства, а не его ключом.
  if (propertyValue(property) !== node) return [];

  const owner = ownerObject(property);
  if (owner === undefined) return [];

  const name = propertyName(property);
  const rule = refRules.find((candidate) => candidate.property === name);
  if (rule === undefined) return [];
  if (rule.fileKind !== null && rule.fileKind !== fileKind) return [];
  if (rule.ownerTypes !== null) {
    const type = ownerType(owner);
    if (type === undefined || !rule.ownerTypes.includes(type)) return [];
  }
  return rule.targets;
}

/** `true`, если узел стоит в любой ссылочной позиции. */
export function isReference(node: Node, fileKind: RefKind | undefined): boolean {
  return targetKinds(node, fileKind).length > 0;
}

/** `true`, если строковый узел — значение JSON, а не ключ свойства. */
export function isJsonValue(node: Node): boolean {
  return isValueNode(node);
}
