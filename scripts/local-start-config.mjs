export function resolveLocalEnvironment(source = process.env) {
  return {
    ...source,
    SERVER_ADDRESS: source.SERVER_ADDRESS ?? "127.0.0.1",
    SPRING_PROFILES_ACTIVE: "local",
    D3_JUDGE_ADAPTER: source.D3_JUDGE_ADAPTER ?? "fake",
  };
}
