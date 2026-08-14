import assert from "node:assert/strict";
import test from "node:test";

import { resolveJudge0Request, resolveJudgeAdapter } from "./demo-preflight-targets.mjs";

test("local default and explicit fake do not require Judge0", () => {
  assert.deepEqual(resolveJudgeAdapter({}), {
    name: "fake",
    supported: true,
    judge0Required: false,
  });
  assert.equal(resolveJudgeAdapter({ D3_JUDGE_ADAPTER: "fake" }).judge0Required, false);
});

test("the real adapter requires Judge0 and unknown adapters fail closed", () => {
  assert.deepEqual(resolveJudgeAdapter({ D3_JUDGE_ADAPTER: "judge0" }), {
    name: "judge0",
    supported: true,
    judge0Required: true,
  });
  assert.deepEqual(resolveJudgeAdapter({ D3_JUDGE_ADAPTER: "unknown" }), {
    name: "unknown",
    supported: false,
    judge0Required: false,
  });
});

test("Judge0 preflight uses only the configured authentication header", () => {
  assert.deepEqual(
    resolveJudge0Request({
      JUDGE0_HEALTH_URL: "http://judge0.internal:2358/about",
      JUDGE0_ALLOWED_ORIGIN: "http://judge0.internal:2358",
      JUDGE0_AUTH_HEADER: "X-D3-Judge-Token",
      JUDGE0_AUTH_TOKEN: "test-only-token",
    }),
    {
      configured: true,
      url: "http://judge0.internal:2358/about",
      target: "http://judge0.internal:2358/about",
      requestInit: {
        redirect: "manual",
        headers: { "X-D3-Judge-Token": "test-only-token" },
      },
    },
  );
});

test("Judge0 preflight fails closed without exposing a missing or invalid credential", () => {
  assert.deepEqual(resolveJudge0Request({}), {
    configured: false,
    target: "http://localhost:2358/about",
    error: "MISSING_AUTH_TOKEN",
  });
  assert.deepEqual(
    resolveJudge0Request({ JUDGE0_AUTH_HEADER: "bad header", JUDGE0_AUTH_TOKEN: "test-only-token" }),
    {
      configured: false,
      target: "http://localhost:2358/about",
      error: "INVALID_AUTH_CONFIGURATION",
    },
  );
  assert.deepEqual(
    resolveJudge0Request({
      JUDGE0_HEALTH_URL: "https://leaked@example.invalid/about?token=visible",
      JUDGE0_ALLOWED_ORIGIN: "https://example.invalid",
      JUDGE0_AUTH_TOKEN: "test-only-token",
    }),
    { configured: false, target: "judge0", error: "INVALID_ORIGIN" },
  );
  assert.deepEqual(
    resolveJudge0Request({
      JUDGE0_HEALTH_URL: "https://attacker.invalid/about",
      JUDGE0_ALLOWED_ORIGIN: "https://judge0.internal",
      JUDGE0_AUTH_TOKEN: "test-only-token",
    }),
    { configured: false, target: "judge0", error: "INVALID_ORIGIN" },
  );
});
