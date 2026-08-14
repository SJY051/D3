import { isIP } from "node:net";

export function resolveLocalEnvironment(source = process.env) {
  const postgresHost = source.D3_POSTGRES_HOST ?? "localhost";
  const postgresPort = source.D3_POSTGRES_PORT ?? "5432";
  const web = resolveLocalWebServer(source);
  const serverAddress = source.SERVER_ADDRESS ?? "127.0.0.1";
  const serviceHost = resolveLocalServiceHost(serverAddress);

  return {
    ...source,
    SERVER_ADDRESS: serverAddress,
    SPRING_PROFILES_ACTIVE: "local",
    D3_JUDGE_ADAPTER: source.D3_JUDGE_ADAPTER ?? "fake",
    D3_WEB_URL: web.health,
    D3_WEB_ORIGIN: web.health,
    CONFIG_SERVER_URL: `http://${serviceHost}:8888`,
    EUREKA_URL: `http://${serviceHost}:8761/eureka/`,
    D3_EUREKA_INSTANCE_HOSTNAME: serverAddress,
    D3_EUREKA_INSTANCE_IP_ADDRESS: serverAddress,
    D3_CONFIG_HEALTH_URL: `http://${serviceHost}:8888/actuator/health`,
    D3_DISCOVERY_HEALTH_URL: `http://${serviceHost}:8761/actuator/health`,
    D3_GATEWAY_HEALTH_URL: `http://${serviceHost}:8080/actuator/health`,
    D3_IDENTITY_HEALTH_URL: `http://${serviceHost}:8081/actuator/health`,
    D3_BATTLE_HEALTH_URL: `http://${serviceHost}:8082/actuator/health`,
    D3_JUDGE_SERVICE_HEALTH_URL: `http://${serviceHost}:8083/actuator/health`,
    D3_COMMUNITY_HEALTH_URL: `http://${serviceHost}:8084/actuator/health`,
    D3_JWT_JWK_SET_URI: `http://${serviceHost}:8081/.well-known/jwks.json`,
    D3_JWT_ISSUER: `http://${serviceHost}:8081`,
    IDENTITY_DB_URL: `jdbc:postgresql://${postgresHost}:${postgresPort}/d3_identity`,
    BATTLE_DB_URL: `jdbc:postgresql://${postgresHost}:${postgresPort}/d3_battle`,
    JUDGE_DB_URL: `jdbc:postgresql://${postgresHost}:${postgresPort}/d3_judge`,
    COMMUNITY_DB_URL: `jdbc:postgresql://${postgresHost}:${postgresPort}/d3_community`,
  };
}

export function resolveLocalServiceHost(serverAddress) {
  if (serverAddress === "localhost") return serverAddress;
  if (isIP(serverAddress) === 0 || ["0.0.0.0", "::"].includes(serverAddress)) {
    throw new Error("SERVER_ADDRESS must be a concrete local interface address");
  }
  return isIP(serverAddress) === 6 ? `[${serverAddress}]` : serverAddress;
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

export function resolveLocalWebServer(source = process.env) {
  const url = new URL(source.D3_WEB_URL ?? "http://localhost:5173");
  if (
    url.protocol !== "http:"
    || !["localhost", "127.0.0.1"].includes(url.hostname)
    || url.username !== ""
    || url.password !== ""
    || !["", "/"].includes(url.pathname)
    || url.search !== ""
    || url.hash !== ""
  ) {
    throw new Error("D3_WEB_URL must be an HTTP loopback origin");
  }

  return {
    name: "web",
    health: url.origin,
    host: "127.0.0.1",
    port: url.port || "80",
  };
}
