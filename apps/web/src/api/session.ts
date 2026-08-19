import { clearActiveMatch, clearActiveMatchIfNotOwner } from "../battle/useActiveMatch";
import { claimRankedQueueOwner, clearRankedQueue, clearRankedQueueIfNotOwner } from "../battle/useRankedQueue";

export interface SessionToken {
  accessToken: string;
  userId: string;
}

let accessToken: string | null = null;
let userId: string | null = null;
let refreshRequest: Promise<string> | null = null;

export function setSession(session: SessionToken): void {
  clearActiveMatchIfNotOwner(session.userId);
  clearRankedQueueIfNotOwner(session.userId);
  accessToken = session.accessToken;
  userId = session.userId;
}

export function clearSession(): void {
  accessToken = null;
  userId = null;
}

export function hasSession(): boolean {
  return accessToken !== null;
}

export function currentSessionUserId(): string | null { return userId; }

export async function requestSessionAccessToken(forceRefresh = false): Promise<string> {
  if (!forceRefresh && accessToken !== null) {
    return accessToken;
  }
  if (refreshRequest === null) {
    refreshRequest = fetch("/api/v1/auth/refresh", {
      credentials: "include",
      method: "POST",
    }).then(async (response) => {
      if (!response.ok) {
        throw new Error(response.status === 401 ? "SESSION_REQUIRED" : "SESSION_UNAVAILABLE");
      }
      const value = await response.json() as Partial<SessionToken>;
      if (typeof value.accessToken !== "string" || value.accessToken.length === 0) {
        throw new Error("SESSION_UNAVAILABLE");
      }
      accessToken = value.accessToken;
      if (typeof value.userId === "string" && value.userId.length > 0) {
        clearActiveMatchIfNotOwner(value.userId);
        claimRankedQueueOwner(value.userId);
        userId = value.userId;
      }
      return value.accessToken;
    }).finally(() => {
      refreshRequest = null;
    });
  }
  return refreshRequest;
}

export async function authenticatedFetch(url: string, init: RequestInit = {}): Promise<Response> {
  let token: string;
  try { token = await requestSessionAccessToken(); } catch (error) {
    clearSession();
    if (error instanceof Error && error.message === "SESSION_REQUIRED") return new Response(null, { status: 401 });
    throw error;
  }

  let response = await fetch(url, withAuthorization(init, token));
  if (response.status !== 401) {
    return response;
  }

  clearSession();
  try { token = await requestSessionAccessToken(); } catch (error) {
    clearSession();
    if (error instanceof Error && error.message === "SESSION_REQUIRED") return response;
    throw error;
  }
  response = await fetch(url, withAuthorization(init, token));
  if (response.status === 401) {
    clearSession();
  }
  return response;
}

export async function endSession(): Promise<void> {
  let response: Response;
  try {
    response = await fetch("/api/v1/auth/logout", { credentials: "include", method: "POST" });
  } catch {
    throw new Error("LOGOUT_FAILED");
  }
  if (!response.ok) {
    throw new Error("LOGOUT_FAILED");
  }
  clearSession();
  clearActiveMatch();
  clearRankedQueue();
}

function withAuthorization(init: RequestInit, token: string): RequestInit {
  return {
    ...init,
    credentials: "include",
    headers: { ...init.headers, Authorization: `Bearer ${token}` },
  };
}
