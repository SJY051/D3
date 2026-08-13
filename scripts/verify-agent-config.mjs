import { lstatSync, readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const skills = [
  "start-issue",
  "verify-change",
  "handoff",
  "demo-smoke",
  "spring-service",
  "realtime-battle",
  "judge0-boundary",
];
const manualSkills = new Set(["start-issue", "handoff", "demo-smoke"]);

function parseFrontmatter(path) {
  const source = readFileSync(path, "utf8");
  const match = source.match(/^---\n([\s\S]*?)\n---\n/);
  if (!match) throw new Error(`${path} has no YAML frontmatter`);

  const fields = Object.fromEntries(
    match[1].split("\n").map((line) => {
      const separator = line.indexOf(":");
      if (separator < 1) throw new Error(`${path} has malformed frontmatter`);
      return [line.slice(0, separator).trim(), line.slice(separator + 1).trim()];
    }),
  );

  const keys = Object.keys(fields).sort().join(",");
  if (keys !== "description,name") throw new Error(`${path} may contain only name and description frontmatter`);
  return { fields, source };
}

for (const skill of skills) {
  const canonicalPath = join(root, ".agents/skills", skill, "SKILL.md");
  const adapterPath = join(root, ".claude/skills", skill, "SKILL.md");
  const metadataPath = join(root, ".agents/skills", skill, "agents/openai.yaml");

  const canonical = parseFrontmatter(canonicalPath);
  const adapter = parseFrontmatter(adapterPath);
  if (canonical.fields.name !== skill || adapter.fields.name !== skill) {
    throw new Error(`${skill} frontmatter name does not match its directory`);
  }
  if (lstatSync(adapterPath).isSymbolicLink()) throw new Error(`${skill} adapter may not be a symlink`);
  if (!adapter.source.includes(`../../../.agents/skills/${skill}/SKILL.md`)) {
    throw new Error(`${skill} adapter does not point to the canonical skill`);
  }

  const metadata = readFileSync(metadataPath, "utf8");
  if (!metadata.includes(`$${skill}`)) throw new Error(`${skill} metadata prompt must name the skill`);
  if (manualSkills.has(skill) && !metadata.includes("allow_implicit_invocation: false")) {
    throw new Error(`${skill} must remain explicit-only`);
  }
  if (!manualSkills.has(skill) && metadata.includes("allow_implicit_invocation: false")) {
    throw new Error(`${skill} must remain available for implicit invocation`);
  }
  if (canonical.source.includes("TODO") || adapter.source.includes("TODO")) {
    throw new Error(`${skill} contains an unresolved TODO`);
  }
}

const settings = JSON.parse(readFileSync(join(root, ".claude/settings.json"), "utf8"));
const hook = settings.hooks?.PostToolUse?.[0]?.hooks?.[0];
if (hook?.command !== "node scripts/hooks/claude-post-edit.mjs") {
  throw new Error("Claude Code post-edit hook is missing or changed");
}

const codexHooks = JSON.parse(readFileSync(join(root, ".codex/hooks.json"), "utf8"));
const codexHookGroup = codexHooks.hooks?.PostToolUse?.[0];
const codexHook = codexHookGroup?.hooks?.[0];
if (codexHookGroup?.matcher !== "Edit|Write" || !codexHook?.command?.includes("verify-agent-config.mjs")) {
  throw new Error("Codex post-edit hook is missing or changed");
}

console.log(`agents: PASS (${skills.length} canonical skills, ${skills.length} Claude adapters, 2 harness hooks)`);
