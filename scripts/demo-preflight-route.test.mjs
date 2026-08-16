import assert from "node:assert/strict";
import test from "node:test";

import {
  probeGatewayCommunityRoute,
  resolveGatewayCommunityRoute,
} from "./demo-preflight-route.mjs";

const expectedRoute = "http://gateway.internal:8080/api/v1/community/matches/00000000-0000-4000-8000-000000000000";

test("the routed probe derives the Gateway origin from its health URL", () => {
  assert.deepEqual(
    resolveGatewayCommunityRoute({
      D3_GATEWAY_HEALTH_URL: "http://gateway.internal:8080/actuator/health",
    }),
    { configured: true, url: expectedRoute, target: expectedRoute },
  );
});

test("the routed probe retries transient failures until the contract 404 converges", async () => {
  let currentTime = 0;
  const responses = [
    new TypeError("connection refused"),
    { status: 503 },
    { status: 404, json: async () => ({ code: "MATCH_RECORD_NOT_FOUND" }) },
  ];
  const requestedUrls = [];

  const result = await probeGatewayCommunityRoute({
    url: expectedRoute,
    fetchImpl: async (url) => {
      requestedUrls.push(url);
      const response = responses.shift();
      if (response instanceof Error) throw response;
      return response;
    },
    waitImpl: async (durationMs) => {
      currentTime += durationMs;
    },
    now: () => currentTime,
    convergenceTimeoutMs: 100,
    retryIntervalMs: 10,
  });

  assert.deepEqual(result, {
    name: "gateway-community-route",
    target: expectedRoute,
    required: true,
    kind: "route",
    ok: true,
    status: 404,
    contractCode: "MATCH_RECORD_NOT_FOUND",
    attempts: 3,
    elapsedMs: 20,
  });
  assert.deepEqual(requestedUrls, [expectedRoute, expectedRoute, expectedRoute]);
});

test("the routed probe fails closed when transient responses do not converge", async () => {
  let currentTime = 0;
  const waits = [];

  const result = await probeGatewayCommunityRoute({
    url: expectedRoute,
    fetchImpl: async () => ({ status: 503, body: "must not be inspected" }),
    waitImpl: async (durationMs) => {
      waits.push(durationMs);
      currentTime += durationMs;
    },
    now: () => currentTime,
    convergenceTimeoutMs: 25,
    retryIntervalMs: 10,
  });

  assert.deepEqual(result, {
    name: "gateway-community-route",
    target: expectedRoute,
    required: true,
    kind: "route",
    ok: false,
    attempts: 3,
    elapsedMs: 25,
    error: "CONVERGENCE_TIMEOUT",
    lastStatus: 503,
    lastFailure: "HTTP_5XX",
  });
  assert.deepEqual(waits, [10, 10, 5]);
});

test("the routed probe rejects a Gateway-generated 404 without the Community contract marker", async () => {
  const result = await probeGatewayCommunityRoute({
    url: expectedRoute,
    fetchImpl: async () => ({ status: 404, json: async () => ({ error: "Not Found" }) }),
    waitImpl: async () => {
      throw new Error("a terminal contract mismatch must not retry");
    },
    now: () => 0,
    convergenceTimeoutMs: 100,
  });

  assert.deepEqual(result, {
    name: "gateway-community-route",
    target: expectedRoute,
    required: true,
    kind: "route",
    ok: false,
    status: 404,
    attempts: 1,
    elapsedMs: 0,
    error: "CONTRACT_MARKER_MISSING",
  });
});

test("the routed probe fails immediately on a terminal non-contract status", async () => {
  let fetchCalls = 0;
  let waitCalls = 0;

  const result = await probeGatewayCommunityRoute({
    url: expectedRoute,
    fetchImpl: async () => {
      fetchCalls += 1;
      return { status: 401, body: "private response content" };
    },
    waitImpl: async () => {
      waitCalls += 1;
    },
    now: () => 0,
    convergenceTimeoutMs: 100,
  });

  assert.deepEqual(result, {
    name: "gateway-community-route",
    target: expectedRoute,
    required: true,
    kind: "route",
    ok: false,
    status: 401,
    attempts: 1,
    elapsedMs: 0,
    error: "UNEXPECTED_STATUS",
  });
  assert.equal(fetchCalls, 1);
  assert.equal(waitCalls, 0);
});
