import { useCallback, useEffect, useState, type FormEvent, type ReactNode } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";

import {
  ApiRequestError,
  type FeedPost,
  type MatchRecord,
  type MatchRecordPage,
  type RankedLanguage,
  type RankedQueueTicket,
  createPublicPost,
  joinRankedQueue,
  loadFeed,
  loadMatchRecord,
  loadPlayerRecords,
  signIn,
} from "../api/goldenPathApi";

export type GoldenPathKind = "sign-in" | "feed" | "ranked" | "result" | "record";

type Resource<T> =
  | { status: "loading" }
  | { status: "success"; value: T }
  | { status: "empty" }
  | { status: "error" | "disconnected"; message: string; correlationId?: string };

const screens: Record<GoldenPathKind, readonly [string, string, string]> = {
  "sign-in": ["WF-01", "D3-ID-001", "Sign in"],
  feed: ["WF-02", "D3-COM-001", "Public feed"],
  ranked: ["WF-03", "D3-BTL-001", "Ranked queue"],
  result: ["WF-05", "D3-BTL-003 · D3-BTL-005", "Match result"],
  record: ["WF-06", "D3-STAT-001", "Player record"],
};

export function GoldenPathPage({ kind }: { kind: GoldenPathKind }) {
  const [wireframe, requirement, title] = screens[kind];
  return (
    <article className="golden-page" data-requirement={requirement} data-wireframe={wireframe}>
      <header>
        <p className="golden-kicker">{wireframe} · {requirement}</p>
        <h1>{title}</h1>
        <p>Connected only through the versioned API contract. No preview records are rendered.</p>
      </header>
      {kind === "sign-in" && <SignIn />}
      {kind === "feed" && <Feed />}
      {kind === "ranked" && <Ranked />}
      {kind === "result" && <Result />}
      {kind === "record" && <Record />}
    </article>
  );
}

function SignIn() {
  const [resource, setResource] = useState<Resource<{ userId: string }>>({ status: "empty" });
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setResource({ status: "loading" });
    try {
      const session = await signIn(String(form.get("email") ?? ""), String(form.get("password") ?? ""));
      setResource({ status: "success", value: { userId: session.userId } });
    } catch (error) { setResource(toFailure(error)); }
  }
  return (
    <form className="golden-panel golden-form" onSubmit={submit}>
      <label>Email<input autoComplete="email" name="email" required type="email" /></label>
      <label>Password<input autoComplete="current-password" name="password" required type="password" /></label>
      <button disabled={resource.status === "loading"} type="submit">{resource.status === "loading" ? "Signing in…" : "Sign in"}</button>
      <ResourceMessage resource={resource} success={(value) => `Signed in as ${value.userId}.`} />
    </form>
  );
}

function Feed() {
  const resource = useResource(loadFeed, []);
  return (
    <section className="golden-stack">
      <FeedComposer />
      <section className="golden-panel" aria-label="Public feed posts">
        <h2>Public feed</h2>
        <ResourceMessage resource={resource} empty="No public posts are available yet." success={(posts) => (
          <div className="golden-list">{posts.map((post) => <FeedPostCard key={post.id} post={post} />)}</div>
        )} />
      </section>
      <Link className="golden-link" to="/ranked">Open ranked queue</Link>
    </section>
  );
}

function FeedComposer() {
  const [resource, setResource] = useState<Resource<FeedPost>>({ status: "empty" });
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const markdown = String(new FormData(event.currentTarget).get("markdown") ?? "");
    setResource({ status: "loading" });
    try { setResource({ status: "success", value: await createPublicPost(markdown) }); }
    catch (error) { setResource(toFailure(error)); }
  }
  return <form className="golden-panel golden-form" onSubmit={submit}>
    <label>Public Markdown post<textarea name="markdown" required rows={5} /></label>
    <button disabled={resource.status === "loading"} type="submit">{resource.status === "loading" ? "Publishing…" : "Publish"}</button>
    <ResourceMessage resource={resource} success={(post) => `Published ${post.id}. Refresh the feed to load the latest page.`} />
  </form>;
}

function FeedPostCard({ post }: { post: FeedPost }) {
  return <article className="golden-record"><p>Author {post.authorUserId}</p><pre>{post.markdown}</pre>{post.matchId !== null && <Link to={`/results/${post.matchId}`}>View match result</Link>}</article>;
}

function Ranked() {
  const navigate = useNavigate();
  const [language, setLanguage] = useState<RankedLanguage>("PYTHON3");
  const [resource, setResource] = useState<Resource<RankedQueueTicket>>({ status: "empty" });
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setResource({ status: "loading" });
    try {
      const ticket = await joinRankedQueue(language);
      setResource({ status: "success", value: ticket });
      if (ticket.status === "MATCHED" && ticket.matchId !== null) navigate(`/battles/${ticket.matchId}`);
    } catch (error) { setResource(toFailure(error)); }
  }
  return (
    <form className="golden-panel golden-form" onSubmit={submit}>
      <label>Language
        <select onChange={(event) => setLanguage(event.target.value as RankedLanguage)} value={language}>
          <option value="C">C</option><option value="CPP">CPP</option><option value="JAVA">JAVA</option>
          <option value="PYTHON3">PYTHON3</option><option value="JAVASCRIPT">JAVASCRIPT</option><option value="TYPESCRIPT">TYPESCRIPT</option>
        </select>
      </label>
      <p>On MATCHED, open the authenticated Battle v3 route.</p>
      <button disabled={resource.status === "loading"} type="submit">{resource.status === "loading" ? "Joining queue…" : "Join ranked queue"}</button>
      <ResourceMessage resource={resource} success={(ticket) => ticket.status === "MATCHED" ? "Match found. Opening Battle v3." : `Queued${ticket.enqueuedAt === null ? "" : ` at ${ticket.enqueuedAt}`}.`} />
    </form>
  );
}

function Result() {
  const { matchId = "" } = useParams();
  const loader = useCallback(() => loadMatchRecord(matchId), [matchId]);
  return <MatchResource resource={useResource(loader, [loader])} title="Public match record" />;
}

function Record() {
  const { playerId = "" } = useParams();
  const loader = useCallback(() => loadPlayerRecords(playerId), [playerId]);
  const resource = useResource(loader, [loader]);
  return <section className="golden-panel"><h2>Player {playerId || "record"}</h2><ResourceMessage resource={resource} empty="No ACTIVE public match records are available." success={(page) => (
    <div className="golden-list">{page.matches.map((match) => <MatchCard key={match.matchId} match={match} />)}{page.nextCursor !== null && <p>More records are available through the next keyset cursor.</p>}</div>
  )} /></section>;
}

function MatchResource({ resource, title }: { resource: Resource<MatchRecord>; title: string }) {
  return <section className="golden-panel"><h2>{title}</h2><ResourceMessage resource={resource} success={(match) => <MatchCard match={match} />} /></section>;
}

function MatchCard({ match }: { match: MatchRecord }) {
  return <article className="golden-record"><p><strong>{match.result}</strong> · {match.ranked ? "ranked" : "unranked"}</p><p>Players: {match.playerOneUserId} / {match.playerTwoUserId}</p><p>Source version {match.sourceVersion} · projected {match.projectedAt}</p><Link to={`/players/${match.playerOneUserId}`}>View player record</Link></article>;
}

function useResource<T>(loader: () => Promise<T>, dependencies: readonly unknown[]): Resource<T> {
  const [resource, setResource] = useState<Resource<T>>({ status: "loading" });
  useEffect(() => {
    let active = true;
    setResource({ status: "loading" });
    void loader().then((value) => {
      if (active) setResource(Array.isArray(value) && value.length === 0 ? { status: "empty" } : { status: "success", value });
    }).catch((error: unknown) => { if (active) setResource(toFailure(error)); });
    return () => { active = false; };
  // The loader's caller supplies its stable dependency list.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, dependencies);
  return resource;
}

function ResourceMessage<T>({ empty = "No data is available.", resource, success }: { empty?: string; resource: Resource<T>; success?: (value: T) => ReactNode }) {
  if (resource.status === "loading") return <p role="status">Loading authoritative data…</p>;
  if (resource.status === "empty") return <p role="status">{empty}</p>;
  if (resource.status === "error" || resource.status === "disconnected") return <p className="golden-error" role="alert">{resource.status === "disconnected" ? "Service disconnected. " : "Request failed. "}{resource.message}{resource.correlationId && ` Correlation ID: ${resource.correlationId}`}</p>;
  if (resource.status === "success" && success) return <>{success(resource.value)}</>;
  return null;
}

function toFailure(error: unknown): Extract<Resource<never>, { status: "error" | "disconnected" }> {
  if (error instanceof ApiRequestError) return { status: error.disconnected ? "disconnected" : "error", message: error.message, correlationId: error.correlationId };
  return { status: "error", message: "Unexpected response from the service." };
}
