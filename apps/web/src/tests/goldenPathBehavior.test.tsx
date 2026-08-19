import { act } from "react";
import { createRoot } from "react-dom/client";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";

import { clearSession, requestSessionAccessToken, setSession } from "../api/session";
import { getRankedQueue, startRankedQueue } from "../battle/useRankedQueue";
import { formatElapsed, GoldenPathPage, type GoldenPathKind } from "../pages/GoldenPathPage";

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { headers: { "Content-Type": "application/json" }, status });
}

function LocationProbe() {
  return <output data-testid="location">{useLocation().pathname}</output>;
}

async function render(kind: GoldenPathKind, path: string) {
  const container = document.createElement("div");
  const root = createRoot(container);
  await act(async () => {
    const route = kind === "result" ? "/results/:matchId" : kind === "record" ? "/players/:playerId" : `/${kind}`;
    root.render(<MemoryRouter initialEntries={[path]}><Routes><Route path={route} element={<GoldenPathPage kind={kind} />} /></Routes><LocationProbe /></MemoryRouter>);
  });
  return { container, root };
}

afterEach(() => {
  localStorage.clear();
  clearSession();
  vi.unstubAllGlobals();
});

describe("P0 route behavior", () => {
  it("stores a login session and moves the user to the feed", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({ userId: "user-1", accessToken: "token" })));
    const { container, root } = await render("sign-in", "/sign-in");
    const email = container.querySelector<HTMLInputElement>('input[name="email"]')!;
    const password = container.querySelector<HTMLInputElement>('input[name="password"]')!;
    email.value = "user@example.com";
    password.value = "password123";
    await act(async () => { container.querySelector("form")!.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true })); await Promise.resolve(); });
    expect(container.querySelector("[data-testid=location]")?.textContent).toBe("/feed");
    root.unmount();
  });

  it("redirects an anonymous feed request to sign-in", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({ code: "SESSION_REQUIRED", message: "session required" }, 401)));
    const { container, root } = await render("feed", "/feed");
    await act(async () => { await Promise.resolve(); await Promise.resolve(); });
    expect(container.querySelector("[data-testid=location]")?.textContent).toBe("/sign-in");
    root.unmount();
  });

  it("shows a dedicated not-found result state", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({ code: "NOT_FOUND", message: "missing" }, 404)));
    const { container, root } = await render("result", "/results/00000000-0000-4000-8000-000000000001");
    await act(async () => { await Promise.resolve(); await Promise.resolve(); });
    expect(container.textContent).toContain("Match record not found");
    root.unmount();
  });

  it("uses a neutral outcome label before a reloaded result identifies the viewer", async () => {
    const match = { matchId: "00000000-0000-4000-8000-000000000001", playerOneUserId: "00000000-0000-4000-8000-000000000002", playerTwoUserId: "00000000-0000-4000-8000-000000000003", result: "PLAYER_ONE_WIN", ranked: true, sourceVersion: 1, projectedAt: "2026-08-17T00:00:00Z" };
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json(match)));
    const { container, root } = await render("result", "/results/00000000-0000-4000-8000-000000000001");
    await act(async () => { await Promise.resolve(); await Promise.resolve(); });
    expect(container.textContent).toContain("Player one victory");
    root.unmount();
  });

  it("formats the approved queue elapsed state from the authoritative enqueue time", () => {
    expect(formatElapsed("2026-08-17T00:00:00Z", Date.parse("2026-08-17T00:01:18Z"))).toBe("01:18");
  });

  it("replays the same queued ticket key after a local cancel", async () => {
    vi.stubGlobal("crypto", { randomUUID: () => "ticket-1" });
    const requestPermission = vi.fn();
    vi.stubGlobal("Notification", { permission: "default", requestPermission });
    setSession({ userId: "user-1", accessToken: "token" });
    const { container, root } = await render("ranked", "/ranked");

    await act(async () => { container.querySelector("form")!.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true })); await Promise.resolve(); });
    expect(requestPermission).toHaveBeenCalledTimes(1);
    const buttons = () => [...container.querySelectorAll("button")];
    await act(async () => { buttons().find((button) => button.textContent === "Pause polling")!.click(); await Promise.resolve(); });

    expect(container.querySelector("select")!.disabled).toBe(true);
    expect(container.querySelector("button[type=submit]")!.textContent).toBe("Retry existing queue");
    expect(getRankedQueue()?.idempotencyKey).toBe("ticket-1");

    await act(async () => { container.querySelector("form")!.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true })); await Promise.resolve(); });
    expect(getRankedQueue()).toMatchObject({ idempotencyKey: "ticket-1", status: "QUEUED" });
    root.unmount();
  });

  it("backs a refreshed session into an anonymous ranked queue marker", async () => {
    vi.stubGlobal("crypto", { randomUUID: () => "ticket-2" });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({ userId: "user-1", accessToken: "token" })));

    startRankedQueue("PYTHON3");

    await requestSessionAccessToken(true);

    expect(getRankedQueue()).toMatchObject({ idempotencyKey: "ticket-2", owner: "user-1" });
  });

  it("appends a player record page through its next cursor", async () => {
    const first = { matches: [{ matchId: "00000000-0000-4000-8000-000000000001", playerOneUserId: "00000000-0000-4000-8000-000000000002", playerTwoUserId: "00000000-0000-4000-8000-000000000003", result: "DRAW", ranked: true, sourceVersion: 1, projectedAt: "2026-08-17T00:00:00Z", players: [{ userId: "00000000-0000-4000-8000-000000000002", language: "PYTHON3", attempts: 2, peakTier: "Silver II", leaderboardPosition: 4, score: { total: 82, speed: 35, dynamicEfficiency: 32, submissionDiscipline: 15, calculationVersion: "v1", problemVersion: "demo-v1", runtimeVersion: "judge0", calibrationVersion: "v1" }, execution: { verdict: "ACCEPTED", passedCount: 8, totalCount: 8, runtimeMeasurements: [], adapterVersion: "judge0", runtimeVersion: "1", evidenceVersion: "v1" }, attacks: { launched: 1, targeted: 0, blocked: 0, reflected: 0 } }] }], nextCursor: "cursor-2" };
    const second = { matches: [{ ...first.matches[0], matchId: "00000000-0000-4000-8000-000000000004" }], nextCursor: null };
    const fetchMock = vi.fn().mockResolvedValueOnce(json(first)).mockResolvedValueOnce(json(second));
    vi.stubGlobal("fetch", fetchMock);
    const { container, root } = await render("record", "/players/00000000-0000-4000-8000-000000000002");
    await act(async () => { await Promise.resolve(); await Promise.resolve(); });
    await act(async () => { container.querySelector("button")!.click(); await Promise.resolve(); });
    expect(container.querySelectorAll(".golden-record")).toHaveLength(2);
    expect(container.textContent).toContain("leaderboard #4");
    expect(container.textContent).toContain("Score 82");
    expect(fetchMock.mock.calls[1][0]).toContain("cursor=cursor-2");
    root.unmount();
  });

  it("keeps a published post successful and clears its composer", async () => {
    const post = { authorUserId: "user-1", createdAt: "2026-08-17T00:00:00Z", id: "post-1", markdown: "hello", matchId: null, renderedHtml: "<p>hello</p>" };
    setSession({ userId: "user-1", accessToken: "token" });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValueOnce(json({ posts: [], nextCursor: null })).mockResolvedValueOnce(json(post, 201)));
    const { container, root } = await render("feed", "/feed");
    await act(async () => { await Promise.resolve(); await Promise.resolve(); });
    const composer = container.querySelector<HTMLFormElement>("form")!;
    const textarea = composer.querySelector<HTMLTextAreaElement>("textarea")!;
    textarea.value = "hello";
    await act(async () => { composer.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true })); await Promise.resolve(); await Promise.resolve(); });
    expect(container.textContent).toContain("Published to the visible feed.");
    expect(textarea.value).toBe("");
    root.unmount();
  });

  it("prefers the public author handle and falls back to a short user ID", async () => {
    setSession({ userId: "user-1", accessToken: "token" });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({
      posts: [
        { authorHandle: "alice", authorUserId: "11111111-1111-4111-8111-111111111111", createdAt: "2026-08-17T00:00:00Z", id: "post-1", markdown: "hello", matchId: null, renderedHtml: "<p>hello</p>" },
        { authorHandle: null, authorUserId: "22222222-2222-4222-8222-222222222222", createdAt: "2026-08-16T00:00:00Z", id: "post-2", markdown: "fallback", matchId: null, renderedHtml: "<p>fallback</p>" },
      ],
      nextCursor: null,
    })));

    const { container, root } = await render("feed", "/feed");
    await act(async () => { await Promise.resolve(); await Promise.resolve(); });

    expect(container.textContent).toContain("Author @alice");
    expect(container.textContent).toContain("Author 22222222");
    root.unmount();
  });
});
