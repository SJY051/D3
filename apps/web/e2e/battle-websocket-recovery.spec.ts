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

test("D3-BTL-002 keeps the local demo channel alive and recovers one closed transport", async ({ page }) => {
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

      constructor() {
        runtime.sockets.push(this);
        window.setTimeout(() => {
          this.readyState = BrowserBattleSocket.OPEN;
          this.onopen?.(new Event("open"));
          this.onmessage?.(new MessageEvent("message", { data: initialSnapshot }));
        }, 0);
      }

      send(message: string) { runtime.commands.push(JSON.parse(message)); }
      close() { this.readyState = 3; }
      closeUnexpectedly() {
        this.readyState = 3;
        this.onclose?.(new CloseEvent("close"));
      }
    }

    Object.defineProperty(window, "WebSocket", { configurable: true, value: BrowserBattleSocket });
  }, { initialSnapshot: snapshot(1) });

  await page.goto(`/battles/${MATCH_ID}`);
  await page.clock.runFor(20_000);
  expect(await page.evaluate(() => (window as typeof window & { commands: unknown[] }).commands)).toContainEqual(
    expect.objectContaining({ type: "HEARTBEAT", version: 3, matchId: MATCH_ID }),
  );

  await page.evaluate(() => {
    const runtime = window as typeof window & { sockets: Array<{ closeUnexpectedly: () => void }> };
    runtime.sockets[0]?.closeUnexpectedly();
  });
  await page.clock.runFor(1_000);
  await expect(page.locator(".connection-pill")).toHaveText("LIVE");
  expect(await page.evaluate(() => (window as typeof window & { refreshes: number }).refreshes)).toBe(2);
});
