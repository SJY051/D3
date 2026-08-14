import assert from "node:assert/strict";
import test from "node:test";

import {
  assertSynchronousRunCompleted,
  mergeRequestedExitCode,
  StartupCancelledError,
} from "./local-start-lifecycle.mjs";

test("local runtime preserves an application failure over a later operator signal", () => {
  assert.equal(mergeRequestedExitCode(undefined, 1), 1);
  assert.equal(mergeRequestedExitCode(1, 0), 1);
});

test("local runtime can upgrade a normal shutdown request when a failure is reported", () => {
  assert.equal(mergeRequestedExitCode(undefined, 0), 0);
  assert.equal(mergeRequestedExitCode(0, 1), 1);
});

test("local runtime treats operator signals during synchronous startup as cancellation", () => {
  for (const signal of ["SIGINT", "SIGTERM"]) {
    assert.throws(
      () => assertSynchronousRunCompleted({ status: null, signal }, "fixture"),
      (error) => error instanceof StartupCancelledError && error.signal === signal,
    );
  }
});

test("local runtime keeps synchronous command and unexpected signal failures actionable", () => {
  const spawnError = new Error("spawn failed");

  assert.throws(
    () => assertSynchronousRunCompleted({ error: spawnError, status: null, signal: null }, "fixture"),
    spawnError,
  );
  assert.throws(
    () => assertSynchronousRunCompleted({ status: 7, signal: null }, "fixture"),
    /fixture exited with 7/,
  );
  assert.throws(
    () => assertSynchronousRunCompleted({ status: null, signal: "SIGKILL" }, "fixture"),
    /fixture terminated by SIGKILL/,
  );
});
