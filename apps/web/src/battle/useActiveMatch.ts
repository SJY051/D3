import { useSyncExternalStore } from "react";

const KEY = "d3.activeMatchId";

function emit() {
  // storage events don't fire in the same tab; dispatch our own for in-tab subscribers.
  window.dispatchEvent(new StorageEvent("storage", { key: KEY }));
}

export function setActiveMatch(matchId: string) {
  if (localStorage.getItem(KEY) === matchId) {
    return;
  }
  localStorage.setItem(KEY, matchId);
  emit();
}

export function clearActiveMatch() {
  if (localStorage.getItem(KEY) === null) {
    return;
  }
  localStorage.removeItem(KEY);
  emit();
}

export function getActiveMatch(): string | null {
  return localStorage.getItem(KEY);
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
