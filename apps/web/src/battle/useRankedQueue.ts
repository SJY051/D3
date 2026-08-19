import { useSyncExternalStore } from "react";

import type { RankedLanguage, RankedQueueTicket } from "../api/goldenPathApi";

const KEY = "d3.rankedQueue";
const TTL_MS = 15 * 60 * 1_000;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
let cachedRaw: string | null = null;
let cachedState: RankedQueueState | null = null;
const LANGUAGES: Record<RankedLanguage, true> = {
  C: true,
  CPP: true,
  JAVA: true,
  PYTHON3: true,
  JAVASCRIPT: true,
  TYPESCRIPT: true,
};

export interface RankedQueueState {
  enqueuedAt: string | null;
  idempotencyKey: string;
  language: RankedLanguage;
  matchId: string | null;
  owner: string | null;
  pausedBecause: "SESSION_REQUIRED" | "CONFLICT" | null;
  recordedAt: number;
  status: "QUEUED" | "PAUSED" | "MATCHED";
}

function storage(): Storage | null {
  return typeof localStorage === "undefined" ? null : localStorage;
}

function emit() {
  if (typeof window !== "undefined") {
    window.dispatchEvent(new StorageEvent("storage", { key: KEY }));
  }
}

function write(next: RankedQueueState) {
  const state = { ...next, recordedAt: Date.now() };
  const raw = JSON.stringify(state);
  cachedRaw = raw;
  cachedState = state;
  storage()?.setItem(KEY, raw);
  emit();
}

function isExpired(state: Pick<RankedQueueState, "recordedAt">): boolean {
  return Date.now() - state.recordedAt > TTL_MS;
}

function read(): RankedQueueState | null {
  const raw = storage()?.getItem(KEY) ?? null;
  if (raw === cachedRaw) {
    if (cachedState !== null && isExpired(cachedState)) {
      clearRankedQueue();
      return null;
    }
    return cachedState;
  }
  if (raw === null) {
    cachedRaw = null;
    cachedState = null;
    return null;
  }
  try {
    const value = JSON.parse(raw) as Partial<RankedQueueState>;
    if (typeof value.idempotencyKey !== "string"
        || typeof value.language !== "string"
        || LANGUAGES[value.language as RankedLanguage] !== true
        || (value.status !== "QUEUED" && value.status !== "PAUSED" && value.status !== "MATCHED")
        || (value.status === "MATCHED" && (typeof value.matchId !== "string" || !UUID.test(value.matchId)))) {
      return null;
    }
    const recordedAt = typeof value.recordedAt === "number" ? value.recordedAt : 0;
    if (isExpired({ recordedAt })) {
      storage()?.removeItem(KEY);
      cachedRaw = null;
      cachedState = null;
      return null;
    }
    cachedRaw = raw;
    cachedState = {
      enqueuedAt: typeof value.enqueuedAt === "string" ? value.enqueuedAt : null,
      idempotencyKey: value.idempotencyKey,
      language: value.language as RankedLanguage,
      matchId: typeof value.matchId === "string" && UUID.test(value.matchId) ? value.matchId : null,
      owner: typeof value.owner === "string" ? value.owner : null,
      pausedBecause: value.pausedBecause === "SESSION_REQUIRED" || value.pausedBecause === "CONFLICT" ? value.pausedBecause : null,
      recordedAt,
      status: value.status,
    };
    return cachedState;
  } catch {
    return null;
  }
}

export function startRankedQueue(language: RankedLanguage, owner: string | null = null): RankedQueueState {
  const current = read();
  const next: RankedQueueState = current === null ? {
    enqueuedAt: null,
    idempotencyKey: crypto.randomUUID(),
    language,
    matchId: null,
    owner,
    pausedBecause: null,
    recordedAt: Date.now(),
    status: "QUEUED",
  } : { ...current, pausedBecause: null, status: "QUEUED" };
  write(next);
  return next;
}

export function updateRankedQueue(ticket: RankedQueueTicket) {
  const current = read();
  if (current === null) return;
  if (current.enqueuedAt === ticket.enqueuedAt
      && current.matchId === ticket.matchId
      && current.status === ticket.status) {
    return;
  }
  write({
    ...current,
    enqueuedAt: ticket.enqueuedAt,
    matchId: ticket.matchId,
    pausedBecause: null,
    status: ticket.status,
  });
}

export function pauseRankedQueue(pausedBecause: RankedQueueState["pausedBecause"] = null) {
  const current = read();
  if (current !== null && current.status === "QUEUED") {
    write({ ...current, pausedBecause, status: "PAUSED" });
  }
}

export function clearRankedQueue() {
  if (storage()?.getItem(KEY) == null) return;
  storage()?.removeItem(KEY);
  cachedRaw = null;
  cachedState = null;
  emit();
}

export function clearRankedQueueIfMatch(matchId: string) {
  if (read()?.matchId === matchId) clearRankedQueue();
}

export function clearRankedQueueIfNotOwner(userId: string) {
  const current = read();
  if (current !== null && current.owner !== userId) {
    clearRankedQueue();
  }
}

export function claimRankedQueueOwner(userId: string) {
  const current = read();
  if (current === null) return;
  if (current.owner === null) {
    write({ ...current, owner: userId });
  } else if (current.owner !== userId) {
    clearRankedQueue();
  }
}

export function getRankedQueue(): RankedQueueState | null {
  return read();
}

function subscribe(onChange: () => void): () => void {
  const handler = (event: StorageEvent) => {
    if (event.key === null || event.key === KEY) onChange();
  };
  window.addEventListener("storage", handler);
  return () => window.removeEventListener("storage", handler);
}

export function useRankedQueue(): RankedQueueState | null {
  return useSyncExternalStore(subscribe, getRankedQueue, () => null);
}
