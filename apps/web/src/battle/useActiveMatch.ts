import { useSyncExternalStore } from "react";

const KEY = "d3.activeMatch";
// Matches are capped at ten minutes plus the reconnect grace; anything older is
// a stale marker the terminal-snapshot clear could not reach (tab closed early).
const TTL_MS = 15 * 60 * 1_000;

interface StoredMatch {
  id: string;
  owner: string | null;
  recordedAt: number;
}

// Session logic imports this module, so tolerate hosts without browser storage
// (server-side or bare test environments) by degrading to "no active match".
function storage(): Storage | null {
  return typeof localStorage === "undefined" ? null : localStorage;
}

function emit() {
  if (typeof window === "undefined") {
    return;
  }
  // storage events don't fire in the same tab; dispatch our own for in-tab subscribers.
  window.dispatchEvent(new StorageEvent("storage", { key: KEY }));
}

function read(): StoredMatch | null {
  const raw = storage()?.getItem(KEY) ?? null;
  if (raw === null) {
    return null;
  }
  try {
    const value = JSON.parse(raw) as Partial<StoredMatch>;
    if (typeof value.id !== "string") {
      return null;
    }
    const recordedAt = typeof value.recordedAt === "number" ? value.recordedAt : 0;
    if (Date.now() - recordedAt > TTL_MS) {
      storage()?.removeItem(KEY);
      return null;
    }
    return { id: value.id, owner: typeof value.owner === "string" ? value.owner : null, recordedAt };
  } catch {
    return null;
  }
}

export function setActiveMatch(id: string, owner: string | null = null) {
  const current = read();
  if (current?.id === id && current.owner === owner) {
    return;
  }
  storage()?.setItem(KEY, JSON.stringify({ id, owner, recordedAt: Date.now() }));
  emit();
}

export function clearActiveMatch() {
  if (storage()?.getItem(KEY) == null) {
    return;
  }
  storage()?.removeItem(KEY);
  emit();
}

// Clear only when the stored marker refers to the given match, so a finished
// replay of an older battle cannot delete the marker of a still-active match.
export function clearActiveMatchIfMatch(id: string) {
  if (read()?.id === id) {
    clearActiveMatch();
  }
}

// Clear a match owned by a different user, e.g. after session expiry a new
// user signs in on the same browser. A null owner (unknown) is left alone.
export function clearActiveMatchIfNotOwner(userId: string) {
  const current = read();
  if (current !== null && current.owner !== null && current.owner !== userId) {
    clearActiveMatch();
  }
}

export function getActiveMatch(): string | null {
  return read()?.id ?? null;
}

function subscribe(onChange: () => void): () => void {
  const handler = (event: StorageEvent) => {
    if (event.key === null || event.key === KEY) {
      onChange();
    }
  };
  window.addEventListener("storage", handler);
  return () => window.removeEventListener("storage", handler);
}

export function useActiveMatch(): string | null {
  return useSyncExternalStore(subscribe, getActiveMatch, () => null);
}
