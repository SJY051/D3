import { authenticatedFetch, clearSession, endSession, setSession, type SessionToken } from "./session";

export type RankedLanguage = "C" | "CPP" | "JAVA" | "PYTHON3" | "JAVASCRIPT" | "TYPESCRIPT";

export interface FeedPost {
  authorHandle?: string | null;
  authorUserId: string;
  createdAt: string;
  id: string;
  markdown: string;
  matchId: string | null;
  renderedHtml: string;
}

export interface FeedPage {
  nextCursor: string | null;
  posts: FeedPost[];
}

export interface MatchRecord {
  matchId: string;
  playerOneUserId: string;
  playerTwoUserId: string;
  projectedAt: string;
  ranked: boolean;
  result: "PLAYER_ONE_WIN" | "PLAYER_TWO_WIN" | "DRAW" | "VOIDED";
  sourceVersion: number;
  players?: PlayerRecordEvidence[];
}

export interface PlayerRecordEvidence {
  userId: string;
  language: string;
  attempts: number;
  peakTier: string;
  leaderboardPosition: number;
  score: null | { total: number; speed: number; dynamicEfficiency: number; submissionDiscipline: number; calculationVersion: string; problemVersion: string; runtimeVersion: string; calibrationVersion: string };
  execution: null | { verdict: string; passedCount: number; totalCount: number; runtimeMeasurements: { tier: string; inputSize: number; sampleCount: number; medianRuntimeMicros: number }[]; adapterVersion: string; runtimeVersion: string; evidenceVersion: string };
  attacks: { launched: number; targeted: number; blocked: number; reflected: number };
}

export interface MatchRecordPage {
  matches: MatchRecord[];
  nextCursor: string | null;
}

export interface RankedQueueTicket {
  enqueuedAt: string | null;
  matchId: string | null;
  status: "QUEUED" | "MATCHED";
}

interface ErrorResponse {
  code?: string;
  correlationId?: string;
  message?: string;
}

export class ApiRequestError extends Error {
  readonly code?: string;
  readonly correlationId?: string;
  readonly disconnected: boolean;
  readonly status: number;

  constructor(message: string, options: { code?: string; correlationId?: string; disconnected?: boolean; status?: number } = {}) {
    super(message);
    this.name = "ApiRequestError";
    this.code = options.code;
    this.correlationId = options.correlationId;
    this.disconnected = options.disconnected ?? false;
    this.status = options.status ?? 0;
  }
}

export async function signIn(email: string, password: string): Promise<SessionToken> {
  const session = await request<SessionToken>("/api/v1/auth/login", {
    body: JSON.stringify({ email, password }),
    headers: { "Content-Type": "application/json" },
    method: "POST",
  });
  setSession(session);
  return session;
}

export async function register(email: string, handle: string, displayName: string, password: string): Promise<SessionToken> {
  const created = await request<{ userId: string }>("/api/v1/auth/register", {
    body: JSON.stringify({ email, handle, displayName, password }),
    headers: { "Content-Type": "application/json" },
    method: "POST",
  });
  return signIn(email, password).catch((error: unknown) => {
    if (error instanceof ApiRequestError) {
      throw error;
    }
    throw new ApiRequestError(`Account ${created.userId} was created, but the session could not be opened.`);
  });
}

export function logout(): Promise<void> {
  return endSession();
}

export function loadFeed(cursor: string | null = null): Promise<FeedPage> {
  const params = new URLSearchParams({ limit: "20" });
  if (cursor !== null) params.set("cursor", cursor);
  return request<FeedPage>(`/api/v1/community/feed?${params}`, undefined, true);
}

export function createPublicPost(markdown: string): Promise<FeedPost> {
  return request<FeedPost>("/api/v1/community/posts", {
    body: JSON.stringify({ markdown, visibility: "PUBLIC" }),
    headers: { "Content-Type": "application/json" },
    method: "POST",
  }, true);
}

export async function joinRankedQueue(language: RankedLanguage, idempotencyKey: string, signal?: AbortSignal): Promise<RankedQueueTicket> {
  return request<RankedQueueTicket>("/api/v1/battle/ranked/queue", {
    body: JSON.stringify({ language }),
    headers: { "Content-Type": "application/json", "Idempotency-Key": idempotencyKey },
    method: "POST",
    signal,
  }, true);
}

export async function waitForRankedMatch(
  language: RankedLanguage,
  options: { idempotencyKey?: string; pollIntervalMs?: number; signal: AbortSignal; onTicket: (ticket: RankedQueueTicket) => void },
): Promise<RankedQueueTicket> {
  const idempotencyKey = options.idempotencyKey ?? crypto.randomUUID();
  const pollIntervalMs = options.pollIntervalMs ?? 750;
  for (let attempt = 0; attempt < 12; attempt += 1) {
    let ticket: RankedQueueTicket;
    try {
      ticket = await joinRankedQueue(language, idempotencyKey, options.signal);
    } catch (error) {
      if (error instanceof ApiRequestError && error.status === 503) {
        await delay(pollIntervalMs, options.signal);
        continue;
      }
      throw error;
    }
    options.onTicket(ticket);
    if (ticket.status === "MATCHED" && ticket.matchId !== null) {
      return ticket;
    }
    await delay(pollIntervalMs, options.signal);
  }
  throw new ApiRequestError("Matchmaking did not converge before the bounded retry limit.");
}

export function loadMatchRecord(matchId: string): Promise<MatchRecord> {
  return request<MatchRecord>(`/api/v1/community/matches/${encodeURIComponent(matchId)}`);
}

export function loadPlayerRecords(playerId: string, cursor: string | null = null): Promise<MatchRecordPage> {
  const params = new URLSearchParams({ limit: "20" });
  if (cursor !== null) params.set("cursor", cursor);
  return request<MatchRecordPage>(`/api/v1/community/players/${encodeURIComponent(playerId)}/matches?${params}`);
}

export function isUuid(value: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}

async function request<T>(url: string, init?: RequestInit, authenticated = false): Promise<T> {
  let response: Response;
  try {
    response = authenticated ? await authenticatedFetch(url, init) : await fetch(url, { ...init, credentials: "include" });
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") throw error;
    throw new ApiRequestError("The service is unreachable. Check the local gateway connection.", { disconnected: true });
  }
  if (!response.ok) {
    const error = await parseError(response);
    if (response.status === 401) clearSession();
    throw new ApiRequestError(error.message ?? `Request failed with ${response.status}.`, {
      code: error.code,
      correlationId: error.correlationId,
      disconnected: response.status >= 500,
      status: response.status,
    });
  }
  return response.json() as Promise<T>;
}

function delay(milliseconds: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const timer = window.setTimeout(resolve, milliseconds);
    signal.addEventListener("abort", () => {
      window.clearTimeout(timer);
      reject(new DOMException("Queue polling aborted.", "AbortError"));
    }, { once: true });
  });
}

async function parseError(response: Response): Promise<ErrorResponse> {
  try { return await response.json() as ErrorResponse; } catch { return {}; }
}
