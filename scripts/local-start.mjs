import { existsSync } from "node:fs";
import { spawn, spawnSync } from "node:child_process";

import {
  resolveLocalDependencyComposeArgs,
  resolveLocalEnvironment,
  resolveLocalWebServer,
} from "./local-start-config.mjs";
import {
  assertSynchronousRunCompleted,
  createChildCompletionTracker,
  createChildFailureReporter,
  isChildSpawnFailure,
  mergeRequestedExitCode,
  StartupCancelledError,
  terminateChild,
} from "./local-start-lifecycle.mjs";

const windows = process.platform === "win32";
const gradle = windows ? "gradlew.bat" : "./gradlew";
const pnpm = windows ? "pnpm.cmd" : "pnpm";
const children = [];
const childCompletion = createChildCompletionTracker();
let shuttingDown = false;
let requestedExitCode;
let shutdownTask;
let finishRuntime;
const startupAbort = new AbortController();
const runtimeFinished = new Promise((resolve) => {
  finishRuntime = resolve;
});

const environment = resolveLocalEnvironment();

const platform = [
  {
    name: "config",
    jar: "platform/config-server/build/libs/config-server-0.0.1-SNAPSHOT.jar",
    health: environment.D3_CONFIG_HEALTH_URL,
    env: { ...environment, SPRING_PROFILES_ACTIVE: "native" },
  },
  {
    name: "discovery",
    jar: "platform/discovery-server/build/libs/discovery-server-0.0.1-SNAPSHOT.jar",
    health: environment.D3_DISCOVERY_HEALTH_URL,
    env: environment,
  },
];

const applications = [
  ["identity", "services/identity-service/build/libs/identity-service-0.0.1-SNAPSHOT.jar",
    environment.D3_IDENTITY_HEALTH_URL],
  ["battle", "services/battle-service/build/libs/battle-service-0.0.1-SNAPSHOT.jar",
    environment.D3_BATTLE_HEALTH_URL],
  ["judge-service", "services/judge-service/build/libs/judge-service-0.0.1-SNAPSHOT.jar",
    environment.D3_JUDGE_SERVICE_HEALTH_URL],
  ["community", "services/community-service/build/libs/community-service-0.0.1-SNAPSHOT.jar",
    environment.D3_COMMUNITY_HEALTH_URL],
  ["gateway", "platform/api-gateway/build/libs/api-gateway-0.0.1-SNAPSHOT.jar",
    environment.D3_GATEWAY_HEALTH_URL],
].map(([name, jar, health]) => ({ name, jar, health, env: environment }));

function run(command, args) {
  const result = spawnSync(command, args, { cwd: process.cwd(), env: environment, stdio: "inherit" });
  assertSynchronousRunCompleted(result, command);
}

function start(name, command, args, env = environment) {
  console.log(`local-start: starting ${name}`);
  const child = spawn(command, args, { cwd: process.cwd(), env, stdio: "inherit" });
  child.name = name;
  children.push(child);
  const reportFailure = createChildFailureReporter(({ detail, defer }) => {
    const handleUnexpectedFailure = () => {
      if (shuttingDown) return;
      console.error(`local-start: ${name} ${detail}`);
      void shutdown(1);
    };
    if (defer) {
      setImmediate(handleUnexpectedFailure);
    } else {
      handleUnexpectedFailure();
    }
  });
  child.on("error", (error) => {
    const spawnFailed = isChildSpawnFailure(child);
    if (spawnFailed) childCompletion.markCompleted(child);
    reportFailure({
      detail: `${spawnFailed ? "failed to start" : "process error"} (${error.code ?? "unknown error"})`,
      defer: false,
    });
  });
  child.once("exit", (code, signal) => {
    childCompletion.markCompleted(child);
    reportFailure({
      detail: `exited early (${signal ?? code})`,
      defer: signal === "SIGINT" || signal === "SIGTERM",
    });
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
    if (shuttingDown) throw new StartupCancelledError();
    if (child && child.signalCode !== null) throw new Error(`${target.name} exited before becoming healthy (${child.signalCode})`);
    if (child && child.exitCode !== null) throw new Error(`${target.name} exited before becoming healthy`);
    try {
      const response = await fetch(target.health, {
        signal: AbortSignal.any([startupAbort.signal, AbortSignal.timeout(1_500)]),
      });
      if (response.ok) {
        console.log(`local-start: ${target.name} healthy`);
        return;
      }
    } catch (error) {
      if (startupAbort.signal.aborted) throw new StartupCancelledError();
      // Retry until the bounded startup deadline.
    }
    await waitForRetry(500);
  }
  throw new Error(`${target.name} did not become healthy within ${timeoutMillis}ms`);
}

function waitForRetry(delayMillis) {
  if (startupAbort.signal.aborted) throw new StartupCancelledError();
  return new Promise((resolve, reject) => {
    const onAbort = () => {
      clearTimeout(timer);
      reject(new StartupCancelledError());
    };
    const timer = setTimeout(() => {
      startupAbort.signal.removeEventListener("abort", onAbort);
      resolve();
    }, delayMillis);
    startupAbort.signal.addEventListener("abort", onAbort, { once: true });
  });
}

async function shutdown(exitCode = 0) {
  requestedExitCode = mergeRequestedExitCode(requestedExitCode, exitCode);
  if (shutdownTask) {
    process.exitCode = requestedExitCode;
    return shutdownTask;
  }
  shuttingDown = true;
  startupAbort.abort();
  shutdownTask = (async () => {
    const terminationResults = await Promise.allSettled(
      children.toReversed().map((child) => terminateChild(child, childCompletion)),
    );
    for (const result of terminationResults) {
      if (result.status !== "rejected") continue;
      requestedExitCode = mergeRequestedExitCode(requestedExitCode, 1);
      console.error(`local-start: cleanup failed: ${result.reason.message}`);
    }
    process.exitCode = requestedExitCode;
    finishRuntime();
  })();
  return shutdownTask;
}

process.once("SIGINT", () => void shutdown(0));
process.once("SIGTERM", () => void shutdown(0));

try {
  const web = resolveLocalWebServer();
  run("docker", resolveLocalDependencyComposeArgs());
  run(gradle, ["bootJar", "--no-configuration-cache"]);

  platform.forEach(startJava);
  await Promise.all(platform.map((target) => waitForHealth(target)));

  applications.forEach(startJava);
  start(web.name, pnpm, [
    "--filter", "@d3/web", "dev", "--host", web.host, "--port", web.port, "--strictPort",
  ]);
  await Promise.all([...applications, web].map((target) => waitForHealth(target)));

  run(process.execPath, ["scripts/demo-preflight.mjs"]);
  console.log("local-start: READY; press Ctrl+C to stop applications (infrastructure remains running)");
  await runtimeFinished;
} catch (error) {
  if (error instanceof StartupCancelledError) {
    await shutdown(requestedExitCode ?? 0);
  } else {
    console.error(`local-start: FAILED: ${error.message}`);
    await shutdown(1);
  }
}
