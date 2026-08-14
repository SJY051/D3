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

export function mergeRequestedExitCode(currentExitCode, nextExitCode) {
  if (currentExitCode === undefined || currentExitCode === 0) return nextExitCode;
  return currentExitCode;
}
