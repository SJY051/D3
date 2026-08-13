import { createConnection } from "node:net";

const httpTargets = [
  ["web", process.env.D3_WEB_URL ?? "http://localhost:5173", true],
  ["discovery", process.env.D3_DISCOVERY_HEALTH_URL ?? "http://localhost:8761/actuator/health", true],
  ["config", process.env.D3_CONFIG_HEALTH_URL ?? "http://localhost:8888/actuator/health", true],
  ["gateway", process.env.D3_GATEWAY_HEALTH_URL ?? "http://localhost:8080/actuator/health", true],
  ["identity", process.env.D3_IDENTITY_HEALTH_URL ?? "http://localhost:8081/actuator/health", true],
  ["battle", process.env.D3_BATTLE_HEALTH_URL ?? "http://localhost:8082/actuator/health", true],
  ["judge-service", process.env.D3_JUDGE_SERVICE_HEALTH_URL ?? "http://localhost:8083/actuator/health", true],
  ["community", process.env.D3_COMMUNITY_HEALTH_URL ?? "http://localhost:8084/actuator/health", true],
  ["judge0", process.env.JUDGE0_HEALTH_URL ?? "http://localhost:2358/about", true],
];

const tcpTargets = [
  ["postgres", process.env.D3_POSTGRES_HOST ?? "localhost", Number(process.env.D3_POSTGRES_PORT ?? 5432), true],
  ["redis", process.env.D3_REDIS_HOST ?? "localhost", Number(process.env.D3_REDIS_PORT ?? 6379), true],
  ["kafka", process.env.D3_KAFKA_HOST ?? "localhost", Number(process.env.D3_KAFKA_PORT ?? 9092), true],
];

function checkTcp(name, host, port, required) {
  return new Promise((resolve) => {
    if (!Number.isInteger(port) || port < 1 || port > 65535) {
      resolve({ name, target: `${host}:${port}`, required, kind: "tcp", ok: false, error: "INVALID_PORT" });
      return;
    }

    const socket = createConnection({ host, port });
    let settled = false;
    const complete = (result) => {
      if (settled) return;
      settled = true;
      socket.destroy();
      resolve({ name, target: `${host}:${port}`, required, kind: "tcp", ...result });
    };

    socket.setTimeout(1500);
    socket.once("connect", () => complete({ ok: true }));
    socket.once("timeout", () => complete({ ok: false, error: "TIMEOUT" }));
    socket.once("error", (error) => complete({ ok: false, error: error.code ?? error.name }));
  });
}

const httpResults = await Promise.all(
  httpTargets.map(async ([name, url, required]) => {
    try {
      const response = await fetch(url, { signal: AbortSignal.timeout(1500) });
      return { name, target: url, required, kind: "http", ok: response.ok, status: response.status };
    } catch (error) {
      return { name, target: url, required, kind: "http", ok: false, error: error.cause?.code ?? error.name };
    }
  }),
);

const tcpResults = await Promise.all(tcpTargets.map((target) => checkTcp(...target)));
const results = [...httpResults, ...tcpResults];

for (const result of results) console.log(JSON.stringify(result));

const ready = results.every((result) => !result.required || result.ok);
console.log(`demo-preflight: ${ready ? "READY" : "NOT READY"}`);
process.exitCode = ready ? 0 : 1;
