import assert from "node:assert/strict";
import { chmodSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawnSync } from "node:child_process";
import test from "node:test";

test("PostgreSQL initialization uses the same configurable service roles as applications", () => {
  const temporaryDirectory = mkdtempSync(join(tmpdir(), "d3-postgres-init-"));
  const capture = join(temporaryDirectory, "psql-arguments.txt");
  const fakePsql = join(temporaryDirectory, "psql");
  writeFileSync(fakePsql, "#!/bin/sh\nprintf '%s\\n' \"$@\" >> \"$D3_TEST_CAPTURE\"\ncat >/dev/null\n");
  chmodSync(fakePsql, 0o755);

  try {
    const result = spawnSync("sh", ["infra/postgres/init/01-create-databases.sh"], {
      cwd: process.cwd(),
      encoding: "utf8",
      env: {
        ...process.env,
        PATH: `${temporaryDirectory}:${process.env.PATH}`,
        D3_TEST_CAPTURE: capture,
        POSTGRES_USER: "admin",
        POSTGRES_DB: "postgres",
        IDENTITY_DB_USER: "identity_custom",
        BATTLE_DB_USER: "battle_custom",
        JUDGE_DB_USER: "judge_custom",
        COMMUNITY_DB_USER: "community_custom",
      },
    });

    assert.equal(result.status, 0, result.stderr);
    const argumentsLog = readFileSync(capture, "utf8");
    for (const role of ["identity_custom", "battle_custom", "judge_custom", "community_custom"]) {
      assert.match(argumentsLog, new RegExp(`--set=role=${role}(?:\\n|$)`));
    }
  } finally {
    rmSync(temporaryDirectory, { recursive: true, force: true });
  }
});

test("PostgreSQL initialization rejects shared and administrator service roles", () => {
  const commonEnvironment = {
    ...process.env,
    POSTGRES_USER: "admin",
    POSTGRES_DB: "postgres",
    IDENTITY_DB_USER: "identity_custom",
    BATTLE_DB_USER: "battle_custom",
    JUDGE_DB_USER: "judge_custom",
    COMMUNITY_DB_USER: "community_custom",
  };
  const script = "infra/postgres/init/01-create-databases.sh";

  const shared = spawnSync("sh", [script], {
    cwd: process.cwd(),
    encoding: "utf8",
    env: { ...commonEnvironment, BATTLE_DB_USER: "identity_custom" },
  });
  assert.notEqual(shared.status, 0);
  assert.match(shared.stderr, /service database roles must be distinct/);

  const administrator = spawnSync("sh", [script], {
    cwd: process.cwd(),
    encoding: "utf8",
    env: { ...commonEnvironment, JUDGE_DB_USER: "admin" },
  });
  assert.notEqual(administrator.status, 0);
  assert.match(administrator.stderr, /service database roles must differ from POSTGRES_USER/);
});
