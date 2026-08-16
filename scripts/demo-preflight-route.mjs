const GATEWAY_HEALTH_URL = "http://localhost:8080/actuator/health";
const MISSING_MATCH_PATH = "/api/v1/community/matches/00000000-0000-4000-8000-000000000000";
const MISSING_MATCH_CODE = "MATCH_RECORD_NOT_FOUND";
const CONVERGENCE_TIMEOUT_MS = 35_000;
const RETRY_INTERVAL_MS = 500;
const REQUEST_TIMEOUT_MS = 1_500;

const wait = (durationMs) => new Promise((resolve) => setTimeout(resolve, durationMs));

export function resolveGatewayCommunityRoute(environment = process.env) {
  try {
    const healthUrl = new URL(environment.D3_GATEWAY_HEALTH_URL ?? GATEWAY_HEALTH_URL);
    if (
      !["http:", "https:"].includes(healthUrl.protocol)
      || healthUrl.username !== ""
      || healthUrl.password !== ""
    ) {
      throw new Error("invalid gateway health URL");
    }

    const url = new URL(MISSING_MATCH_PATH, healthUrl.origin).toString();
    return { configured: true, url, target: url };
  } catch {
    return {
      configured: false,
      target: "gateway-community-route",
      error: "INVALID_GATEWAY_HEALTH_URL",
    };
  }
}

export async function probeGatewayCommunityRoute({
  url,
  fetchImpl = fetch,
  waitImpl = wait,
  now = Date.now,
  convergenceTimeoutMs = CONVERGENCE_TIMEOUT_MS,
  retryIntervalMs = RETRY_INTERVAL_MS,
  requestTimeoutMs = REQUEST_TIMEOUT_MS,
}) {
  const startedAt = now();
  const deadline = startedAt + convergenceTimeoutMs;
  let attempts = 0;
  let lastStatus;
  let lastFailure;

  while (now() < deadline) {
    attempts += 1;
    const remainingMs = deadline - now();

    try {
      const response = await fetchImpl(url, {
        method: "GET",
        redirect: "manual",
        signal: AbortSignal.timeout(Math.max(1, Math.min(requestTimeoutMs, remainingMs))),
      });

      if (response.status === 404) {
        const contractCode = await readContractCode(response);
        if (contractCode === MISSING_MATCH_CODE) {
          return {
            name: "gateway-community-route",
            target: url,
            required: true,
            kind: "route",
            ok: true,
            status: 404,
            contractCode,
            attempts,
            elapsedMs: now() - startedAt,
          };
        }
        return {
          name: "gateway-community-route",
          target: url,
          required: true,
          kind: "route",
          ok: false,
          status: 404,
          attempts,
          elapsedMs: now() - startedAt,
          error: "CONTRACT_MARKER_MISSING",
        };
      }

      if (response.status < 500 || response.status > 599) {
        return {
          name: "gateway-community-route",
          target: url,
          required: true,
          kind: "route",
          ok: false,
          status: response.status,
          attempts,
          elapsedMs: now() - startedAt,
          error: "UNEXPECTED_STATUS",
        };
      }

      lastStatus = response.status;
      lastFailure = "HTTP_5XX";
    } catch {
      lastStatus = undefined;
      lastFailure = "NETWORK_ERROR";
    }

    const retryDelayMs = Math.min(retryIntervalMs, deadline - now());
    if (retryDelayMs > 0) await waitImpl(retryDelayMs);
  }

  return {
    name: "gateway-community-route",
    target: url,
    required: true,
    kind: "route",
    ok: false,
    attempts,
    elapsedMs: now() - startedAt,
    error: "CONVERGENCE_TIMEOUT",
    ...(lastStatus === undefined ? {} : { lastStatus }),
    ...(lastFailure === undefined ? {} : { lastFailure }),
  };
}

async function readContractCode(response) {
  try {
    const body = await response.json();
    return typeof body === "object" && body !== null && !Array.isArray(body) && typeof body.code === "string"
      ? body.code
      : undefined;
  } catch {
    return undefined;
  }
}
