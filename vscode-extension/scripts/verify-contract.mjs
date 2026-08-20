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

/** Ожидаемая версия контракта; обязана совпадать с SmartAppContract.VERSION. */
const EXPECTED_CONTRACT_VERSION = 2;

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

if (failures.length > 0) {
  console.error("Contract verification failed:\n" + failures.join("\n"));
  process.exit(1);
}

console.log(
  `Contract OK: version ${EXPECTED_CONTRACT_VERSION}, ` +
    `${rules.kinds.length} kinds, ${rules.refRules.length} ref rules, ` +
    `${categories.length} keyword categories`,
);
