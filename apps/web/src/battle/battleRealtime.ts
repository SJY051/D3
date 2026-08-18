export const BATTLE_PROTOCOL = "d3.battle.v3";
export const BATTLE_HEARTBEAT_INTERVAL_MILLIS = 20_000;
export const BATTLE_RECONNECT_DELAY_MILLIS = 1_000;

export type ConnectionState = "CONNECTING" | "LIVE" | "DISCONNECTED" | "SESSION_REQUIRED" | "PROTOCOL_ERROR";
export type MatchState = "LOBBY" | "READY" | "RUNNING" | "JUDGING" | "FINISHED";
export type ParticipantConnection = "CONNECTING" | "CONNECTED" | "DISCONNECTED";
export type AttackPhase = "WARNING" | "ACTIVE" | "RESOLVED";
export type AttackTarget = "SELF" | "OPPONENT";

export interface BattleSnapshot {
  matchId: string;
  sequence: number;
  serverTime: string;
  match: {
    state: MatchState;
    startedAt: string | null;
    matchDeadline: string | null;
    self: {
      playerId: string;
      ready: boolean;
      connectionState: ParticipantConnection;
      reconnectDeadline: string | null;
    };
    opponent: {
      ready: boolean;
      connectionState: ParticipantConnection;
      reconnectDeadline: string | null;
    };
  };
  attack: {
    selfEnergy: number;
    opponentEnergy: number;
    maximumEnergy: number;
    attackCost: number;
    blockCost: number;
    reflectCost: number;
    current: {
      attackId: string;
      phase: AttackPhase;
      target: AttackTarget;
      warningDeadline: string;
      overlayExpiresAt: string | null;
      overlaySeed: number;
      resolution: "BLOCKED" | "EXPIRED" | null;
    } | null;
  };
}

export type BattleCommandType =
  | "HEARTBEAT"
  | "READY"
  | "SURRENDER"
  | "RUN"
  | "SUBMIT"
  | "ATTACK_LAUNCH"
  | "ATTACK_BLOCK"
  | "ATTACK_REFLECT";

export interface BattleCommand {
  type: BattleCommandType;
  version: 3;
  matchId: string;
  commandId: string;
  attackId?: string;
  sourceCode?: string;
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const MATCH_STATES: Record<MatchState, true> = {
  LOBBY: true,
  READY: true,
  RUNNING: true,
  JUDGING: true,
  FINISHED: true,
};
const CONNECTION_STATES: Record<ParticipantConnection, true> = {
  CONNECTING: true,
  CONNECTED: true,
  DISCONNECTED: true,
};
const ATTACK_PHASES: Record<AttackPhase, true> = {
  WARNING: true,
  ACTIVE: true,
  RESOLVED: true,
};
const ATTACK_TARGETS: Record<AttackTarget, true> = {
  SELF: true,
  OPPONENT: true,
};

export function isMatchId(value: string): boolean {
  return UUID.test(value);
}

export function createBattleCommand(
  matchId: string,
  type: BattleCommandType,
  fields: Pick<BattleCommand, "attackId" | "sourceCode"> = {},
): BattleCommand {
  return {
    type,
    version: 3,
    matchId,
    commandId: crypto.randomUUID(),
    ...fields,
  };
}

export function battleSocketUrl(matchId: string, location: Location = window.location): string {
  const protocol = location.protocol === "https:" ? "wss:" : "ws:";
  return `${protocol}//${location.host}/ws/v1/battle/matches/${matchId}`;
}

export function overlayGlyphs(seed: number, count = 28): readonly { glyph: string; x: number; y: number }[] {
  let state = BigInt.asUintN(64, BigInt(seed));
  const glyphs = ["#", "@", "%", "&", "?", "{", "}", ";", "∴", "×"];
  const next = () => {
    state = BigInt.asUintN(64, state * 6364136223846793005n + 1442695040888963407n);
    return Number(state >> 32n) / 0x1_0000_0000;
  };
  return Array.from({ length: count }, () => ({
    glyph: glyphs[Math.floor(next() * glyphs.length)] ?? "#",
    x: 3 + Math.floor(next() * 91),
    y: 5 + Math.floor(next() * 87),
  }));
}

export function parseBattleSnapshot(raw: string): BattleSnapshot | null {
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch {
    return null;
  }
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    return null;
  }

  const message = value as {
    type?: unknown;
    version?: unknown;
    matchId?: unknown;
    sequence?: unknown;
    serverTime?: unknown;
    payload?: unknown;
  };
  if (message.type !== "BATTLE_SNAPSHOT"
      || message.version !== 3
      || typeof message.matchId !== "string"
      || !isMatchId(message.matchId)
      || !isNonNegativeInteger(message.sequence)
      || !isDateTime(message.serverTime)
      || typeof message.payload !== "object"
      || message.payload === null
      || Array.isArray(message.payload)) {
    return null;
  }

  const payload = message.payload as { match?: unknown; attack?: unknown };
  if (!isMatch(payload.match) || !isAttack(payload.attack)) {
    return null;
  }

  return {
    matchId: message.matchId,
    sequence: message.sequence,
    serverTime: message.serverTime,
    match: payload.match,
    attack: payload.attack,
  };
}

function isMatch(value: unknown): value is BattleSnapshot["match"] {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    return false;
  }
  const match = value as {
    state?: unknown;
    startedAt?: unknown;
    matchDeadline?: unknown;
    self?: unknown;
    opponent?: unknown;
  };
  if (typeof match.self !== "object"
      || match.self === null
      || Array.isArray(match.self)
      || typeof match.opponent !== "object"
      || match.opponent === null
      || Array.isArray(match.opponent)) {
    return false;
  }
  const self = match.self as {
    playerId?: unknown;
    ready?: unknown;
    connectionState?: unknown;
    reconnectDeadline?: unknown;
  };
  const opponent = match.opponent as {
    ready?: unknown;
    connectionState?: unknown;
    reconnectDeadline?: unknown;
  };
  return typeof match.state === "string"
    && MATCH_STATES[match.state as MatchState] === true
    && isNullableDateTime(match.startedAt)
    && isNullableDateTime(match.matchDeadline)
    && typeof self.playerId === "string"
    && isMatchId(self.playerId)
    && typeof self.ready === "boolean"
    && typeof self.connectionState === "string"
    && CONNECTION_STATES[self.connectionState as ParticipantConnection] === true
    && isNullableDateTime(self.reconnectDeadline)
    && typeof opponent.ready === "boolean"
    && typeof opponent.connectionState === "string"
    && CONNECTION_STATES[opponent.connectionState as ParticipantConnection] === true
    && isNullableDateTime(opponent.reconnectDeadline);
}

function isAttack(value: unknown): value is BattleSnapshot["attack"] {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    return false;
  }
  const attack = value as {
    selfEnergy?: unknown;
    opponentEnergy?: unknown;
    maximumEnergy?: unknown;
    attackCost?: unknown;
    blockCost?: unknown;
    reflectCost?: unknown;
    current?: unknown;
  };
  if (!isNonNegativeInteger(attack.selfEnergy)
      || !isNonNegativeInteger(attack.opponentEnergy)
      || !isPositiveInteger(attack.maximumEnergy)
      || !isPositiveInteger(attack.attackCost)
      || !isPositiveInteger(attack.blockCost)
      || !isPositiveInteger(attack.reflectCost)) {
    return false;
  }
  if (attack.current === null) {
    return true;
  }
  if (typeof attack.current !== "object"
      || attack.current === null
      || Array.isArray(attack.current)) {
    return false;
  }
  const current = attack.current as {
    attackId?: unknown;
    phase?: unknown;
    target?: unknown;
    warningDeadline?: unknown;
    overlayExpiresAt?: unknown;
    overlaySeed?: unknown;
    resolution?: unknown;
  };
  if (typeof current.attackId !== "string"
      || current.attackId.length === 0
      || typeof current.phase !== "string"
      || ATTACK_PHASES[current.phase as AttackPhase] !== true
      || typeof current.target !== "string"
      || ATTACK_TARGETS[current.target as AttackTarget] !== true
      || !isDateTime(current.warningDeadline)
      || !isNullableDateTime(current.overlayExpiresAt)
      || !isInteger(current.overlaySeed)) {
    return false;
  }
  return current.resolution === null
    || current.resolution === "BLOCKED"
    || current.resolution === "EXPIRED";
}


function isInteger(value: unknown): value is number {
  return typeof value === "number" && Number.isInteger(value);
}

function isPositiveInteger(value: unknown): value is number {
  return Number.isSafeInteger(value) && Number(value) > 0;
}

function isNonNegativeInteger(value: unknown): value is number {
  return Number.isSafeInteger(value) && Number(value) >= 0;
}

function isDateTime(value: unknown): value is string {
  return typeof value === "string" && Number.isFinite(Date.parse(value));
}

function isNullableDateTime(value: unknown): value is string | null {
  return value === null || isDateTime(value);
}
