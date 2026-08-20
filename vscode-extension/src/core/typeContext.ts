import { fieldAccess, typeContext, type RefKind } from "./contract";
import { propertyName, type Node } from "./ast";

/**
 * Категория ключевых слов для значения свойства `type` по контексту в AST.
 * Порт `SmartAppTypeContext.kt` — единый источник правды для подсветки и
 * автодополнения, чтобы тип filler'а не подсвечивался как валидный тип сценария.
 *
 * Категория ищется подъёмом по объемлющим объектам до первого распознанного
 * структурного ключа; `requirement` внутри `fields` трактуется как
 * `field_requirement`, а не как requirement уровня сценария.
 */
export function categoryFor(typeProperty: Node, fileKind: RefKind | undefined): string | undefined {
  const owner = typeProperty.parent;
  if (owner?.type !== "object") return fileKindCategory(fileKind);

  const keys = enclosingKeys(owner);
  const insideFields = keys.includes(fieldAccess.fieldsProperty);

  for (const key of keys) {
    if (typeContext.actionKeys.has(key)) return typeContext.actionCategory;
    if (insideFields) {
      const overridden = typeContext.insideFieldsCategories.get(key);
      if (overridden !== undefined) return overridden;
    }
    const category = typeContext.keyCategories.get(key);
    if (category !== undefined) return category;
  }
  return fileKindCategory(fileKind);
}

/** Категория по виду файла, когда структурный контекст не распознан. */
export function fileKindCategory(kind: RefKind | undefined): string | undefined {
  return kind === undefined ? undefined : typeContext.fileKindCategory.get(kind);
}

/**
 * Имена распознаваемых структурных ключей над [start] — от ближнего к дальнему.
 */
function enclosingKeys(start: Node): string[] {
  const keys: string[] = [];
  let object: Node | undefined = start;
  let guard = 0;

  while (object !== undefined && guard++ < 100) {
    const holder: Node | undefined = object.parent;
    if (holder === undefined) break;

    const holderProperty: Node | undefined =
      holder.type === "property"
        ? holder
        : holder.type === "array" && holder.parent?.type === "property"
          ? holder.parent
          : undefined;
    if (holderProperty === undefined) break;

    const keyName = propertyName(holderProperty);
    if (keyName !== undefined && typeContext.contextKeys.has(keyName)) keys.push(keyName);

    object = holderProperty.parent?.type === "object" ? holderProperty.parent : undefined;
  }
  return keys;
}
