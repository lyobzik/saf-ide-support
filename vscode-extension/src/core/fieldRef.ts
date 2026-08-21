import { KIND, fieldAccess, type RefKind } from "./contract";
import {
  enclosingTopLevelProperty,
  findProperty,
  propertyName,
  propertyValue,
  type Node,
} from "./ast";
import { isJinja } from "./jinja";

/** Идентификатор поля формы: пара (форма, поле). */
export interface FormFieldRef {
  readonly form: string;
  readonly field: string;
}

/**
 * Целевая форма Jinja-ссылки и опознание определений полей. Порт
 * `SmartAppFieldRef.kt`.
 *
 * Целевая форма зависит от вида файла:
 * - в **сценарии** её называет top-level свойство `form` того же сценария.
 *   Искать `form` у ближайшего владельца было бы неверно: Jinja обычно стоит во
 *   вложенном action/field/question-объекте, а `form` лежит в top-level объекте;
 * - в **файле формы** `main_form` — это сама форма, внутри определения которой
 *   стоит узел: отдельного указателя на неё в файле нет.
 */
export function targetFormOf(node: Node, fileKind: RefKind | undefined): string | undefined {
  const topLevel = enclosingTopLevelProperty(node);
  if (topLevel === undefined) return undefined;

  if (fileKind === KIND.FORM) {
    const name = propertyName(topLevel);
    return name !== undefined && name.length > 0 ? name : undefined;
  }
  if (fileKind !== KIND.SCENARIO) return undefined;

  const scenarioObject = propertyValue(topLevel);
  if (scenarioObject?.type !== "object") return undefined;

  const formProperty = findProperty(scenarioObject, fieldAccess.formProperty);
  if (formProperty === undefined) return undefined;
  const formValue = propertyValue(formProperty);
  if (formValue?.type !== "string") return undefined;

  const formName = formValue.value as string;
  if (formName.length === 0) return undefined;
  // Динамическая форма не даёт однозначной цели — пропускаем без ошибки.
  if (isJinja(formName)) return undefined;
  return formName;
}

/**
 * `true`, если [property] — определение поля формы: прямой ребёнок объекта
 * `fields`, принадлежащего top-level определению формы в FORM-файле.
 */
export function isFieldDefinition(property: Node, fileKind: RefKind | undefined): boolean {
  if (fileKind !== KIND.FORM) return false;
  if (property.type !== "property") return false;

  const fieldsObject = property.parent;
  if (fieldsObject?.type !== "object") return false;
  const fieldsProperty = fieldsObject.parent;
  if (fieldsProperty?.type !== "property") return false;
  if (propertyName(fieldsProperty) !== fieldAccess.fieldsProperty) return false;

  const formObject = fieldsProperty.parent;
  if (formObject?.type !== "object") return false;
  const formProperty = formObject.parent;
  if (formProperty?.type !== "property") return false;
  // Определение формы — свойство корневого объекта файла.
  return formProperty.parent?.parent === undefined;
}
