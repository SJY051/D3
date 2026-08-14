import { createHash } from "node:crypto";
import { readdir, readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repositoryRoot = fileURLToPath(new URL("../", import.meta.url));
const servicesRoot = path.join(repositoryRoot, "services");
const manifestPath = fileURLToPath(new URL("./migration-checksums.json", import.meta.url));

async function listFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  const nested = await Promise.all(
    entries.map((entry) => {
      const absolutePath = path.join(directory, entry.name);
      return entry.isDirectory() ? listFiles(absolutePath) : [absolutePath];
    }),
  );
  return nested.flat();
}

function fail(message) {
  throw new Error(`migrations: FAIL (${message})`);
}

const manifest = JSON.parse(await readFile(manifestPath, "utf8"));
if (manifest.algorithm !== "sha256" || typeof manifest.files !== "object") {
  fail("invalid checksum manifest");
}

const migrationFiles = (await listFiles(servicesRoot))
  .filter(
    (file) =>
      file.endsWith(".sql") &&
      file.includes(
        `${path.sep}src${path.sep}main${path.sep}resources${path.sep}db${path.sep}migration${path.sep}`,
      ),
  )
  .map((file) => path.relative(repositoryRoot, file).split(path.sep).join("/"))
  .sort();
const manifestFiles = Object.keys(manifest.files).sort();

if (JSON.stringify(migrationFiles) !== JSON.stringify(manifestFiles)) {
  const missing = migrationFiles.filter((file) => !manifestFiles.includes(file));
  const stale = manifestFiles.filter((file) => !migrationFiles.includes(file));
  fail(`manifest coverage differs; missing=[${missing.join(", ")}], stale=[${stale.join(", ")}]`);
}

for (const relativePath of migrationFiles) {
  const contents = await readFile(path.join(repositoryRoot, relativePath));
  const actual = createHash("sha256").update(contents).digest("hex");
  const expected = manifest.files[relativePath];
  if (actual !== expected) {
    fail(`${relativePath} changed; add a forward migration instead of rewriting history`);
  }
}

console.log(`migrations: PASS (${migrationFiles.length} immutable Flyway migrations)`);
