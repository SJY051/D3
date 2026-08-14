import assert from "node:assert/strict";
import test from "node:test";

import { mergeRequestedExitCode } from "./local-start-lifecycle.mjs";

test("local runtime preserves an application failure over a later operator signal", () => {
  assert.equal(mergeRequestedExitCode(undefined, 1), 1);
  assert.equal(mergeRequestedExitCode(1, 0), 1);
});

test("local runtime can upgrade a normal shutdown request when a failure is reported", () => {
  assert.equal(mergeRequestedExitCode(undefined, 0), 0);
  assert.equal(mergeRequestedExitCode(0, 1), 1);
});
