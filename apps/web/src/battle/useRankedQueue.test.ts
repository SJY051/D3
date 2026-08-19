import { afterEach, describe, expect, it, vi } from "vitest";

import {
  claimRankedQueueOwner,
  clearRankedQueue,
  clearRankedQueueIfMatch,
  clearRankedQueueIfNotOwner,
  getRankedQueue,
  pauseRankedQueue,
  startRankedQueue,
  updateRankedQueue,
} from "./useRankedQueue";

afterEach(() => {
  vi.useRealTimers();
  localStorage.clear();
  vi.unstubAllGlobals();
});

describe("ranked-queue store", () => {
  it("keeps one idempotency key across pause and retry", () => {
    vi.stubGlobal("crypto", { randomUUID: () => "ticket-1" });

    startRankedQueue("PYTHON3", "user-a");
    pauseRankedQueue("CONFLICT");
    const paused = getRankedQueue();
    startRankedQueue("JAVA", "user-a");

    expect(paused?.status).toBe("PAUSED");
    expect(paused?.pausedBecause).toBe("CONFLICT");
    expect(getRankedQueue()).toMatchObject({
      idempotencyKey: "ticket-1",
      language: "PYTHON3",
      pausedBecause: null,
      status: "QUEUED",
    });
  });

  it("records a matched ticket and clears it by match or owner", () => {
    vi.stubGlobal("crypto", { randomUUID: () => "ticket-2" });
    startRankedQueue("JAVA", "user-a");
    updateRankedQueue({
      enqueuedAt: "2026-08-18T00:00:00Z",
      matchId: "00000000-0000-4000-8000-000000000001",
      status: "MATCHED",
    });

    expect(getRankedQueue()?.matchId).toBe("00000000-0000-4000-8000-000000000001");
    clearRankedQueueIfMatch("other");
    expect(getRankedQueue()).not.toBeNull();
    clearRankedQueueIfMatch("00000000-0000-4000-8000-000000000001");
    expect(getRankedQueue()).toBeNull();

    startRankedQueue("CPP", "user-a");
    clearRankedQueueIfNotOwner("user-b");
    expect(getRankedQueue()).toBeNull();
  });

  it("does not trust a mutated match id from storage", () => {
    vi.stubGlobal("crypto", { randomUUID: () => "ticket-3" });
    startRankedQueue("JAVA", "user-a");
    localStorage.setItem("d3.rankedQueue", JSON.stringify({
      ...getRankedQueue(),
      matchId: "../admin",
      status: "MATCHED",
    }));

    expect(getRankedQueue()).toBeNull();
  });

  it("notifies subscribers and clears explicitly", () => {
    const handler = vi.fn();
    window.addEventListener("storage", handler);

    startRankedQueue("C");
    clearRankedQueue();

    window.removeEventListener("storage", handler);
    expect(handler).toHaveBeenCalledTimes(2);
  });

  it("expires the cached queue state after the storage ttl", () => {
    vi.useFakeTimers({ now: Date.parse("2026-08-18T00:00:00Z") });
    vi.stubGlobal("crypto", { randomUUID: () => "ticket-4" });

    startRankedQueue("C");
    expect(getRankedQueue()).not.toBeNull();

    vi.setSystemTime(Date.parse("2026-08-18T00:16:00Z"));

    expect(getRankedQueue()).toBeNull();
    expect(localStorage.getItem("d3.rankedQueue")).toBeNull();
  });

  it("claims anonymous queues on refresh and clears them on explicit owner mismatch", () => {
    vi.stubGlobal("crypto", { randomUUID: () => "ticket-5" });

    startRankedQueue("C");
    claimRankedQueueOwner("user-a");

    expect(getRankedQueue()?.owner).toBe("user-a");

    clearRankedQueue();
    startRankedQueue("C");
    clearRankedQueueIfNotOwner("user-b");

    expect(getRankedQueue()).toBeNull();
  });

  it("skips duplicate queued ticket writes", () => {
    const handler = vi.fn();
    vi.stubGlobal("crypto", { randomUUID: () => "ticket-6" });
    window.addEventListener("storage", handler);

    startRankedQueue("C");
    updateRankedQueue({ enqueuedAt: "2026-08-18T00:00:00Z", matchId: null, status: "QUEUED" });
    updateRankedQueue({ enqueuedAt: "2026-08-18T00:00:00Z", matchId: null, status: "QUEUED" });

    window.removeEventListener("storage", handler);
    expect(handler).toHaveBeenCalledTimes(2);
  });
});
