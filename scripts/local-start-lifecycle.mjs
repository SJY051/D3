export function mergeRequestedExitCode(currentExitCode, nextExitCode) {
  if (currentExitCode === undefined || currentExitCode === 0) return nextExitCode;
  return currentExitCode;
}
