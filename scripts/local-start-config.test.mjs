import assert from "node:assert/strict";
import test from "node:test";

import {
  resolveLocalDependencyComposeArgs,
  resolveLocalEnvironment,
  resolveLocalWebServer,
} from "./local-start-config.mjs";

test("local runtime binds every Java application to loopback by default", () => {
  const environment = resolveLocalEnvironment({});

  assert.equal(environment.SERVER_ADDRESS, "127.0.0.1");
  assert.equal(environment.SPRING_PROFILES_ACTIVE, "local");
  assert.equal(environment.D3_JUDGE_ADAPTER, "fake");
});

test("local runtime preserves an explicit operator bind address", () => {
  const environment = resolveLocalEnvironment({
    SERVER_ADDRESS: "::1",
    D3_JUDGE_ADAPTER: "judge0",
  });

  assert.equal(environment.SERVER_ADDRESS, "::1");
  assert.equal(environment.D3_JUDGE_ADAPTER, "judge0");
});

test("local runtime derives every service database URL from the shared PostgreSQL endpoint", () => {
  const environment = resolveLocalEnvironment({
    D3_POSTGRES_HOST: "database.internal",
    D3_POSTGRES_PORT: "55432",
    IDENTITY_DB_URL: "jdbc:postgresql://stale.example:5432/d3_identity",
  });

  assert.equal(environment.IDENTITY_DB_URL, "jdbc:postgresql://database.internal:55432/d3_identity");
  assert.equal(environment.BATTLE_DB_URL, "jdbc:postgresql://database.internal:55432/d3_battle");
  assert.equal(environment.JUDGE_DB_URL, "jdbc:postgresql://database.internal:55432/d3_judge");
  assert.equal(environment.COMMUNITY_DB_URL, "jdbc:postgresql://database.internal:55432/d3_community");
});

test("local runtime waits for every Compose dependency to become healthy", () => {
  assert.deepEqual(resolveLocalDependencyComposeArgs(), [
    "compose",
    "-f",
    "infra/compose.yaml",
    "up",
    "-d",
    "--wait",
    "--wait-timeout",
    "60",
    "postgres",
    "redis",
    "kafka",
  ]);
});

test("local runtime derives the Vite binding and health target from D3_WEB_URL", () => {
  assert.deepEqual(resolveLocalWebServer({ D3_WEB_URL: "http://localhost:5174" }), {
    name: "web",
    health: "http://localhost:5174",
    host: "127.0.0.1",
    port: "5174",
  });
});

test("local runtime rejects a non-loopback Web origin", () => {
  assert.throws(
    () => resolveLocalWebServer({ D3_WEB_URL: "http://0.0.0.0:5173" }),
    /must be an HTTP loopback origin/,
  );
});
