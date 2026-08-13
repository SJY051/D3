import { existsSync, readFileSync, readdirSync } from "node:fs";
import { dirname, extname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const contractsRoot = join(root, "contracts");

function collectJson(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) return collectJson(path);
    return extname(path) === ".json" ? [path] : [];
  });
}

const files = collectJson(contractsRoot);
if (files.length !== 10) {
  throw new Error(`Expected 10 contract documents, found ${files.length}`);
}

const parsedContracts = [];

for (const file of files) {
  const label = relative(root, file);
  let contract;
  try {
    contract = JSON.parse(readFileSync(file, "utf8"));
  } catch (error) {
    throw new Error(`${label} is not valid JSON: ${error.message}`);
  }

  if (label.includes("/http/")) {
    if (contract.openapi !== "3.1.0") throw new Error(`${label} must use OpenAPI 3.1.0`);
    if (!contract.info?.version) throw new Error(`${label} must declare info.version`);
    if (!contract.paths || typeof contract.paths !== "object") throw new Error(`${label} must declare paths`);
    if (!Array.isArray(contract["x-requirements"]) || contract["x-requirements"].length === 0) {
      throw new Error(`${label} must map at least one requirement`);
    }
  } else {
    if (contract.$schema !== "https://json-schema.org/draft/2020-12/schema") {
      throw new Error(`${label} must use JSON Schema 2020-12`);
    }
    if (!contract.$id?.startsWith("https://d3.local/contracts/")) {
      throw new Error(`${label} must have a stable D3 contract ID`);
    }
    if (!Array.isArray(contract["x-requirements"]) || contract["x-requirements"].length === 0) {
      throw new Error(`${label} must map at least one requirement`);
    }
  }

  for (const reference of JSON.stringify(contract).matchAll(/"\$ref":"([^"]+)"/g)) {
    const target = reference[1];
    if (!target.startsWith("#") && !existsSync(resolve(dirname(file), target))) {
      throw new Error(`${label} has an unresolved reference: ${target}`);
    }
  }

  parsedContracts.push({ contract, label });
}

const ajv = new Ajv2020({ allErrors: true, strict: true });
addFormats(ajv);
ajv.addKeyword("x-requirements");

for (const { contract, label } of parsedContracts.filter(({ label }) => !label.includes("/http/"))) {
  try {
    ajv.addSchema(contract);
  } catch (error) {
    throw new Error(`${label} failed JSON Schema registration: ${error.message}`);
  }
}

for (const { contract, label } of parsedContracts.filter(({ label }) => !label.includes("/http/"))) {
  try {
    if (!ajv.getSchema(contract.$id)) throw new Error("compiled validator was not created");
  } catch (error) {
    throw new Error(`${label} failed JSON Schema compilation: ${error.message}`);
  }
}

const matchFinished = ajv.getSchema("https://d3.local/contracts/events/match-finished.v1.schema.json");
const matchEvent = {
  eventId: "11111111-1111-4111-8111-111111111111",
  eventType: "match.finished",
  version: 1,
  occurredAt: "2026-08-13T12:00:00Z",
  correlationId: "contract-smoke",
  aggregateId: "22222222-2222-4222-8222-222222222222",
  aggregateVersion: 1,
  data: {
    matchId: "22222222-2222-4222-8222-222222222222",
    result: "PLAYER_ONE_WIN",
    ranked: true,
    playerIds: ["33333333-3333-4333-8333-333333333333", "44444444-4444-4444-8444-444444444444"],
  },
};

if (!matchFinished(matchEvent)) {
  throw new Error(`match.finished valid sample was rejected: ${ajv.errorsText(matchFinished.errors)}`);
}
if (matchFinished({ ...matchEvent, data: { ...matchEvent.data, sourceCode: "private" } })) {
  throw new Error("match.finished accepted private source outside its public contract");
}

console.log(`contracts: PASS (${files.length} JSON documents, 6 compiled JSON Schemas, privacy sample)`);
