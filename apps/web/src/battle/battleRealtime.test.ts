import { describe, expect, it, vi } from "vitest";

import {
  battleSocketUrl,
  BATTLE_HEARTBEAT_INTERVAL_MILLIS,
  BATTLE_RECONNECT_DELAY_MILLIS,
  createBattleCommand,
  overlayGlyphs,
  parseBattleSnapshot,
} from "./battleRealtime";

const MATCH_ID = "11111111-1111-4111-8111-111111111111";

function snapshot(overrides: Record<string, unknown> = {}, overlaySeed = 9123) {
  return JSON.stringify({
    type: "BATTLE_SNAPSHOT",
    version: 3,
    matchId: MATCH_ID,
    sequence: 4,
    serverTime: "2026-08-15T09:30:00Z",
    payload: {
      match: {
        state: "RUNNING",
        startedAt: "2026-08-15T09:29:00Z",
        matchDeadline: "2026-08-15T09:39:00Z",
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
        selfEnergy: 60,
        opponentEnergy: 40,
        maximumEnergy: 100,
        attackCost: 40,
        blockCost: 20,
        reflectCost: 30,
        current: {
          attackId: "attack-7",
          phase: "ACTIVE",
          target: "SELF",
          warningDeadline: "2026-08-15T09:30:02Z",
          overlayExpiresAt: "2026-08-15T09:30:05Z",
          overlaySeed,
          resolution: null,
        },
      },
    },
    ...overrides,
  });
}

describe("Battle v3 realtime boundary", () => {
  it("parses the authoritative attack projection and rejects a mismatched version", () => {
    const parsed = parseBattleSnapshot(snapshot());

    expect(parsed?.attack).toMatchObject({
      maximumEnergy: 100,
      attackCost: 40,
      blockCost: 20,
      reflectCost: 30,
      current: {
        attackId: "attack-7",
        phase: "ACTIVE",
        target: "SELF",
        overlaySeed: 9123,
      },
    });
    expect(parseBattleSnapshot(snapshot({ version: 2 }))).toBeNull();
    expect(parseBattleSnapshot("not-json")).toBeNull();
    expect(parseBattleSnapshot(snapshot({}, Number.MAX_SAFE_INTEGER + 2))).not.toBeNull();
  });

  it("maps the self-scoped submission verdict and degrades malformed verdicts to null", () => {
    expect(parseBattleSnapshot(snapshot())?.submission).toBeNull();

    const wrongAnswer = JSON.parse(snapshot()) as { payload: Record<string, unknown> };
    wrongAnswer.payload.submission = { verdict: "WRONG_ANSWER", attemptNumber: 1, acceptedLocked: false };
    expect(parseBattleSnapshot(JSON.stringify(wrongAnswer))?.submission).toEqual({
      verdict: "WRONG_ANSWER",
      attemptNumber: 1,
      acceptedLocked: false,
    });

    const accepted = JSON.parse(snapshot()) as { payload: Record<string, unknown> };
    accepted.payload.submission = { verdict: "ACCEPTED", attemptNumber: 2, acceptedLocked: true };
    const lockedFrame = parseBattleSnapshot(JSON.stringify(accepted));
    expect(lockedFrame?.submission?.acceptedLocked).toBe(true);

    const malformed = JSON.parse(snapshot()) as { payload: Record<string, unknown> };
    malformed.payload.submission = { verdict: 7, attemptNumber: "two" };
    expect(parseBattleSnapshot(JSON.stringify(malformed))?.submission).toBeNull();
  });

  it("derives repeatable display noise without reading or rewriting source", () => {
    const first = overlayGlyphs(9123);
    const replay = overlayGlyphs(9123);
    const differentAttack = overlayGlyphs(9124);

    expect(first).toEqual(replay);
    expect(first).not.toEqual(differentAttack);
    expect(first).toHaveLength(28);
    expect(first.every(({ x, y }) => x >= 3 && x <= 93 && y >= 5 && y <= 91)).toBe(true);
  });

  it("builds the gateway URL and versioned idempotent command shape", () => {
    vi.stubGlobal("crypto", { randomUUID: () => "33333333-3333-4333-8333-333333333333" });
    const location = { protocol: "https:", host: "d3.example" } as Location;

    expect(battleSocketUrl(MATCH_ID, location)).toBe(
      `wss://d3.example/ws/v1/battle/matches/${MATCH_ID}`,
    );
    expect(createBattleCommand(MATCH_ID, "ATTACK_BLOCK", { attackId: "attack-7" })).toEqual({
      type: "ATTACK_BLOCK",
      version: 3,
      matchId: MATCH_ID,
      commandId: "33333333-3333-4333-8333-333333333333",
      attackId: "attack-7",
    });
    expect(createBattleCommand(MATCH_ID, "HEARTBEAT")).toMatchObject({
      type: "HEARTBEAT",
      version: 3,
      matchId: MATCH_ID,
    });
    expect(BATTLE_HEARTBEAT_INTERVAL_MILLIS).toBeLessThan(60_000);
    expect(BATTLE_RECONNECT_DELAY_MILLIS).toBeGreaterThan(0);
    vi.unstubAllGlobals();
  });
});
