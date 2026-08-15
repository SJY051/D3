import assert from "node:assert/strict";
import test from "node:test";

import {
  resolveLocalDependencyComposeArgs,
  resolveLocalEnvironment,
  resolveLocalServiceHost,
  resolveLocalWebServer,
} from "./local-start-config.mjs";

test("local runtime binds every Java application to loopback by default", () => {
  const environment = resolveLocalEnvironment({});

  assert.equal(environment.SERVER_ADDRESS, "127.0.0.1");
  assert.equal(environment.SPRING_PROFILES_ACTIVE, "local");
  assert.equal(environment.D3_JUDGE_ADAPTER, "fake");
  assert.equal(environment.D3_BATTLE_SERVICE_CLIENT_SECRET, "local-battle-service-secret");
  assert.equal(environment.D3_IDENTITY_INTERNAL_URL, "http://127.0.0.1:8081");
});

test("local runtime aligns health, Config, Eureka, and JWT URLs with an explicit bind address", () => {
  const environment = resolveLocalEnvironment({
    SERVER_ADDRESS: "192.168.1.20",
    CONFIG_SERVER_URL: "http://localhost:8888",
    EUREKA_URL: "http://127.0.0.1:8761/eureka/",
    D3_CONFIG_HEALTH_URL: "http://localhost:8888/actuator/health",
    D3_JUDGE_ADAPTER: "judge0",
    D3_BATTLE_SERVICE_CLIENT_SECRET: "supplied-local-secret",
  });

  assert.equal(environment.SERVER_ADDRESS, "192.168.1.20");
  assert.equal(environment.CONFIG_SERVER_URL, "http://192.168.1.20:8888");
  assert.equal(environment.EUREKA_URL, "http://192.168.1.20:8761/eureka/");
  assert.equal(environment.D3_EUREKA_INSTANCE_HOSTNAME, "192.168.1.20");
  assert.equal(environment.D3_EUREKA_INSTANCE_IP_ADDRESS, "192.168.1.20");
  assert.equal(environment.D3_CONFIG_HEALTH_URL, "http://192.168.1.20:8888/actuator/health");
  assert.equal(environment.D3_DISCOVERY_HEALTH_URL, "http://192.168.1.20:8761/actuator/health");
  assert.equal(environment.D3_GATEWAY_HEALTH_URL, "http://192.168.1.20:8080/actuator/health");
  assert.equal(environment.D3_IDENTITY_HEALTH_URL, "http://192.168.1.20:8081/actuator/health");
  assert.equal(environment.D3_JWT_JWK_SET_URI, "http://192.168.1.20:8081/.well-known/jwks.json");
  assert.equal(environment.D3_JWT_ISSUER, "http://192.168.1.20:8081");
  assert.equal(environment.D3_IDENTITY_INTERNAL_URL, "http://192.168.1.20:8081");
  assert.equal(environment.D3_BATTLE_SERVICE_CLIENT_SECRET, "supplied-local-secret");
  assert.equal(environment.D3_JUDGE_ADAPTER, "judge0");
});

test("local runtime formats IPv6 service URLs and rejects wildcard bind addresses", () => {
  assert.equal(resolveLocalServiceHost("::1"), "[::1]");
  const environment = resolveLocalEnvironment({ SERVER_ADDRESS: "::1" });
  assert.equal(environment.SERVER_ADDRESS, "::1");
  assert.equal(environment.D3_EUREKA_INSTANCE_HOSTNAME, "[::1]");
  assert.equal(environment.D3_EUREKA_INSTANCE_IP_ADDRESS, "::1");
  assert.equal(environment.EUREKA_URL, "http://[::1]:8761/eureka/");
  assert.throws(
    () => resolveLocalEnvironment({ SERVER_ADDRESS: "0.0.0.0" }),
    /must be a concrete local interface address/,
  );
  assert.throws(
    () => resolveLocalEnvironment({ SERVER_ADDRESS: "::" }),
    /must be a concrete local interface address/,
  );
});

test("local runtime derives every service database URL from the shared PostgreSQL endpoint", () => {
  const environment = resolveLocalEnvironment({
    D3_POSTGRES_HOST: "database.internal",
    D3_POSTGRES_PORT: "55432",
    IDENTITY_DB_URL: "jdbc:postgresql://stale.example:5432/d3_identity",
  });

  assert.equal(environment.IDENTITY_DB_URL, "jdbc:postgresql://database.internal:55432/d3_identity");
  assert.equal(environment.BATTLE_DB_URL, "jdbc:postgresql://database.internal:55432/d3_battle");
  assert.equal(environment.JUDGE_DB_URL, "jdbc:postgresql://database.internal:55432/d3_judge");
  assert.equal(environment.COMMUNITY_DB_URL, "jdbc:postgresql://database.internal:55432/d3_community");
});

test("local runtime waits for every Compose dependency to become healthy", () => {
  assert.deepEqual(resolveLocalDependencyComposeArgs(), [
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
  ]);
});

test("local runtime derives the Vite binding and health target from D3_WEB_URL", () => {
  assert.deepEqual(resolveLocalWebServer({ D3_WEB_URL: "http://localhost:5174" }), {
    name: "web",
    health: "http://localhost:5174",
    host: "127.0.0.1",
    port: "5174",
  });
});

test("local runtime keeps the Gateway CORS origin aligned with the Web URL", () => {
  const environment = resolveLocalEnvironment({
    D3_WEB_URL: "http://localhost:5174",
    D3_WEB_ORIGIN: "http://localhost:5173",
  });

  assert.equal(environment.D3_WEB_URL, "http://localhost:5174");
  assert.equal(environment.D3_WEB_ORIGIN, "http://localhost:5174");
});

test("local runtime rejects a non-loopback Web origin", () => {
  assert.throws(
    () => resolveLocalWebServer({ D3_WEB_URL: "http://0.0.0.0:5173" }),
    /must be an HTTP loopback origin/,
  );
});
