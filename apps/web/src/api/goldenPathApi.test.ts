import { afterEach, describe, expect, it, vi } from "vitest";

import { clearSession, hasSession, requestSessionAccessToken } from "./session";
import { loadFeed, logout, signIn, waitForRankedMatch } from "./goldenPathApi";

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { headers: { "Content-Type": "application/json" }, status });
}

afterEach(() => {
  clearSession();
  vi.unstubAllGlobals();
});

describe("golden-path API adapter", () => {
  it("refreshes a protected feed request once after a 401", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ userId: "user-1", accessToken: "old-token" }))
      .mockResolvedValueOnce(json({ code: "EXPIRED", message: "expired" }, 401))
      .mockResolvedValueOnce(json({ userId: "user-1", accessToken: "new-token" }))
      .mockResolvedValueOnce(json({ posts: [], nextCursor: null }));
    vi.stubGlobal("fetch", fetchMock);

    await signIn("user@example.com", "password123");
    await expect(loadFeed()).resolves.toEqual({ posts: [], nextCursor: null });

    expect(fetchMock.mock.calls[1][1].headers.Authorization).toBe("Bearer old-token");
    expect(fetchMock.mock.calls[3][1].headers.Authorization).toBe("Bearer new-token");
  });

  it("replays one queue idempotency key until it is matched", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ userId: "user-1", accessToken: "token" }))
      .mockResolvedValueOnce(json({ status: "QUEUED", matchId: null, enqueuedAt: "2026-08-17T00:00:00Z" }))
      .mockResolvedValueOnce(json({ status: "MATCHED", matchId: "00000000-0000-4000-8000-000000000001", enqueuedAt: "2026-08-17T00:00:00Z" }));
    vi.stubGlobal("fetch", fetchMock);
    await signIn("user@example.com", "password123");

    const ticket = await waitForRankedMatch("PYTHON3", { onTicket: () => undefined, pollIntervalMs: 0, signal: new AbortController().signal });

    expect(ticket.status).toBe("MATCHED");
    expect(fetchMock.mock.calls[1][1].headers["Idempotency-Key"]).toBe(fetchMock.mock.calls[2][1].headers["Idempotency-Key"]);
  });

  it("retries a transient queue outage with the same idempotency key", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ userId: "user-1", accessToken: "token" }))
      .mockResolvedValueOnce(json({ code: "QUEUE_UNAVAILABLE", message: "lease contention", correlationId: "c-1" }, 503))
      .mockResolvedValueOnce(json({ status: "QUEUED", matchId: null, enqueuedAt: "2026-08-18T00:00:00Z" }))
      .mockResolvedValueOnce(json({ status: "MATCHED", matchId: "00000000-0000-4000-8000-000000000001", enqueuedAt: "2026-08-18T00:00:00Z" }));
    vi.stubGlobal("fetch", fetchMock);
    await signIn("user@example.com", "password123");

    const ticket = await waitForRankedMatch("PYTHON3", { onTicket: () => undefined, pollIntervalMs: 0, signal: new AbortController().signal });

    expect(ticket.status).toBe("MATCHED");
    expect(fetchMock).toHaveBeenCalledTimes(4);
    const keys = [1, 2, 3].map((call) => fetchMock.mock.calls[call][1].headers["Idempotency-Key"]);
    expect(keys[1]).toBe(keys[0]);
    expect(keys[2]).toBe(keys[0]);
  });

  it("keeps the session when logout revocation fails", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ userId: "user-1", accessToken: "token" }))
      .mockResolvedValueOnce(json({ code: "UNAVAILABLE", message: "identity outage" }, 503))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);
    await signIn("user@example.com", "password123");

    await expect(logout()).rejects.toThrow("LOGOUT_FAILED");
    expect(hasSession()).toBe(true);

    await expect(logout()).resolves.toBeUndefined();
    expect(hasSession()).toBe(false);
  });

  it("forces a fresh access token for an explicit battle reconnect", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(json({ userId: "user-1", accessToken: "first-token" })).mockResolvedValueOnce(json({ userId: "user-1", accessToken: "refreshed-token" }));
    vi.stubGlobal("fetch", fetchMock);
    await expect(requestSessionAccessToken()).resolves.toBe("first-token");
    await expect(requestSessionAccessToken(true)).resolves.toBe("refreshed-token");
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
});
