#!/usr/bin/env node
/**
 * Проверка общих артефактов контракта: обе стороны читают одни и те же файлы из
 * `shared/`, поэтому валидация здесь — единственная schema-проверка в проекте
 * (Kotlin JSON-Schema-валидатор не тянет: он сам порождает rules.json).
 *
 * Падение этого скрипта останавливает сборку — тихая «пустая семантика»
 * недопустима.
 */
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import Ajv from "ajv";

const here = dirname(fileURLToPath(import.meta.url));
const sharedDir = join(here, "..", "..", "shared");

/**
 * Ожидаемая версия контракта читается из `src/core/contract.ts`, а не дублируется
 * здесь: бампов версии и без того три (Kotlin, схема, TS), и четвёртое место
 * ломало сборку уже после того, как остальные три были синхронизированы.
 */
const contractSource = readFileSync(join(here, "..", "src", "core", "contract.ts"), "utf8");
const versionMatch = /EXPECTED_CONTRACT_VERSION\s*=\s*(\d+)/.exec(contractSource);
if (versionMatch === null) {
  console.error("Contract verification failed:");
  console.error("  не удалось прочитать EXPECTED_CONTRACT_VERSION из src/core/contract.ts");
  process.exit(1);
}
const EXPECTED_CONTRACT_VERSION = Number(versionMatch[1]);

const readJson = (path) => JSON.parse(readFileSync(path, "utf8"));

const ajv = new Ajv({ allErrors: true, strict: false });
const failures = [];

const validate = (label, schemaPath, dataPath) => {
  const validator = ajv.compile(readJson(schemaPath));
  const data = readJson(dataPath);
  if (!validator(data)) {
    failures.push(
      `${label} does not match its schema:\n` +
        validator.errors.map((e) => `  ${e.instancePath || "/"} ${e.message}`).join("\n"),
    );
  }
  return data;
};

const rules = validate(
  "rules.json",
  join(sharedDir, "rules", "rules.schema.json"),
  join(sharedDir, "rules", "rules.json"),
);

const keywords = validate(
  "keywords.json",
  join(sharedDir, "keywords", "keywords.schema.json"),
  join(sharedDir, "keywords", "keywords.json"),
);

const userFields = validate(
  "user_fields.json",
  join(sharedDir, "keywords", "user_fields.schema.json"),
  join(sharedDir, "keywords", "user_fields.json"),
);

if (rules?._meta?.contractVersion !== EXPECTED_CONTRACT_VERSION) {
  failures.push(
    `contract version mismatch: rules.json has ${rules?._meta?.contractVersion}, ` +
      `the extension expects ${EXPECTED_CONTRACT_VERSION}`,
  );
}

const categories = Object.entries(keywords?.categories ?? {});
if (categories.length === 0 || categories.every(([, values]) => values.length === 0)) {
  failures.push("keyword dictionary is empty — highlighting would be silently disabled");
}

// Пустой пол — это молча выключенная семантика модели пользователя: на типовом
// приложении все имена приходят именно оттуда.
const userClasses = Object.entries(userFields?.classes ?? {});
if (userClasses.length === 0) {
  failures.push("user model snapshot has no classes — user semantics would be silently disabled");
}
if (userClasses.every(([, spec]) => spec.fields.length === 0)) {
  failures.push("user model snapshot declares no fields at all");
}
// Непустой снимок без класса по умолчанию — тот же молчаливый провал: у
// типового приложения `USER` не задан или назначает его наследника.
const defaultUserClass = rules?.userModel?.defaultClass;
const defaultUserSpec =
  defaultUserClass === undefined ? undefined : userFields?.classes?.[defaultUserClass];
if (defaultUserClass !== undefined && defaultUserSpec === undefined) {
  failures.push(`user model snapshot has no default class ${defaultUserClass}`);
} else if (defaultUserSpec !== undefined && defaultUserSpec.fields.length === 0) {
  // Наличия мало: пустой `fields` у класса по умолчанию — тот же потерянный
  // пол, только проверку присутствия он проходит. У промежуточных классов
  // (`Model`) пустой список законен, поэтому проверка адресная.
  failures.push(`default user class ${defaultUserClass} declares no fields`);
}

if (failures.length > 0) {
  console.error("Contract verification failed:\n" + failures.join("\n"));
  process.exit(1);
}

console.log(
  `Contract OK: version ${EXPECTED_CONTRACT_VERSION}, ` +
    `${rules.kinds.length} kinds, ${rules.refRules.length} ref rules, ` +
    `${categories.length} keyword categories, ${userClasses.length} user model classes`,
);
