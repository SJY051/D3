const SUPPORTED_JUDGE_ADAPTERS = new Set(["fake", "judge0"]);

export function resolveJudgeAdapter(environment = process.env) {
  const name = environment.D3_JUDGE_ADAPTER ?? "fake";
  return {
    name,
    supported: SUPPORTED_JUDGE_ADAPTERS.has(name),
    judge0Required: name === "judge0",
  };
}
