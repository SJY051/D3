import { existsSync, lstatSync, readFileSync, readdirSync } from "node:fs";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");

const requiredPaths = [
  "AGENTS.md",
  "CLAUDE.md",
  ".codex/hooks.json",
  "docs/specs/d3-mvp.md",
  "docs/wireframes/README.md",
  "contracts/http/identity.openapi.json",
  "contracts/events/envelope.v1.schema.json",
  "contracts/websocket/battle-event.v1.schema.json",
  "contracts/websocket/battle-event.v2.schema.json",
  "apps/web/src/app/AppRouter.tsx",
  "platform/api-gateway/build.gradle.kts",
  "services/identity-service/build.gradle.kts",
  "services/battle-service/build.gradle.kts",
  "services/judge-service/build.gradle.kts",
  "services/community-service/build.gradle.kts",
  "infra/compose.yaml",
  ".github/workflows/ci.yml",
];

const missing = requiredPaths.filter((path) => !existsSync(join(root, path)));
if (missing.length > 0) {
  throw new Error(`Missing scaffold paths:\n${missing.join("\n")}`);
}

const claudePointer = readFileSync(join(root, "CLAUDE.md"), "utf8");
if (claudePointer !== "@AGENTS.md\n") {
  throw new Error("CLAUDE.md must remain the one-line @AGENTS.md pointer");
}

const specification = readFileSync(join(root, "docs/specs/d3-mvp.md"), "utf8");
const requirementIds = [...new Set(specification.match(/D3-[A-Z]+-\d{3}/g) ?? [])].sort();
if (requirementIds.length !== 16) {
  throw new Error(`Expected 16 requirement IDs, found ${requirementIds.length}`);
}

const skeletonRoots = [
  "apps/web/src/tests",
  "apps/web/e2e",
  "platform/discovery-server/src/test",
  "platform/config-server/src/test",
  "platform/api-gateway/src/test",
  "services/identity-service/src/test",
  "services/battle-service/src/test",
  "services/judge-service/src/test",
  "services/community-service/src/test",
].map((path) => join(root, path));

function collectFiles(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = join(directory, entry.name);
    return entry.isDirectory() ? collectFiles(path) : [path];
  });
}

const skeletonFiles = skeletonRoots
  .flatMap(collectFiles)
  .filter((path) => /\.(java|ts|tsx)$/.test(path))
  .map((path) => ({ path, text: readFileSync(path, "utf8") }));

const unmapped = requirementIds.filter(
  (id) => !skeletonFiles.some(({ text }) => text.includes(id) && /@Disabled|describe\.skip|test\.skip/.test(text)),
);
if (unmapped.length > 0) {
  throw new Error(`Requirements without an explicit skipped-test marker: ${unmapped.join(", ")}`);
}

for (const entry of readdirSync(join(root, ".claude/skills"))) {
  const adapter = join(root, ".claude/skills", entry, "SKILL.md");
  if (lstatSync(adapter).isSymbolicLink()) {
    throw new Error(`Claude adapter must be a regular file: ${relative(root, adapter)}`);
  }
}

const routeShell = readFileSync(join(root, "apps/web/src/components/AppShell.tsx"), "utf8");
if (!routeShell.includes("Developer network · live coding arena")) {
  throw new Error("The web shell must expose the D³ product identity");
}

console.log(`structure: PASS (${requiredPaths.length} paths, ${requirementIds.length} requirement markers)`);
