import { findNodeAtOffset, parseTree, type Node, type ParseError } from "jsonc-parser";

/**
 * Тонкие обёртки над `jsonc-parser` — замена PSI-навигации из плагина IDEA.
 *
 * Опции парсера выбраны не по вкусу, а по результату характеризации платформы
 * (`SmartAppStrictJsonContractTest`): JSON PSI IntelliJ **не** считает ошибкой
 * ни комментарии, ни висячие запятые, поэтому такие файлы обязаны обрабатываться
 * и здесь — иначе две IDE разойдутся на реальных файлах пользователей.
 */
const PARSE_OPTIONS = { allowTrailingComma: true, disallowComments: false } as const;

export type { Node, ParseError };

export interface ParsedDocument {
  readonly text: string;
  readonly root: Node | undefined;
  readonly errors: readonly ParseError[];
}

export function parseDocument(text: string): ParsedDocument {
  const errors: ParseError[] = [];
  const root = parseTree(text, errors, PARSE_OPTIONS);
  return { text, root, errors };
}

/** Свойство-владелец узла, если узел — ключ или значение свойства. */
export function propertyOf(node: Node): Node | undefined {
  return node.parent?.type === "property" ? node.parent : undefined;
}

/** `true`, если узел — значение (свойства или элемент массива), а не ключ. */
export function isValueNode(node: Node): boolean {
  const property = propertyOf(node);
  if (property === undefined) return true;
  return property.children?.[0] !== node;
}

/** Имя свойства (`undefined`, если узел не свойство или ключ не строка). */
export function propertyName(property: Node): string | undefined {
  const key = property.children?.[0];
  return key?.type === "string" ? (key.value as string) : undefined;
}

/** Значение свойства. */
export function propertyValue(property: Node): Node | undefined {
  return property.children?.[1];
}

/** Объект, которому принадлежит свойство. */
export function ownerObject(property: Node): Node | undefined {
  return property.parent?.type === "object" ? property.parent : undefined;
}

/** Значение свойства `type` у объекта — условие ссылочных правил. */
export function ownerType(object: Node): string | undefined {
  for (const property of object.children ?? []) {
    if (property.type !== "property") continue;
    if (propertyName(property) !== "type") continue;
    const value = propertyValue(property);
    return value?.type === "string" ? (value.value as string) : undefined;
  }
  return undefined;
}

/** Свойство с заданным именем в объекте (первое вхождение, как `findProperty`). */
export function findProperty(object: Node, name: string): Node | undefined {
  for (const property of object.children ?? []) {
    if (property.type === "property" && propertyName(property) === name) return property;
  }
  return undefined;
}

/**
 * Все свойства корневого объекта **в порядке документа, с дубликатами**.
 * Дубликаты значимы: одноимённые top-level ключи — легальный случай, и
 * навигация обязана предлагать оба определения.
 */
export function topLevelProperties(root: Node | undefined): Node[] {
  if (root?.type !== "object") return [];
  return (root.children ?? []).filter((child) => child.type === "property");
}

/** Ближайший объемлющий узел заданного типа. */
export function ancestorOfType(node: Node, type: Node["type"]): Node | undefined {
  let current = node.parent;
  while (current !== undefined) {
    if (current.type === type) return current;
    current = current.parent;
  }
  return undefined;
}

/**
 * Свойство верхнего уровня, внутри которого лежит [node] (определение сущности),
 * либо `undefined`.
 */
export function enclosingTopLevelProperty(node: Node): Node | undefined {
  let current: Node | undefined = node;
  while (current !== undefined) {
    if (current.type === "property" && current.parent?.parent === undefined) return current;
    current = current.parent;
  }
  return undefined;
}

/** Обходит все строковые узлы документа в порядке смещений. */
export function forEachStringNode(root: Node | undefined, visit: (node: Node) => void): void {
  if (root === undefined) return;
  const stack: Node[] = [root];
  const collected: Node[] = [];
  while (stack.length > 0) {
    const node = stack.pop() as Node;
    if (node.type === "string") collected.push(node);
    for (const child of node.children ?? []) stack.push(child);
  }
  collected.sort((a, b) => a.offset - b.offset);
  for (const node of collected) visit(node);
}

/** Узел, покрывающий смещение offset, либо undefined. */
export function nodeAt(root: Node | undefined, offset: number): Node | undefined {
  return root === undefined ? undefined : findNodeAtOffset(root, offset, true);
}

/** Ближайший строковый узел, покрывающий offset. */
export function stringNodeAt(root: Node | undefined, offset: number): Node | undefined {
  const node = nodeAt(root, offset);
  return node?.type === "string" ? node : undefined;
}
