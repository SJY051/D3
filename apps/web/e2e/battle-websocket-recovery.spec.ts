import { expect, test } from "@playwright/test";

const MATCH_ID = "11111111-1111-4111-8111-111111111111";

function snapshot(sequence: number): string {
  const now = Date.now();
  return JSON.stringify({
    type: "BATTLE_SNAPSHOT",
    version: 3,
    matchId: MATCH_ID,
    sequence,
    serverTime: new Date(now).toISOString(),
    payload: {
      match: {
        state: "RUNNING",
        startedAt: new Date(now - 60_000).toISOString(),
        matchDeadline: new Date(now + 9 * 60_000).toISOString(),
        self: {
          playerId: "22222222-2222-4222-8222-222222222222",
          ready: true,
          connectionState: "CONNECTED",
          reconnectDeadline: null,
        },
        opponent: { ready: true, connectionState: "CONNECTED", reconnectDeadline: null },
      },
      attack: {
        selfEnergy: 60,
        opponentEnergy: 40,
        maximumEnergy: 100,
        attackCost: 40,
        blockCost: 20,
        reflectCost: 30,
        current: null,
      },
    },
  });
}

test("D3-BTL-002 does not automatically reconnect after normal or protocol closes", async ({ page }) => {
  await page.clock.install();
  await page.addInitScript(({ initialSnapshot }) => {
    const runtime = window as typeof window & {
      commands: Array<Record<string, unknown>>;
      sockets: BrowserBattleSocket[];
      refreshes: number;
    };
    runtime.commands = [];
    runtime.sockets = [];
    runtime.refreshes = 0;
    window.fetch = async () => {
      runtime.refreshes += 1;
      return new Response(JSON.stringify({ accessToken: "header.payload.signature" }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    };

    class BrowserBattleSocket {
      static readonly OPEN = 1;
      readyState = 0;
      onopen: ((event: Event) => void) | null = null;
      onmessage: ((event: MessageEvent<string>) => void) | null = null;
      onerror: ((event: Event) => void) | null = null;
      onclose: ((event: CloseEvent) => void) | null = null;
      url: string;

      constructor(url: string) {
        this.url = url;
        runtime.sockets.push(this);
        window.setTimeout(() => {
          this.readyState = BrowserBattleSocket.OPEN;
          this.onopen?.(new Event("open"));
          this.onmessage?.(new MessageEvent("message", { data: initialSnapshot }));
        }, 0);
      }

      send(message: string) { runtime.commands.push(JSON.parse(message)); }
    }

    Object.defineProperty(window, "WebSocket", { configurable: true, value: BrowserBattleSocket });
  }, { initialSnapshot: snapshot(1) });

  await page.goto(`/battles/${MATCH_ID}`);
  await page.clock.runFor(20_000);
  expect(await page.evaluate(() => (window as typeof window & { commands: unknown[] }).commands)).toContainEqual(
    expect.objectContaining({ type: "HEARTBEAT", version: 3, matchId: MATCH_ID }),
  );
  await page.evaluate((matchId) => {
    const runtime = window as typeof window & {
      sockets: Array<{ url: string; onclose: ((event: CloseEvent) => void) | null }>;
    };
    runtime.sockets.find((socket) => socket.url.includes(`/ws/v1/battle/matches/${matchId}`))
      ?.onclose?.({ code: 1000 } as CloseEvent);
  }, MATCH_ID);
  await page.clock.runFor(1_000);
  await expect(page.locator(".connection-pill")).toHaveText("DISCONNECTED");
  expect(await page.evaluate((matchId) => {
    const runtime = window as typeof window & { sockets: Array<{ url: string }> };
    return runtime.sockets.filter((socket) => socket.url.includes(`/ws/v1/battle/matches/${matchId}`));
  }, MATCH_ID)).toHaveLength(1);
  expect(await page.evaluate(() => (window as typeof window & { refreshes: number }).refreshes)).toBe(1);

  await page.getByRole("button", { name: "Reconnect" }).click();
  await expect(page.locator(".connection-pill")).toHaveText("LIVE");
  await page.evaluate((matchId) => {
    const runtime = window as typeof window & {
      sockets: Array<{ url: string; onclose: ((event: CloseEvent) => void) | null }>;
    };
    runtime.sockets.filter((socket) => socket.url.includes(`/ws/v1/battle/matches/${matchId}`))[1]
      ?.onclose?.({ code: 1002 } as CloseEvent);
  }, MATCH_ID);
  await page.clock.runFor(1_000);
  await expect(page.locator(".connection-pill")).toHaveText("PROTOCOL_ERROR");
  expect(await page.evaluate((matchId) => {
    const runtime = window as typeof window & { sockets: Array<{ url: string }> };
    return runtime.sockets.filter((socket) => socket.url.includes(`/ws/v1/battle/matches/${matchId}`));
  }, MATCH_ID)).toHaveLength(2);
  expect(await page.evaluate(() => (window as typeof window & { refreshes: number }).refreshes)).toBe(2);
});
