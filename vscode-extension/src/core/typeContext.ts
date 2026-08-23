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
  const fieldsIndex = keys.indexOf(fieldAccess.fieldsProperty);

  for (const [index, key] of keys.entries()) {
    if (typeContext.actionKeys.has(key)) return typeContext.actionCategory;
    if (belongsToFieldItself(keys, index, fieldsIndex)) {
      const overridden = typeContext.insideFieldsCategories.get(key);
      if (overridden !== undefined) return overridden;
    }
    const category = typeContext.keyCategories.get(key);
    if (category !== undefined) return category;
  }
  return fileKindCategory(fileKind);
}

/**
 * Ключ описывает **само поле**, а не действие внутри него.
 *
 * `fields.<f>.requirement` — требование поля (`field_requirement`), а
 * `fields.<f>.on_filled_actions[].requirement` — требование действия, и словарь
 * у него общий (`requirement`). Различает их action-контейнер между ключом и
 * `fields`: если он есть, переопределение «внутри fields» не применяется.
 */
function belongsToFieldItself(
  keys: readonly string[],
  index: number,
  fieldsIndex: number,
): boolean {
  if (fieldsIndex < 0) return false;
  return keys.slice(index + 1, fieldsIndex).every((key) => !typeContext.actionKeys.has(key));
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
