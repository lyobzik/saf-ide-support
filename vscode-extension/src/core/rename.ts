import { KIND, fieldAccess, type RefKind } from "./contract";
import { propertyName, propertyOf, stringNodeAt, type Node } from "./ast";
import { isJinja } from "./jinja";
import { targetKinds } from "./refRules";
import { fieldHits, registrationsAt, type DocumentContext } from "./semantics";
import type { SmartAppIndex } from "./index";

/**
 * Переименование определения сущности или поля формы вместе со всеми
 * использованиями. В IDEA это даётся PSI-ссылками бесплатно; здесь правки
 * собираются явно.
 */

export interface TextEdit {
  readonly uri: string;
  readonly start: number;
  readonly end: number;
  readonly newText: string;
}

export interface RenameTarget {
  readonly kind: "definition" | "field";
  /** Текущее имя — адаптер показывает его в диалоге переименования. */
  readonly name: string;
  /** Диапазон имени под кареткой (без кавычек). */
  readonly start: number;
  readonly end: number;
  readonly entityKind?: RefKind;
  readonly form?: string;
}

/**
 * Результат разбора позиции: либо цель переименования, либо причина отказа.
 * Причина нужна адаптеру, чтобы объяснить пользователю, почему F2 недоступен,
 * вместо безликого «здесь нельзя переименовать».
 */
export interface RenameLookup {
  readonly target?: RenameTarget;
  readonly reason?: string;
}

/** Разбирает позицию offset: что переименовывать и можно ли вообще. */
export function renameLookupAt(
  index: SmartAppIndex,
  context: DocumentContext,
  offset: number,
): RenameLookup {
  if (!index.isReady()) return { reason: "Индекс SmartApp DSL ещё строится" };
  if (context.kind === undefined) return {};

  const node = stringNodeAt(context.parsed.root, offset);
  if (node === undefined) return {};
  const value = node.value as string;

  if (isJinja(value)) {
    const hit = fieldHits(node, context).find((c) => offset >= c.start && offset <= c.end);
    if (hit === undefined) return {};
    return {
      target: { kind: "field", name: hit.field, start: hit.start, end: hit.end, form: hit.form },
    };
  }

  const property = propertyOf(node);
  const isKey = property !== undefined && property.children?.[0] === node;

  // Определение сущности: top-level ключ файла.
  if (isKey && property.parent?.parent === undefined) {
    return {
      target: {
        kind: "definition",
        name: value,
        start: node.offset + 1,
        end: node.offset + node.length - 1,
        entityKind: context.kind,
      },
    };
  }

  // Определение поля формы.
  if (isKey && context.kind === KIND.FORM) {
    const form = formOfFieldDefinition(property);
    if (form !== undefined) {
      return {
        target: {
          kind: "field",
          name: value,
          start: node.offset + 1,
          end: node.offset + node.length - 1,
          form,
        },
      };
    }
  }

  // Ключевое слово, зарегистрированное приложением: имя живёт и в Python-коде,
  // и в значениях `type` всех файлов приложения. Согласованно переписать их мы
  // не умеем, поэтому отказ с объяснением — тот же, что в плагине IDEA.
  if (registrationsAt(index, context, node).length > 0) {
    return {
      reason:
        `Ключевое слово '${value}' зарегистрировано в Python-коде приложения; ` +
        "переименование не поддерживается",
    };
  }

  // Ссылка на сущность. Правило может допускать несколько видов сразу
  // (`"action"` в external-обёртке ссылается и на action, и на behavior), и
  // выбирать первый попавшийся нельзя: молча переименуется не та сущность.
  // Смотрим, какие виды реально нашлись в воркспейсе.
  const kinds = targetKinds(node, context.kind);
  if (kinds.length === 0) return {};

  const resolvedKinds = [
    ...new Set(
      index
        .findDefinitions(value, kinds, context.scopeRoot)
        .map((definition) => definition.kind),
    ),
  ];

  if (resolvedKinds.length === 0) {
    return { reason: `Определение '${value}' не найдено — переименовывать нечего` };
  }
  if (resolvedKinds.length > 1) {
    const label = resolvedKinds.map((kind) => kind.toLowerCase()).join(" и ");
    return {
      reason:
        `Ссылка '${value}' неоднозначна: определения есть и как ${label}. ` +
        "Переименуйте нужное определение в его файле.",
    };
  }

  return {
    target: {
      kind: "definition",
      name: value,
      start: node.offset + 1,
      end: node.offset + node.length - 1,
      entityKind: resolvedKinds[0],
    },
  };
}

/** Цель переименования в позиции offset, если переименование возможно. */
export function renameTargetAt(
  index: SmartAppIndex,
  context: DocumentContext,
  offset: number,
): RenameTarget | undefined {
  return renameLookupAt(index, context, offset).target;
}

/** Правки, переименовывающие цель вместе со всеми её использованиями. */
export function renameEdits(
  index: SmartAppIndex,
  context: DocumentContext,
  offset: number,
  newName: string,
): TextEdit[] {
  const target = renameTargetAt(index, context, offset);
  if (target === undefined) return [];

  const edits: TextEdit[] = [];
  const add = (uri: string, start: number, end: number): void => {
    edits.push({ uri, start, end, newText: newName });
  };

  if (target.kind === "field" && target.form !== undefined) {
    for (const definition of index.findFields(target.form, target.name, context.scopeRoot)) {
      // Определение поля — ключ вместе с кавычками: правим только имя.
      add(definition.uri, definition.start + 1, definition.end - 1);
    }
    for (const usage of index.findFieldUsages(target.form, target.name, context.scopeRoot)) {
      add(usage.uri, usage.start, usage.end);
    }
    return dedupe(edits);
  }

  const entityKind = target.entityKind;
  if (entityKind === undefined) return [];

  for (const definition of index.findDefinitions(target.name, [entityKind], context.scopeRoot)) {
    add(definition.uri, definition.start + 1, definition.end - 1);
  }
  for (const usage of index.findUsages(target.name, entityKind, context.scopeRoot)) {
    // Диапазон использования — уже само имя, без кавычек.
    add(usage.uri, usage.start, usage.end);
  }
  return dedupe(edits);
}

/** Имя формы, если свойство — определение поля внутри `fields` формы. */
function formOfFieldDefinition(property: Node): string | undefined {
  const fieldsObject = property.parent;
  const fieldsProperty = fieldsObject?.parent;
  if (
    fieldsProperty?.type !== "property" ||
    propertyName(fieldsProperty) !== fieldAccess.fieldsProperty
  ) {
    return undefined;
  }
  const formProperty = fieldsProperty.parent?.parent;
  if (formProperty?.type !== "property" || formProperty.parent?.parent !== undefined) {
    return undefined;
  }
  return propertyName(formProperty);
}

function dedupe(edits: readonly TextEdit[]): TextEdit[] {
  const seen = new Set<string>();
  const result: TextEdit[] = [];
  for (const edit of edits) {
    const key = `${edit.uri} ${edit.start} ${edit.end}`;
    if (seen.has(key)) continue;
    seen.add(key);
    result.push(edit);
  }
  return result;
}
