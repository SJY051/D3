import { act } from "react";
import { createRoot } from "react-dom/client";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";

import { clearSession, setSession } from "../api/session";
import { getRankedQueue } from "../battle/useRankedQueue";
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
    await act(async () => { buttons().find((button) => button.textContent === "Cancel queue")!.click(); await Promise.resolve(); });

    expect(container.querySelector("select")!.disabled).toBe(true);
    expect(container.querySelector("button[type=submit]")!.textContent).toBe("Retry existing queue");
    expect(getRankedQueue()?.idempotencyKey).toBe("ticket-1");

    await act(async () => { container.querySelector("form")!.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true })); await Promise.resolve(); });
    expect(getRankedQueue()).toMatchObject({ idempotencyKey: "ticket-1", status: "QUEUED" });
    root.unmount();
  });

  it("appends a player record page through its next cursor", async () => {
    const first = { matches: [{ matchId: "00000000-0000-4000-8000-000000000001", playerOneUserId: "00000000-0000-4000-8000-000000000002", playerTwoUserId: "00000000-0000-4000-8000-000000000003", result: "DRAW", ranked: true, sourceVersion: 1, projectedAt: "2026-08-17T00:00:00Z" }], nextCursor: "cursor-2" };
    const second = { matches: [{ ...first.matches[0], matchId: "00000000-0000-4000-8000-000000000004" }], nextCursor: null };
    const fetchMock = vi.fn().mockResolvedValueOnce(json(first)).mockResolvedValueOnce(json(second));
    vi.stubGlobal("fetch", fetchMock);
    const { container, root } = await render("record", "/players/00000000-0000-4000-8000-000000000002");
    await act(async () => { await Promise.resolve(); await Promise.resolve(); });
    await act(async () => { container.querySelector("button")!.click(); await Promise.resolve(); });
    expect(container.querySelectorAll(".golden-record")).toHaveLength(2);
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
});
