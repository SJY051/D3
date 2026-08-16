export interface SessionToken {
  accessToken: string;
  userId: string;
}

let accessToken: string | null = null;
let refreshRequest: Promise<string> | null = null;

export function setSession(session: SessionToken): void {
  accessToken = session.accessToken;
}

export function clearSession(): void {
  accessToken = null;
}

export function hasSession(): boolean {
  return accessToken !== null;
}

export async function requestSessionAccessToken(): Promise<string> {
  if (accessToken !== null) {
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
      return value.accessToken;
    }).finally(() => {
      refreshRequest = null;
    });
  }
  return refreshRequest;
}

export async function authenticatedFetch(url: string, init: RequestInit = {}): Promise<Response> {
  let token: string;
  try {
    token = await requestSessionAccessToken();
  } catch {
    clearSession();
    return new Response(null, { status: 401 });
  }

  let response = await fetch(url, withAuthorization(init, token));
  if (response.status !== 401) {
    return response;
  }

  clearSession();
  try {
    token = await requestSessionAccessToken();
  } catch {
    clearSession();
    return response;
  }
  response = await fetch(url, withAuthorization(init, token));
  if (response.status === 401) {
    clearSession();
  }
  return response;
}

export async function endSession(): Promise<void> {
  try {
    await fetch("/api/v1/auth/logout", { credentials: "include", method: "POST" });
  } finally {
    clearSession();
  }
}

function withAuthorization(init: RequestInit, token: string): RequestInit {
  return {
    ...init,
    credentials: "include",
    headers: { ...init.headers, Authorization: `Bearer ${token}` },
  };
}
