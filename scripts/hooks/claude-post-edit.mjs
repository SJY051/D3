import { spawnSync } from "node:child_process";
import { dirname, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
let input = "";
for await (const chunk of process.stdin) input += chunk;
if (!input.trim()) process.exit(0);

let event;
try {
  event = JSON.parse(input);
} catch {
  console.error("D3 hook received malformed JSON input");
  process.exit(2);
}

const editedPath = event.tool_input?.file_path ?? event.tool_input?.path;
if (!editedPath) process.exit(0);

const projectPath = relative(root, resolve(editedPath));
const affectsGuidance =
  projectPath === "AGENTS.md" ||
  projectPath === "CLAUDE.md" ||
  projectPath === ".claude/settings.json" ||
  projectPath.startsWith(".agents/skills/") ||
  projectPath.startsWith(".claude/skills/");

if (!affectsGuidance) process.exit(0);

const verification = spawnSync(process.execPath, ["scripts/verify-agent-config.mjs"], {
  cwd: root,
  stdio: "inherit",
});
process.exit(verification.status ?? 1);
