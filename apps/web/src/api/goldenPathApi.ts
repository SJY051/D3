export type RankedLanguage = "C" | "CPP" | "JAVA" | "PYTHON3" | "JAVASCRIPT" | "TYPESCRIPT";

export interface FeedPost {
  authorUserId: string;
  createdAt: string;
  id: string;
  markdown: string;
  matchId: string | null;
}

export interface MatchRecord {
  matchId: string;
  playerOneUserId: string;
  playerTwoUserId: string;
  projectedAt: string;
  ranked: boolean;
  result: "PLAYER_ONE_WIN" | "PLAYER_TWO_WIN" | "DRAW" | "VOIDED";
  sourceVersion: number;
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

interface TokenResponse {
  accessToken: string;
  userId: string;
}

interface ErrorResponse {
  code?: string;
  correlationId?: string;
  message?: string;
}

export class ApiRequestError extends Error {
  readonly correlationId?: string;
  readonly disconnected: boolean;

  constructor(message: string, options: { correlationId?: string; disconnected?: boolean } = {}) {
    super(message);
    this.name = "ApiRequestError";
    this.correlationId = options.correlationId;
    this.disconnected = options.disconnected ?? false;
  }
}

let accessToken: string | null = null;

export function hasAccessToken(): boolean {
  return accessToken !== null;
}

export async function signIn(email: string, password: string): Promise<TokenResponse> {
  const response = await request<TokenResponse>("/api/v1/auth/login", {
    body: JSON.stringify({ email, password }),
    headers: { "Content-Type": "application/json" },
    method: "POST",
  });
  accessToken = response.accessToken;
  return response;
}

export async function loadFeed(): Promise<FeedPost[]> {
  const response = await request<{ posts: FeedPost[] }>("/api/v1/community/feed?limit=20", {
    headers: authenticatedHeaders(),
  });
  return response.posts;
}

export function createPublicPost(markdown: string): Promise<FeedPost> {
  return request<FeedPost>("/api/v1/community/posts", {
    body: JSON.stringify({ markdown, visibility: "PUBLIC" }),
    headers: { ...authenticatedHeaders(), "Content-Type": "application/json" },
    method: "POST",
  });
}

export async function joinRankedQueue(language: RankedLanguage): Promise<RankedQueueTicket> {
  if (accessToken === null) {
    throw new ApiRequestError("Sign in is required before entering the ranked queue.");
  }
  return request<RankedQueueTicket>("/api/v1/battle/ranked/queue", {
    body: JSON.stringify({ language }),
    headers: {
      ...authenticatedHeaders(),
      "Content-Type": "application/json",
      "Idempotency-Key": crypto.randomUUID(),
    },
    method: "POST",
  });
}

function authenticatedHeaders(): HeadersInit {
  return accessToken === null ? {} : { Authorization: `Bearer ${accessToken}` };
}

export function loadMatchRecord(matchId: string): Promise<MatchRecord> {
  return request<MatchRecord>(`/api/v1/community/matches/${encodeURIComponent(matchId)}`);
}

export function loadPlayerRecords(playerId: string): Promise<MatchRecordPage> {
  return request<MatchRecordPage>(`/api/v1/community/players/${encodeURIComponent(playerId)}/matches?limit=20`);
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  let response: Response;
  try {
    response = await fetch(url, { ...init, credentials: "include" });
  } catch {
    throw new ApiRequestError("The service is unreachable. Check the local gateway connection.", { disconnected: true });
  }

  if (!response.ok) {
    const error = await parseError(response);
    throw new ApiRequestError(error.message ?? `Request failed with ${response.status}.`, {
      correlationId: error.correlationId,
      disconnected: response.status >= 500,
    });
  }

  return response.json() as Promise<T>;
}

async function parseError(response: Response): Promise<ErrorResponse> {
  try {
    return await response.json() as ErrorResponse;
  } catch {
    return {};
  }
}
