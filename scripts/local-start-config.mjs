export function resolveLocalEnvironment(source = process.env) {
  const postgresHost = source.D3_POSTGRES_HOST ?? "localhost";
  const postgresPort = source.D3_POSTGRES_PORT ?? "5432";

  return {
    ...source,
    SERVER_ADDRESS: source.SERVER_ADDRESS ?? "127.0.0.1",
    SPRING_PROFILES_ACTIVE: "local",
    D3_JUDGE_ADAPTER: source.D3_JUDGE_ADAPTER ?? "fake",
    IDENTITY_DB_URL: `jdbc:postgresql://${postgresHost}:${postgresPort}/d3_identity`,
    BATTLE_DB_URL: `jdbc:postgresql://${postgresHost}:${postgresPort}/d3_battle`,
    JUDGE_DB_URL: `jdbc:postgresql://${postgresHost}:${postgresPort}/d3_judge`,
    COMMUNITY_DB_URL: `jdbc:postgresql://${postgresHost}:${postgresPort}/d3_community`,
  };
}

export function resolveLocalDependencyComposeArgs() {
  return [
    "compose",
    "-f",
    "infra/compose.yaml",
    "up",
    "-d",
    "--wait",
    "--wait-timeout",
    "60",
    "postgres",
    "redis",
    "kafka",
  ];
}
