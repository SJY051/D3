import { readFileSync } from "node:fs";

const messagePath = process.argv[2];
if (!messagePath) throw new Error("Usage: node scripts/check-commit-message.mjs <message-file>");

const title = readFileSync(messagePath, "utf8").split("\n", 1)[0];
const conventional = /^(feat|fix|docs|style|refactor|perf|test|build|ci|chore|revert)(\([a-z0-9-]+\))?!?: .{1,72}$/;

if (!conventional.test(title)) {
  console.error("Commit title must be English Conventional Commit syntax and at most 72 characters after ': '.");
  process.exit(1);
}

console.log("commit-message: PASS");
