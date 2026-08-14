import assert from "node:assert/strict";
import test from "node:test";

import { resolveJudgeAdapter } from "./demo-preflight-targets.mjs";

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
