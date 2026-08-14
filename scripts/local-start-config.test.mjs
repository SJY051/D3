import assert from "node:assert/strict";
import test from "node:test";

import { resolveLocalEnvironment } from "./local-start-config.mjs";

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
