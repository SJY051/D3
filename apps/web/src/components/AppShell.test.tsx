import { act } from "react";
import { createRoot } from "react-dom/client";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";

import { AppShell } from "./AppShell";
import { LiveBattlePage } from "../pages/LiveBattlePage";
import { clearSession, setSession } from "../api/session";
import { getActiveMatch, setActiveMatch } from "../battle/useActiveMatch";
import { startRankedQueue } from "../battle/useRankedQueue";

const MATCH = "00000000-0000-4000-8000-000000000001";

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { headers: { "Content-Type": "application/json" }, status });
}

function snapshotMessage(state = "RUNNING") {
  return JSON.stringify({
    type: "BATTLE_SNAPSHOT",
    version: 3,
    matchId: MATCH,
    sequence: 4,
    serverTime: "2026-08-15T09:30:00Z",
    payload: {
      match: {
        state,
        startedAt: "2026-08-15T09:29:00Z",
        matchDeadline: "2026-08-15T09:39:00Z",
        self: { playerId: "22222222-2222-4222-8222-222222222222", ready: true, connectionState: "CONNECTED", reconnectDeadline: null },
        opponent: { ready: true, connectionState: "CONNECTED", reconnectDeadline: null },
      },
      attack: { selfEnergy: 60, opponentEnergy: 40, maximumEnergy: 100, attackCost: 40, blockCost: 20, reflectCost: 30, current: null },
    },
  });
}

class FakeWebSocket {
  static OPEN = 1;
  static instances: FakeWebSocket[] = [];
  readyState = 1;
  onopen: (() => void) | null = null;
  onmessage: ((event: { data: string }) => void) | null = null;
  onerror: (() => void) | null = null;
  onclose: (() => void) | null = null;
  constructor(public url: string, public protocols?: string | string[]) {
    FakeWebSocket.instances.push(this);
  }
  send() {}
  close() { this.onclose?.(); }
}

async function renderShell(path: string) {
  const container = document.createElement("div");
  const root = createRoot(container);
  await act(async () => {
    root.render(
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/" element={<AppShell />}>
            <Route path="feed" element={<p>feed</p>} />
            <Route path="battles/:matchId" element={<LiveBattlePage />} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );
  });
  return { container, root };
}

function banner(container: HTMLElement) {
  return container.querySelector<HTMLAnchorElement>(".rejoin-action");
}

afterEach(() => {
  vi.useRealTimers();
  localStorage.clear();
  clearSession();
  vi.unstubAllGlobals();
  FakeWebSocket.instances.length = 0;
});

describe("rejoin banner", () => {
  it("shows on a non-battle route after a recorded match survives a fresh mount (tab reopen)", async () => {
    setActiveMatch(MATCH, "user-a");
    const { container, root } = await renderShell("/feed");
    expect(banner(container)?.getAttribute("href")).toBe(`/battles/${MATCH}`);
    root.unmount();
  });

  it("is hidden while on that battle route", async () => {
    setActiveMatch(MATCH, "user-a");
    setSession({ accessToken: "t", userId: "user-a" });
    const { container, root } = await renderShell(`/battles/${MATCH}`);
    expect(banner(container)).toBeNull();
    root.unmount();
  });

  it("clears reactively when a different user signs in on the same browser", async () => {
    setSession({ accessToken: "a", userId: "user-a" });
    setActiveMatch(MATCH, "user-a");
    const { container, root } = await renderShell("/feed");
    expect(banner(container)).not.toBeNull();
    clearSession(); // simulate session expiry (loses in-memory userId)
    await act(async () => { setSession({ accessToken: "b", userId: "user-b" }); });
    expect(banner(container)).toBeNull();
    root.unmount();
  });

  it("returns to the battle and receives the snapshot when the banner is clicked", async () => {
    vi.stubGlobal("WebSocket", FakeWebSocket as unknown as typeof WebSocket);
    setSession({ accessToken: "t", userId: "user-a" });
    setActiveMatch(MATCH, "user-a");

    const { container, root } = await renderShell("/feed");
    await act(async () => {
      banner(container)!.dispatchEvent(new MouseEvent("click", { bubbles: true, cancelable: true, button: 0 }));
    });
    // token promise resolves, LiveBattlePage opens the socket
    await act(async () => { await Promise.resolve(); });

    const socket = FakeWebSocket.instances.at(-1)!;
    expect(socket.url).toContain(`/ws/v1/battle/matches/${MATCH}`);
    await act(async () => {
      socket.onopen?.();
      socket.onmessage?.({ data: snapshotMessage() });
    });

    expect(container.textContent).toContain("Solution buffer");
    expect(getActiveMatch()).toBe(MATCH); // still recorded during a live match
    root.unmount();
  });

  it("keeps polling a ranked queue from the shell and exposes the matched battle", async () => {
    setSession({ accessToken: "t", userId: "user-a" });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({
      enqueuedAt: "2026-08-18T00:00:00Z",
      matchId: MATCH,
      status: "MATCHED",
    })));
    vi.stubGlobal("crypto", { randomUUID: () => "ticket-1" });
    startRankedQueue("PYTHON3", "user-a");

    const { container, root } = await renderShell("/feed");
    await act(async () => { await Promise.resolve(); await Promise.resolve(); });

    expect(container.textContent).toContain("Ranked match found");
    expect(banner(container)?.getAttribute("href")).toBe(`/battles/${MATCH}`);
    expect(getActiveMatch()).toBe(MATCH);
    root.unmount();
  });

  it("keeps polling after the API helper reaches its bounded retry limit", async () => {
    vi.useFakeTimers({ now: Date.parse("2026-08-18T00:00:10Z") });
    setSession({ accessToken: "t", userId: "user-a" });
    const fetchMock = vi.fn();
    for (let attempt = 0; attempt < 12; attempt += 1) {
      fetchMock.mockResolvedValueOnce(json({
        enqueuedAt: "2026-08-18T00:00:00Z",
        matchId: null,
        status: "QUEUED",
      }));
    }
    fetchMock.mockResolvedValueOnce(json({
      enqueuedAt: "2026-08-18T00:00:00Z",
      matchId: MATCH,
      status: "MATCHED",
    }));
    vi.stubGlobal("fetch", fetchMock);
    vi.stubGlobal("crypto", { randomUUID: () => "ticket-1" });
    startRankedQueue("PYTHON3", "user-a");

    const { container, root } = await renderShell("/feed");
    await act(async () => { await Promise.resolve(); });
    expect(container.textContent).toContain("Searching for ranked match · 00:10");

    await act(async () => {
      await vi.advanceTimersByTimeAsync(10_000);
    });

    expect(fetchMock).toHaveBeenCalledTimes(13);
    expect(container.textContent).toContain("Ranked match found");
    root.unmount();
  });
});
