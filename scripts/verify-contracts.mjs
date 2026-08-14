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

function isHttpContract(label) {
  return label.replaceAll("\\", "/").includes("/http/");
}

const files = collectJson(contractsRoot);
if (files.length !== 11) {
  throw new Error(`Expected 11 contract documents, found ${files.length}`);
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

  if (isHttpContract(label)) {
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

for (const { contract, label } of parsedContracts.filter(({ label }) => !isHttpContract(label))) {
  try {
    ajv.addSchema(contract);
  } catch (error) {
    throw new Error(`${label} failed JSON Schema registration: ${error.message}`);
  }
}

for (const { contract, label } of parsedContracts.filter(({ label }) => !isHttpContract(label))) {
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

const judgeContract = parsedContracts.find(({ label }) => label.replaceAll("\\", "/") === "contracts/http/judge.openapi.json")?.contract;
const acceptJudgeSubmission = judgeContract?.paths?.["/internal/v1/judge/submissions"]?.post;
const readJudgeEvidence = judgeContract?.paths?.["/internal/v1/judge/submissions/{submissionId}/evidence"]?.get;
if (!acceptJudgeSubmission || !readJudgeEvidence) {
  throw new Error("judge v1 acceptance and safe-evidence operations must both be present");
}
if (readJudgeEvidence.responses?.["400"]?.$ref !== "#/components/responses/BadRequest") {
  throw new Error("judge v1 evidence read must declare its bad-request response");
}
const safeEvidence = judgeContract.components?.schemas?.SafeEvaluationEvidence;
if (!safeEvidence || safeEvidence.additionalProperties !== false) {
  throw new Error("judge safe evidence must be a closed schema");
}
for (const privateField of ["sourceCode", "hiddenTests", "compilerCommand", "rawDiagnostics"]) {
  if (Object.hasOwn(safeEvidence.properties ?? {}, privateField)) {
    throw new Error(`judge safe evidence exposes private field: ${privateField}`);
  }
}

const battleContract = parsedContracts.find(({ label }) => label.replaceAll("\\", "/") === "contracts/http/battle.openapi.json")?.contract;
const joinRankedQueue = battleContract?.paths?.["/api/v1/battle/ranked/queue"]?.post;
if (!joinRankedQueue) {
  throw new Error("battle v1 ranked queue operation must be present");
}
if (joinRankedQueue.security?.[0]?.bearerAuth?.[0] !== "battle.play") {
  throw new Error("battle v1 ranked queue must require battle.play authority");
}
const joinRequest = battleContract.components?.schemas?.RankedQueueJoinRequest;
if (!joinRequest || joinRequest.additionalProperties !== false || Object.hasOwn(joinRequest.properties ?? {}, "playerId")) {
  throw new Error("battle ranked queue request must be closed and derive player identity from JWT");
}

const battleEventV1 = ajv.getSchema("https://d3.local/contracts/websocket/battle-event.v1.schema.json");
if (!battleEventV1) throw new Error("battle event v1 validator was not preserved");
if (!battleEventV1({
  type: "MATCH_STATE",
  version: 1,
  matchId: "11111111-1111-4111-8111-111111111111",
  sequence: 3,
  serverTime: "2026-08-14T00:00:00Z",
  payload: {},
})) {
  throw new Error(`battle event v1 rejected its original shape: ${ajv.errorsText(battleEventV1.errors)}`);
}
const battleSnapshot = ajv.getSchema("https://d3.local/contracts/websocket/battle-event.v2.schema.json");
if (!battleSnapshot) throw new Error("battle snapshot validator was not created");
const battleSnapshotEvent = {
  type: "MATCH_SNAPSHOT",
  version: 2,
  matchId: "11111111-1111-4111-8111-111111111111",
  sequence: 4,
  serverTime: "2026-08-14T00:00:00Z",
  payload: {
    state: "RUNNING",
    startedAt: "2026-08-14T00:00:00Z",
    matchDeadline: "2026-08-14T00:10:00Z",
    self: {
      playerId: "22222222-2222-4222-8222-222222222222",
      ready: true,
      connectionState: "CONNECTED",
      reconnectDeadline: null,
    },
    opponent: {
      ready: true,
      connectionState: "DISCONNECTED",
      reconnectDeadline: "2026-08-14T00:00:30Z",
    },
    result: null,
  },
};
if (!battleSnapshot(battleSnapshotEvent)) {
  throw new Error(`battle snapshot valid sample was rejected: ${ajv.errorsText(battleSnapshot.errors)}`);
}
for (const privateField of ["playerId", "activeConnectionGeneration", "incidentReference", "sourceCode", "literal"]) {
  const unsafe = structuredClone(battleSnapshotEvent);
  unsafe.payload.opponent[privateField] = "private";
  if (battleSnapshot(unsafe)) {
    throw new Error(`battle snapshot accepted private field: ${privateField}`);
  }
}
const missingReconnectDeadline = structuredClone(battleSnapshotEvent);
missingReconnectDeadline.payload.opponent.reconnectDeadline = null;
if (battleSnapshot(missingReconnectDeadline)) {
  throw new Error("battle snapshot accepted a disconnected player without a reconnect deadline");
}
const clientOwnedClock = structuredClone(battleSnapshotEvent);
clientOwnedClock.payload.matchDeadline = null;
if (battleSnapshot(clientOwnedClock)) {
  throw new Error("battle snapshot accepted a running match without its server deadline");
}
const importedLegacyDraw = structuredClone(battleSnapshotEvent);
importedLegacyDraw.payload.state = "FINISHED";
importedLegacyDraw.payload.result = {
  outcome: "DRAW",
  winner: null,
  reason: "LEGACY_IMPORT",
  resolvedAt: "2026-08-14T00:10:00Z",
};
if (!battleSnapshot(importedLegacyDraw)) {
  throw new Error(`battle snapshot rejected an imported legacy draw: ${ajv.errorsText(battleSnapshot.errors)}`);
}
const legacyDrawWithWinner = structuredClone(importedLegacyDraw);
legacyDrawWithWinner.payload.result.winner = "SELF";
if (battleSnapshot(legacyDrawWithWinner)) {
  throw new Error("battle snapshot accepted a legacy draw with a winner");
}
const resultWithWinnerId = structuredClone(importedLegacyDraw);
resultWithWinnerId.payload.result.winnerId = "22222222-2222-4222-8222-222222222222";
if (battleSnapshot(resultWithWinnerId)) {
  throw new Error("battle snapshot accepted an absolute winner identifier");
}

console.log(`contracts: PASS (${files.length} JSON documents, 7 compiled JSON Schemas, privacy, Judge v1, and Battle v1/v2 samples)`);
