import { act } from "react";
import { createRoot } from "react-dom/client";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, describe, expect, it } from "vitest";

import { AppShell } from "./AppShell";
import { clearSession, setSession } from "../api/session";
import { setActiveMatch } from "../battle/useActiveMatch";

const MATCH = "00000000-0000-4000-8000-000000000001";

async function renderAt(path: string) {
  const container = document.createElement("div");
  const root = createRoot(container);
  await act(async () => {
    root.render(
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/" element={<AppShell />}>
            <Route path="feed" element={<p>feed</p>} />
            <Route path="battles/:matchId" element={<p>battle</p>} />
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
  localStorage.clear();
  clearSession();
});

describe("rejoin banner", () => {
  it("shows on a non-battle route after a recorded match survives a fresh mount (tab reopen)", async () => {
    setActiveMatch(MATCH);
    const { container, root } = await renderAt("/feed");
    expect(banner(container)?.getAttribute("href")).toBe(`/battles/${MATCH}`);
    root.unmount();
  });

  it("is hidden while on that battle route", async () => {
    setActiveMatch(MATCH);
    const { container, root } = await renderAt(`/battles/${MATCH}`);
    expect(banner(container)).toBeNull();
    root.unmount();
  });

  it("disappears reactively when the active match is cleared on user switch", async () => {
    setSession({ accessToken: "a", userId: "user-a" });
    setActiveMatch(MATCH);
    const { container, root } = await renderAt("/feed");
    expect(banner(container)).not.toBeNull();
    await act(async () => { setSession({ accessToken: "b", userId: "user-b" }); });
    expect(banner(container)).toBeNull();
    root.unmount();
  });
});
