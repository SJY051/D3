import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import test from "node:test";

import {
  assertSynchronousRunCompleted,
  createChildFailureReporter,
  createChildCompletionTracker,
  hasChildExited,
  isChildSpawnFailure,
  mergeRequestedExitCode,
  StartupCancelledError,
  terminateChild,
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

test("local runtime recognizes both normal and signal child completion", () => {
  assert.equal(hasChildExited({ exitCode: 0, signalCode: null }), true);
  assert.equal(hasChildExited({ exitCode: null, signalCode: "SIGKILL" }), true);
  assert.equal(hasChildExited({ exitCode: null, signalCode: "SIGTERM" }), true);
  assert.equal(hasChildExited({ exitCode: null, signalCode: null }), false);
});

test("local runtime reports only the first asynchronous child failure", () => {
  const failures = [];
  const report = createChildFailureReporter((failure) => failures.push(failure));

  assert.equal(report({ kind: "error", detail: "ENOENT" }), true);
  assert.equal(report({ kind: "exit", detail: "1" }), false);
  assert.deepEqual(failures, [{ kind: "error", detail: "ENOENT" }]);
});

test("local runtime marks a spawn-error child complete without an exit event", () => {
  const tracker = createChildCompletionTracker();
  const child = { pid: undefined, exitCode: null, signalCode: null };

  assert.equal(tracker.hasCompleted(child), false);
  tracker.markCompleted(child);
  assert.equal(tracker.hasCompleted(child), true);
});

test("local runtime distinguishes spawn failure from an error on a running child", () => {
  assert.equal(isChildSpawnFailure({ pid: undefined }), true);
  assert.equal(isChildSpawnFailure({ pid: 42 }), false);
});

test("local runtime waits for exit after escalating a running child to SIGKILL", async () => {
  const tracker = createChildCompletionTracker();
  const child = Object.assign(new EventEmitter(), {
    name: "fixture",
    pid: 42,
    exitCode: null,
    signalCode: null,
    signals: [],
    kill(signal) {
      this.signals.push(signal);
      if (signal === "SIGKILL") {
        setImmediate(() => {
          this.signalCode = signal;
          tracker.markCompleted(this);
          this.emit("exit", null, signal);
        });
      }
      return true;
    },
  });

  await terminateChild(child, tracker, { gracefulMillis: 1, forcedMillis: 50 });

  assert.deepEqual(child.signals, ["SIGTERM", "SIGKILL"]);
  assert.equal(tracker.hasCompleted(child), true);
});

test("local runtime reports cleanup failure when a child never exits", async () => {
  const tracker = createChildCompletionTracker();
  const child = Object.assign(new EventEmitter(), {
    name: "fixture",
    pid: 42,
    exitCode: null,
    signalCode: null,
    unrefCalls: 0,
    kill() {
      return false;
    },
    unref() {
      this.unrefCalls += 1;
    },
  });

  await assert.rejects(
    terminateChild(child, tracker, { gracefulMillis: 1, forcedMillis: 1 }),
    /fixture did not exit after SIGKILL/,
  );
  assert.equal(child.unrefCalls, 1);
});
