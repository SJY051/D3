import { afterEach, describe, expect, it, vi } from "vitest";

import {
  clearRankedQueue,
  clearRankedQueueIfMatch,
  clearRankedQueueIfNotOwner,
  getRankedQueue,
  pauseRankedQueue,
  startRankedQueue,
  updateRankedQueue,
} from "./useRankedQueue";

afterEach(() => {
  localStorage.clear();
  vi.unstubAllGlobals();
});

describe("ranked-queue store", () => {
  it("keeps one idempotency key across pause and retry", () => {
    vi.stubGlobal("crypto", { randomUUID: () => "ticket-1" });

    startRankedQueue("PYTHON3", "user-a");
    pauseRankedQueue();
    const paused = getRankedQueue();
    startRankedQueue("JAVA", "user-a");

    expect(paused?.status).toBe("PAUSED");
    expect(getRankedQueue()).toMatchObject({
      idempotencyKey: "ticket-1",
      language: "PYTHON3",
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

  it("notifies subscribers and clears explicitly", () => {
    const handler = vi.fn();
    window.addEventListener("storage", handler);

    startRankedQueue("C");
    clearRankedQueue();

    window.removeEventListener("storage", handler);
    expect(handler).toHaveBeenCalledTimes(2);
  });
});
