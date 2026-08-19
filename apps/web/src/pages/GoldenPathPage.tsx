import { useCallback, useEffect, useState, type FormEvent, type ReactNode } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";

import {
  ApiRequestError,
  type FeedPage,
  type FeedPost,
  type MatchRecord,
  type MatchRecordPage,
  type RankedLanguage,
  createPublicPost,
  logout,
  isUuid,
  loadFeed,
  loadMatchRecord,
  loadPlayerRecords,
  register,
  signIn,
} from "../api/goldenPathApi";
import { currentSessionUserId } from "../api/session";
import { pauseRankedQueue, startRankedQueue, type RankedQueueState, useRankedQueue } from "../battle/useRankedQueue";

export type GoldenPathKind = "sign-in" | "feed" | "ranked" | "result" | "record";

type Resource<T> =
  | { status: "loading" }
  | { status: "success"; value: T }
  | { status: "empty" }
  | { status: "error" | "disconnected"; message: string; correlationId?: string; code?: string; statusCode?: number };

const screens: Record<GoldenPathKind, readonly [string, string, string]> = {
  "sign-in": ["WF-01", "D3-ID-001", "Sign in"],
  feed: ["WF-02", "D3-COM-001", "Public feed"],
  ranked: ["WF-03", "D3-BTL-001", "Ranked queue"],
  result: ["WF-05", "D3-BTL-003 · D3-BTL-005 · D3-STAT-001", "Match result"],
  record: ["WF-06", "D3-STAT-001", "Player record"],
};

export function GoldenPathPage({ kind }: { kind: GoldenPathKind }) {
  const [wireframe, requirement, title] = screens[kind];
  return <article className={`golden-page golden-page--${kind}`} data-requirement={requirement} data-wireframe={wireframe}>
    <header className="golden-heading"><p className="golden-kicker">{wireframe} · {requirement}</p><h1>{title}</h1><p>{kind === "sign-in" ? "A developer network where reputation is earned live, one coding battle at a time — connected through the versioned API contract." : "Connected through the versioned API contract. No preview records are rendered."}</p>{kind === "sign-in" && <div className="golden-hero-metrics" aria-label="D cubed product features"><span>BUILD</span><i aria-hidden="true" /><span>BATTLE</span><i aria-hidden="true" /><span>BECOME</span></div>}</header>
    {kind === "sign-in" && <SignIn />}{kind === "feed" && <Feed />}{kind === "ranked" && <Ranked />}{kind === "result" && <Result />}{kind === "record" && <Record />}
  </article>;
}

function SignIn() {
  const navigate = useNavigate();
  const [mode, setMode] = useState<"login" | "register">("login");
  const [resource, setResource] = useState<Resource<{ userId: string }>>({ status: "empty" });
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setResource({ status: "loading" });
    try {
      const email = String(form.get("email") ?? "");
      const password = String(form.get("password") ?? "");
      const session = mode === "login" ? await signIn(email, password) : await register(email, String(form.get("handle") ?? ""), String(form.get("displayName") ?? ""), password);
      setResource({ status: "success", value: { userId: session.userId } });
      navigate("/feed");
    } catch (error) { setResource(toFailure(error)); }
  }
  return <form className="golden-panel golden-form" onSubmit={submit}>
    <div className="golden-switch"><button type="button" aria-pressed={mode === "login"} onClick={() => setMode("login")}>Sign in</button><button type="button" aria-pressed={mode === "register"} onClick={() => setMode("register")}>Create account</button></div>
    {mode === "register" && <><label>Handle<input name="handle" pattern="[a-z0-9-]{1,39}" required /></label><label>Display name<input name="displayName" maxLength={80} required /></label></>}
    <label>Email<input autoComplete="email" name="email" required type="email" /></label><label>Password<input autoComplete={mode === "login" ? "current-password" : "new-password"} minLength={8} name="password" required type="password" /></label>
    <button disabled={resource.status === "loading"} type="submit">{resource.status === "loading" ? "Working…" : mode === "login" ? "Sign in" : "Create account"}</button>
    <ResourceMessage resource={resource} success={(value) => `Signed in as ${value.userId}.`} />
  </form>;
}

function Feed() {
  const navigate = useNavigate();
  const [resource, setResource] = useState<Resource<FeedPage>>({ status: "loading" });
  const [logoutError, setLogoutError] = useState<string | null>(null);
  const loadPage = useCallback(async (cursor: string | null, append: boolean) => {
    setResource((current) => append && current.status === "success" ? current : { status: "loading" });
    try {
      const next = await loadFeed(cursor);
      setResource((current) => append && current.status === "success" ? { status: "success", value: { nextCursor: next.nextCursor, posts: [...current.value.posts, ...next.posts] } } : next.posts.length === 0 ? { status: "empty" } : { status: "success", value: next });
    } catch (error) { setResource(toFailure(error)); }
  }, []);
  useEffect(() => { void loadPage(null, false); }, [loadPage]);
  useSessionRedirect(resource, navigate);
  const addPost = (post: FeedPost) => setResource((current) => current.status === "success" ? { status: "success", value: { ...current.value, posts: [post, ...current.value.posts] } } : { status: "success", value: { nextCursor: null, posts: [post] } });
  return <section className="golden-stack"><FeedComposer onPublished={addPost} />
    <section className="golden-panel" aria-label="Public feed posts"><h2>Public feed</h2><ResourceMessage resource={resource} empty="No public posts are available yet." success={(page) => <><div className="golden-list">{page.posts.map((post) => <FeedPostCard key={post.id} post={post} />)}</div>{page.nextCursor !== null && <button type="button" onClick={() => void loadPage(page.nextCursor, true)}>Load more</button>}</>} /></section>
    <div className="golden-route-actions"><Link className="golden-link" to="/ranked">Open ranked queue</Link><button type="button" onClick={() => void logout().then(() => navigate("/sign-in", { replace: true })).catch(() => setLogoutError("Sign-out could not revoke the server session. Stay signed in and try again."))}>Sign out</button></div>
    {logoutError !== null && <p className="golden-error" role="alert">{logoutError}</p>}
  </section>;
}

function FeedComposer({ onPublished }: { onPublished: (post: FeedPost) => void }) {
  const navigate = useNavigate();
  const [resource, setResource] = useState<Resource<FeedPost>>({ status: "empty" });
  useSessionRedirect(resource, navigate);
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const markdown = String(new FormData(form).get("markdown") ?? "");
    setResource({ status: "loading" });
    try { const post = await createPublicPost(markdown); onPublished(post); form.reset(); setResource({ status: "success", value: post }); } catch (error) { setResource(toFailure(error)); }
  }
  return <form className="golden-panel golden-form" onSubmit={submit}><label>Public Markdown post<textarea name="markdown" required rows={5} /></label><button disabled={resource.status === "loading"} type="submit">{resource.status === "loading" ? "Publishing…" : "Publish"}</button><ResourceMessage resource={resource} success={() => "Published to the visible feed."} /></form>;
}

function FeedPostCard({ post }: { post: FeedPost }) {
  const author = post.authorHandle ? `@${post.authorHandle}` : post.authorUserId.slice(0, 8);
  return <article className="golden-record"><p>Author {author}</p><div className="golden-markdown" dangerouslySetInnerHTML={{ __html: post.renderedHtml }} />{post.matchId !== null && <Link to={`/results/${post.matchId}`}>View match result</Link>}</article>;
}

function Ranked() {
  const navigate = useNavigate();
  const queue = useRankedQueue();
  const [language, setLanguage] = useState<RankedLanguage>("PYTHON3");
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (queue?.status !== "QUEUED") return undefined;
    const timer = window.setInterval(() => setNow(Date.now()), 1_000);
    return () => window.clearInterval(timer);
  }, [queue?.status]);
  useEffect(() => {
    if (queue?.status === "MATCHED" && queue.matchId !== null) navigate(`/battles/${queue.matchId}`);
  }, [navigate, queue]);
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (queue?.status === "QUEUED" || queue?.status === "MATCHED") return;
    if ("Notification" in window && Notification.permission === "default") {
      void Notification.requestPermission();
    }
    startRankedQueue(language, currentSessionUserId());
  }
  const selectedLanguage = queue?.language ?? language;
  return <form className="golden-panel golden-form" onSubmit={submit}><label>Language<select disabled={queue !== null} onChange={(event) => setLanguage(event.target.value as RankedLanguage)} value={selectedLanguage}><option value="C">C</option><option value="CPP">CPP</option><option value="JAVA">JAVA</option><option value="PYTHON3">PYTHON3</option><option value="JAVASCRIPT">JAVASCRIPT</option><option value="TYPESCRIPT">TYPESCRIPT</option></select></label><p>Queue replays one idempotency key until MATCHED, then opens the authenticated Battle v3 route.</p><p className="golden-queue-state" role="status">{rankedQueueStatus(queue, now)}</p><button disabled={queue?.status === "QUEUED" || queue?.status === "MATCHED"} type="submit">{queue?.status === "QUEUED" ? "Finding a match..." : queue?.status === "PAUSED" ? "Retry existing queue" : queue?.status === "MATCHED" ? "Match found" : "Join ranked queue"}</button>{queue?.status === "QUEUED" && <button type="button" onClick={() => pauseRankedQueue()}>Pause polling</button>}{queue?.status === "MATCHED" && queue.matchId !== null && <Link className="golden-link" to={`/battles/${queue.matchId}`}>Enter battle</Link>}</form>;
}

function Result() {
  const { matchId = "" } = useParams();
  if (!isUuid(matchId)) return <ResultUnavailable title="Invalid match link" detail="Open a public match record with a valid match ID." />;
  return <ResultRecord matchId={matchId} />;
}

function ResultRecord({ matchId }: { matchId: string }) {
  const resource = useResource(useCallback(() => loadMatchRecord(matchId), [matchId]), [matchId]);
  if ((resource.status === "error" || resource.status === "disconnected") && resource.statusCode === 404) return <ResultUnavailable title="Match record not found" detail="This public record is unavailable or has not been projected yet." />;
  const viewerId = currentSessionUserId();
  return <section className="golden-panel"><h2>Public match record</h2><ResourceMessage resource={resource} success={(match) => { const playerId = viewerId === match.playerOneUserId || viewerId === match.playerTwoUserId ? viewerId : undefined; return <><p className="golden-outcome">{outcomeLabel(match.result, playerId, match)}</p><MatchDetails match={match} playerId={playerId} /><p><Link to={`/players/${playerId ?? match.playerOneUserId}`}>{playerId === undefined ? "View player-one record" : "View player record"}</Link> · <Link to="/feed">Return to feed</Link></p></>; }} /></section>;
}

function ResultUnavailable({ detail, title }: { detail: string; title: string }) { return <section className="golden-panel golden-not-found"><h2>{title}</h2><p>{detail}</p><Link to="/feed">Return to feed</Link></section>; }

function Record() {
  const { playerId = "" } = useParams();
  const [resource, setResource] = useState<Resource<MatchRecordPage>>({ status: "loading" });
  const loadPage = useCallback(async (cursor: string | null, append: boolean) => {
    if (!isUuid(playerId)) { setResource({ status: "error", message: "Player ID is invalid.", statusCode: 400 }); return; }
    setResource((current) => append && current.status === "success" ? current : { status: "loading" });
    try { const next = await loadPlayerRecords(playerId, cursor); setResource((current) => append && current.status === "success" ? { status: "success", value: { nextCursor: next.nextCursor, matches: [...current.value.matches, ...next.matches] } } : next.matches.length === 0 ? { status: "empty" } : { status: "success", value: next }); } catch (error) { setResource(toFailure(error)); }
  }, [playerId]);
  useEffect(() => { void loadPage(null, false); }, [loadPage]);
  return <section className="golden-panel"><h2>Player {playerId || "record"}</h2><ResourceMessage resource={resource} empty="No ACTIVE public match records are available." success={(page) => <><div className="golden-list">{page.matches.map((match) => <MatchDetails key={match.matchId} match={match} playerId={playerId} />)}</div>{page.nextCursor !== null && <button type="button" onClick={() => void loadPage(page.nextCursor, true)}>Load more</button>}</>} /></section>;
}

function MatchDetails({ match, playerId }: { match: MatchRecord; playerId?: string }) {
  const opponent = playerId === match.playerOneUserId ? match.playerTwoUserId : match.playerOneUserId;
  return <article className="golden-record"><p><strong>{outcomeLabel(match.result, playerId, match)}</strong> · {match.ranked ? "ranked" : "unranked"}</p>{playerId && <p>Opponent seat: {opponent}</p>}<p>Player one: {match.playerOneUserId} · Player two: {match.playerTwoUserId}</p><p>Source version {match.sourceVersion} · projected {match.projectedAt}</p><Link to={`/results/${match.matchId}`}>View match result</Link></article>;
}

function outcomeLabel(result: MatchRecord["result"], playerId?: string, match?: MatchRecord): string { if (result === "DRAW") return "Draw"; if (result === "VOIDED") return "Voided"; if (playerId !== undefined && match !== undefined) return playerId === (result === "PLAYER_ONE_WIN" ? match.playerOneUserId : match.playerTwoUserId) ? "Victory" : "Defeat"; return result === "PLAYER_ONE_WIN" ? "Player one victory" : "Player two victory"; }

export function formatElapsed(enqueuedAt: string, now: number): string {
  const totalSeconds = Math.max(0, Math.floor((now - Date.parse(enqueuedAt)) / 1_000));
  return `${Math.floor(totalSeconds / 60).toString().padStart(2, "0")}:${(totalSeconds % 60).toString().padStart(2, "0")}`;
}

function rankedQueueStatus(queue: RankedQueueState | null, now: number): string {
  if (queue?.status === "QUEUED") {
    const elapsed = queue.enqueuedAt === null ? "" : ` Elapsed ${formatElapsed(queue.enqueuedAt, now)}.`;
    return `Searching — queued attempt remains active across routes.${elapsed}`;
  }
  if (queue?.status === "PAUSED" && queue.pausedBecause === "SESSION_REQUIRED") {
    return "Polling paused — sign in again to resume this ranked queue.";
  }
  if (queue?.status === "PAUSED" && queue.pausedBecause === "CONFLICT") {
    return "Polling paused — this account already has an active queue or match.";
  }
  if (queue?.status === "PAUSED") {
    return "Polling paused — retry to reuse the queued ticket if the server still has it.";
  }
  if (queue?.status === "MATCHED") {
    return "Match found. Opening Battle v3.";
  }
  return "Idle — choose a language to join.";
}

function useResource<T>(loader: () => Promise<T>, dependencies: readonly unknown[]): Resource<T> {
  const [resource, setResource] = useState<Resource<T>>({ status: "loading" });
  useEffect(() => { let active = true; setResource({ status: "loading" }); void loader().then((value) => { if (active) setResource({ status: "success", value }); }).catch((error: unknown) => { if (active) setResource(toFailure(error)); }); return () => { active = false; }; // eslint-disable-next-line react-hooks/exhaustive-deps
  }, dependencies);
  return resource;
}

function useSessionRedirect(resource: Resource<unknown>, navigate: ReturnType<typeof useNavigate>) {
  useEffect(() => { if ((resource.status === "error" || resource.status === "disconnected") && resource.statusCode === 401) navigate("/sign-in", { replace: true }); }, [navigate, resource]);
}

function ResourceMessage<T>({ empty = "No data is available.", resource, success }: { empty?: string; resource: Resource<T>; success?: (value: T) => ReactNode }) {
  if (resource.status === "loading") return <p role="status">Loading authoritative data…</p>;
  if (resource.status === "empty") return <p role="status">{empty}</p>;
  if (resource.status === "error" || resource.status === "disconnected") return <p className="golden-error" role="alert">{resource.status === "disconnected" ? "Service disconnected. " : "Request failed. "}{resource.message}{resource.correlationId && ` Correlation ID: ${resource.correlationId}`}</p>;
  if (resource.status === "success" && success) return <>{success(resource.value)}</>;
  return null;
}

function toFailure(error: unknown): Extract<Resource<never>, { status: "error" | "disconnected" }> {
  if (error instanceof ApiRequestError) return { status: error.disconnected ? "disconnected" : "error", message: friendlyMessage(error), correlationId: error.correlationId, code: error.code, statusCode: error.status };
  return { status: "error", message: "Unexpected response from the service." };
}

function friendlyMessage(error: ApiRequestError): string {
  if (error.code === "INVALID_CREDENTIALS") return "Email or password is incorrect.";
  if (error.code === "EMAIL_ALREADY_EXISTS") return "An account already uses this email.";
  if (error.code === "HANDLE_ALREADY_EXISTS") return "This handle is already in use.";
  return error.message;
}
