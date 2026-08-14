import { existsSync } from "node:fs";
import { spawn, spawnSync } from "node:child_process";

import { resolveLocalEnvironment } from "./local-start-config.mjs";

const windows = process.platform === "win32";
const gradle = windows ? "gradlew.bat" : "./gradlew";
const pnpm = windows ? "pnpm.cmd" : "pnpm";
const children = [];
let shuttingDown = false;

const environment = resolveLocalEnvironment();

const platform = [
  {
    name: "config",
    jar: "platform/config-server/build/libs/config-server-0.0.1-SNAPSHOT.jar",
    health: process.env.D3_CONFIG_HEALTH_URL ?? "http://localhost:8888/actuator/health",
    env: { ...environment, SPRING_PROFILES_ACTIVE: "native" },
  },
  {
    name: "discovery",
    jar: "platform/discovery-server/build/libs/discovery-server-0.0.1-SNAPSHOT.jar",
    health: process.env.D3_DISCOVERY_HEALTH_URL ?? "http://localhost:8761/actuator/health",
    env: environment,
  },
];

const applications = [
  ["identity", "services/identity-service/build/libs/identity-service-0.0.1-SNAPSHOT.jar",
    process.env.D3_IDENTITY_HEALTH_URL ?? "http://localhost:8081/actuator/health"],
  ["battle", "services/battle-service/build/libs/battle-service-0.0.1-SNAPSHOT.jar",
    process.env.D3_BATTLE_HEALTH_URL ?? "http://localhost:8082/actuator/health"],
  ["judge-service", "services/judge-service/build/libs/judge-service-0.0.1-SNAPSHOT.jar",
    process.env.D3_JUDGE_SERVICE_HEALTH_URL ?? "http://localhost:8083/actuator/health"],
  ["community", "services/community-service/build/libs/community-service-0.0.1-SNAPSHOT.jar",
    process.env.D3_COMMUNITY_HEALTH_URL ?? "http://localhost:8084/actuator/health"],
  ["gateway", "platform/api-gateway/build/libs/api-gateway-0.0.1-SNAPSHOT.jar",
    process.env.D3_GATEWAY_HEALTH_URL ?? "http://localhost:8080/actuator/health"],
].map(([name, jar, health]) => ({ name, jar, health, env: environment }));

function run(command, args) {
  const result = spawnSync(command, args, { cwd: process.cwd(), env: environment, stdio: "inherit" });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${command} exited with ${result.status}`);
}

function start(name, command, args, env = environment) {
  console.log(`local-start: starting ${name}`);
  const child = spawn(command, args, { cwd: process.cwd(), env, stdio: "inherit" });
  child.name = name;
  children.push(child);
  child.once("exit", (code, signal) => {
    if (!shuttingDown) {
      console.error(`local-start: ${name} exited early (${signal ?? code})`);
      void shutdown(1);
    }
  });
  return child;
}

function startJava(target) {
  if (!existsSync(target.jar)) throw new Error(`Missing application jar: ${target.jar}`);
  start(target.name, "java", ["-jar", target.jar], target.env);
}

async function waitForHealth(target, timeoutMillis = 60_000) {
  const deadline = Date.now() + timeoutMillis;
  while (Date.now() < deadline) {
    const child = children.find((candidate) => candidate.name === target.name);
    if (child?.exitCode !== null) throw new Error(`${target.name} exited before becoming healthy`);
    try {
      const response = await fetch(target.health, { signal: AbortSignal.timeout(1_500) });
      if (response.ok) {
        console.log(`local-start: ${target.name} healthy`);
        return;
      }
    } catch {
      // Retry until the bounded startup deadline.
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error(`${target.name} did not become healthy within ${timeoutMillis}ms`);
}

async function shutdown(exitCode = 0) {
  if (shuttingDown) return;
  shuttingDown = true;
  for (const child of children.toReversed()) {
    if (child.exitCode === null) child.kill("SIGTERM");
  }
  await Promise.all(children.map((child) => new Promise((resolve) => {
    if (child.exitCode !== null) return resolve();
    child.once("exit", resolve);
    setTimeout(() => {
      if (child.exitCode === null) child.kill("SIGKILL");
      resolve();
    }, 5_000).unref();
  })));
  process.exitCode = exitCode;
}

process.once("SIGINT", () => void shutdown(0));
process.once("SIGTERM", () => void shutdown(0));

try {
  run("docker", ["compose", "-f", "infra/compose.yaml", "up", "-d", "postgres", "redis", "kafka"]);
  run(gradle, ["bootJar", "--no-configuration-cache"]);

  platform.forEach(startJava);
  await Promise.all(platform.map((target) => waitForHealth(target)));

  applications.forEach(startJava);
  start("web", pnpm, ["--filter", "@d3/web", "dev", "--host", "127.0.0.1", "--strictPort"]);
  await Promise.all(applications.map((target) => waitForHealth(target)));

  run(process.execPath, ["scripts/demo-preflight.mjs"]);
  console.log("local-start: READY; press Ctrl+C to stop applications (infrastructure remains running)");
  await new Promise(() => {});
} catch (error) {
  console.error(`local-start: FAILED: ${error.message}`);
  await shutdown(1);
}
