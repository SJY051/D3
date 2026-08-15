import { expect, test } from "@playwright/test";

const MATCH_ID = "11111111-1111-4111-8111-111111111111";

type Attack = {
  attackId: string;
  phase: "WARNING" | "ACTIVE" | "RESOLVED";
  target: "SELF" | "OPPONENT";
  warningDeadline: string;
  overlayExpiresAt: string | null;
  overlaySeed: number;
  resolution: "BLOCKED" | "EXPIRED" | null;
} | null;

function snapshot(sequence: number, attack: Attack): string {
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
        opponent: {
          ready: true,
          connectionState: "CONNECTED",
          reconnectDeadline: null,
        },
      },
      attack: {
        selfEnergy: 70,
        opponentEnergy: 45,
        current: attack,
      },
    },
  });
}

test("D3-BTL-004 keeps source intact through warning, reflect, overlay, and expiry", async ({ page }) => {
  const initialSnapshot = snapshot(1, null);
  await page.addInitScript(({ firstSnapshot }) => {
    const runtime = window as typeof window & {
      __d3Commands: unknown[];
      __d3Refreshes: number;
      __d3PushSnapshot: (message: string) => void;
      __d3Socket: { protocols: string[]; url: string };
    };
    runtime.__d3Commands = [];
    runtime.__d3Refreshes = 0;
    window.fetch = async () => {
      runtime.__d3Refreshes += 1;
      return new Response(JSON.stringify({
        userId: "22222222-2222-4222-8222-222222222222",
        accessToken: "header.payload.signature",
      }), {
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

      constructor(url: string, protocols: string[]) {
        runtime.__d3Socket = { protocols, url };
        runtime.__d3PushSnapshot = (message: string) => {
          this.onmessage?.(new MessageEvent("message", { data: message }));
        };
        window.setTimeout(() => {
          this.readyState = BrowserBattleSocket.OPEN;
          this.onopen?.(new Event("open"));
          runtime.__d3PushSnapshot(firstSnapshot);
        }, 0);
      }

      send(message: string) {
        runtime.__d3Commands.push(JSON.parse(message));
      }

      close() {
        this.readyState = 3;
      }
    }

    Object.defineProperty(window, "WebSocket", {
      configurable: true,
      value: BrowserBattleSocket,
    });
  }, { firstSnapshot: initialSnapshot });

  await page.goto(`/battles/${MATCH_ID}`);
  const editor = page.getByLabel("Solution source");
  const socketBoundary = await page.evaluate(() => {
    const runtime = window as typeof window & { __d3Socket: { protocols: string[]; url: string } };
    return runtime.__d3Socket;
  });
  expect(socketBoundary).toEqual({
    protocols: ["d3.battle.v3", "d3.jwt.header.payload.signature"],
    url: `ws://127.0.0.1:5173/ws/v1/battle/matches/${MATCH_ID}`,
  });
  const refreshCount = await page.evaluate(() => {
    const runtime = window as typeof window & { __d3Refreshes: number };
    return runtime.__d3Refreshes;
  });
  expect(refreshCount).toBe(1);
  await expect(editor).toBeVisible();
  const source = "function solve(input) {\n  return input.trim();\n}";
  await editor.fill(source);

  const warningDeadline = new Date(Date.now() + 30_000).toISOString();
  await page.evaluate((message) => {
    const runtime = window as typeof window & { __d3PushSnapshot: (value: string) => void };
    runtime.__d3PushSnapshot(message);
  }, snapshot(2, {
    attackId: "attack-browser-1",
    phase: "WARNING",
    target: "SELF",
    warningDeadline,
    overlayExpiresAt: null,
    overlaySeed: 8844,
    resolution: null,
  }));

  await expect(page.getByRole("status")).toContainText("Incoming attack");
  await expect(page.getByRole("button", { name: "Block" })).toBeEnabled();
  await expect(page.getByRole("button", { name: "Reflect" })).toBeEnabled();
  await expect(editor).toHaveValue(source);

  await page.getByRole("button", { name: "Reflect" }).click();
  const commands = await page.evaluate(() => {
    const runtime = window as typeof window & { __d3Commands: Array<Record<string, unknown>> };
    return runtime.__d3Commands;
  });
  expect(commands.at(-1)).toMatchObject({
    type: "ATTACK_REFLECT",
    version: 3,
    matchId: MATCH_ID,
    attackId: "attack-browser-1",
  });

  await page.evaluate((message) => {
    const runtime = window as typeof window & { __d3PushSnapshot: (value: string) => void };
    runtime.__d3PushSnapshot(message);
  }, snapshot(3, {
    attackId: "attack-browser-1",
    phase: "ACTIVE",
    target: "SELF",
    warningDeadline,
    overlayExpiresAt: new Date(Date.now() + 30_000).toISOString(),
    overlaySeed: 8844,
    resolution: null,
  }));

  await expect(page.locator(".garbage-overlay")).toHaveAttribute("data-overlay-seed", "8844");
  await expect(page.getByRole("status")).toContainText("source buffer is unchanged");
  await expect(editor).toHaveValue(source);

  await page.evaluate((message) => {
    const runtime = window as typeof window & { __d3PushSnapshot: (value: string) => void };
    runtime.__d3PushSnapshot(message);
  }, snapshot(4, {
    attackId: "attack-browser-1",
    phase: "RESOLVED",
    target: "SELF",
    warningDeadline,
    overlayExpiresAt: new Date().toISOString(),
    overlaySeed: 8844,
    resolution: "EXPIRED",
  }));

  await expect(page.locator(".garbage-overlay")).toHaveCount(0);
  await expect(page.getByRole("status")).toContainText("expired");
  await expect(editor).toHaveValue(source);
});
