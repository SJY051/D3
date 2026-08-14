export class StartupCancelledError extends Error {
  constructor(signal) {
    super(signal ? `Startup cancelled by ${signal}` : "Startup cancelled");
    this.name = "StartupCancelledError";
    this.signal = signal;
  }
}

export function assertSynchronousRunCompleted(result, command) {
  if (result.signal === "SIGINT" || result.signal === "SIGTERM") {
    throw new StartupCancelledError(result.signal);
  }
  if (result.error) throw result.error;
  if (result.signal) throw new Error(`${command} terminated by ${result.signal}`);
  if (result.status !== 0) throw new Error(`${command} exited with ${result.status}`);
}

export function createChildFailureReporter(onFailure) {
  let reported = false;
  return (failure) => {
    if (reported) return false;
    reported = true;
    onFailure(failure);
    return true;
  };
}

export function createChildCompletionTracker() {
  const completed = new WeakSet();
  return {
    markCompleted(child) {
      completed.add(child);
    },
    hasCompleted(child) {
      return completed.has(child) || hasChildExited(child);
    },
  };
}

export function hasChildExited(child) {
  return child.exitCode != null || child.signalCode != null;
}

export function isChildSpawnFailure(child) {
  return child.pid == null;
}

export function pipeChildOutput(child, stdout = process.stdout, stderr = process.stderr) {
  child.stdout.pipe(stdout, { end: false });
  child.stderr.pipe(stderr, { end: false });
}

function releaseChildResources(child) {
  for (const stream of [child.stdout, child.stderr]) {
    try {
      stream?.destroy();
    } catch {
      // Continue releasing the remaining resources.
    }
  }
  try {
    child.unref();
  } catch {
    // The caller still reports cleanup failure.
  }
}

export function terminateChild(
  child,
  completionTracker,
  { gracefulMillis = 5_000, forcedMillis = 1_000 } = {},
) {
  if (completionTracker.hasCompleted(child)) return Promise.resolve();

  return new Promise((resolve, reject) => {
    let settled = false;
    let gracefulTimer;
    let forcedTimer;

    const cleanup = () => {
      if (gracefulTimer) clearTimeout(gracefulTimer);
      if (forcedTimer) clearTimeout(forcedTimer);
      child.off("exit", handleExit);
    };
    const finish = () => {
      if (settled) return;
      settled = true;
      cleanup();
      resolve();
    };
    const fail = () => {
      if (settled) return;
      settled = true;
      cleanup();
      releaseChildResources(child);
      reject(new Error(`${child.name ?? "child"} did not exit after SIGKILL`));
    };
    const handleExit = () => finish();
    const requestSignal = (signal) => {
      try {
        child.kill(signal);
      } catch {
        // The bounded exit wait below decides whether cleanup actually succeeded.
      }
    };

    child.once("exit", handleExit);
    if (completionTracker.hasCompleted(child)) {
      finish();
      return;
    }

    requestSignal("SIGTERM");
    if (completionTracker.hasCompleted(child)) {
      finish();
      return;
    }

    gracefulTimer = setTimeout(() => {
      if (completionTracker.hasCompleted(child)) {
        finish();
        return;
      }
      requestSignal("SIGKILL");
      if (completionTracker.hasCompleted(child)) {
        finish();
        return;
      }
      forcedTimer = setTimeout(() => {
        if (completionTracker.hasCompleted(child)) finish();
        else fail();
      }, forcedMillis);
    }, gracefulMillis);
  });
}

export function mergeRequestedExitCode(currentExitCode, nextExitCode) {
  if (currentExitCode === undefined || currentExitCode === 0) return nextExitCode;
  return currentExitCode;
}
