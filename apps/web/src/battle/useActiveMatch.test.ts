import { afterEach, describe, expect, it, vi } from "vitest";

import { clearActiveMatch, getActiveMatch, setActiveMatch } from "./useActiveMatch";

afterEach(() => {
  localStorage.clear();
  vi.restoreAllMocks();
});

describe("active-match store", () => {
  it("records and clears the active match id", () => {
    expect(getActiveMatch()).toBeNull();
    setActiveMatch("m-1");
    expect(getActiveMatch()).toBe("m-1");
    clearActiveMatch();
    expect(getActiveMatch()).toBeNull();
  });

  it("survives a fresh read (simulated tab reopen)", () => {
    setActiveMatch("m-2");
    expect(getActiveMatch()).toBe("m-2");
  });

  it("notifies in-tab subscribers on change and skips no-op writes", () => {
    const handler = vi.fn();
    window.addEventListener("storage", handler);

    setActiveMatch("m-3");
    setActiveMatch("m-3"); // no-op, same value → no event
    clearActiveMatch();
    clearActiveMatch(); // no-op, already empty → no event

    window.removeEventListener("storage", handler);
    expect(handler).toHaveBeenCalledTimes(2);
  });
});
