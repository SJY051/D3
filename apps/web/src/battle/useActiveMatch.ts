import { useSyncExternalStore } from "react";

const KEY = "d3.activeMatch";

interface StoredMatch {
  id: string;
  owner: string | null;
}

function emit() {
  // storage events don't fire in the same tab; dispatch our own for in-tab subscribers.
  window.dispatchEvent(new StorageEvent("storage", { key: KEY }));
}

function read(): StoredMatch | null {
  const raw = localStorage.getItem(KEY);
  if (raw === null) {
    return null;
  }
  try {
    const value = JSON.parse(raw) as Partial<StoredMatch>;
    return typeof value.id === "string"
      ? { id: value.id, owner: typeof value.owner === "string" ? value.owner : null }
      : null;
  } catch {
    return null;
  }
}

export function setActiveMatch(id: string, owner: string | null = null) {
  const current = read();
  if (current?.id === id && current.owner === owner) {
    return;
  }
  localStorage.setItem(KEY, JSON.stringify({ id, owner }));
  emit();
}

export function clearActiveMatch() {
  if (localStorage.getItem(KEY) === null) {
    return;
  }
  localStorage.removeItem(KEY);
  emit();
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
