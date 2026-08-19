import { useEffect, useState } from "react";
import { Link, NavLink, Outlet, useLocation, useNavigate } from "react-router-dom";

import { ApiRequestError, waitForRankedMatch } from "../api/goldenPathApi";
import { currentSessionUserId } from "../api/session";
import { setActiveMatch, useActiveMatch } from "../battle/useActiveMatch";
import { pauseRankedQueue, updateRankedQueue, useRankedQueue } from "../battle/useRankedQueue";

const routes = [
  ["/sign-in", "Sign in"],
  ["/feed", "Feed"],
  ["/ranked", "Ranked"],
] as const;

export function AppShell() {
  const activeMatchId = useActiveMatch();
  const navigate = useNavigate();
  const rankedQueue = useRankedQueue();
  const { pathname } = useLocation();
  const [now, setNow] = useState(() => Date.now());
  const matchedQueueId = rankedQueue?.status === "MATCHED" ? rankedQueue.matchId : null;
  const queueElapsed = rankedQueue?.status === "QUEUED" && rankedQueue.enqueuedAt !== null
    ? formatElapsed(rankedQueue.enqueuedAt, now)
    : null;
  const showMatchedQueue = matchedQueueId !== null && pathname !== `/battles/${matchedQueueId}`;
  const showRejoin = !showMatchedQueue && activeMatchId !== null && pathname !== `/battles/${activeMatchId}`;
  const showSearchingQueue = !showMatchedQueue && !showRejoin && rankedQueue?.status === "QUEUED";

  useEffect(() => {
    if (rankedQueue?.status !== "QUEUED") return undefined;
    let active = true;
    const controller = new AbortController();
    const poll = async () => {
      while (active && !controller.signal.aborted) {
        try {
          const ticket = await waitForRankedMatch(rankedQueue.language, {
            idempotencyKey: rankedQueue.idempotencyKey,
            signal: controller.signal,
            onTicket: (next) => {
              if (next.status === "QUEUED") updateRankedQueue(next);
            },
          });
          if (!active || ticket.matchId === null) return;
          updateRankedQueue(ticket);
          setActiveMatch(ticket.matchId, currentSessionUserId());
          if (typeof document !== "undefined"
              && document.hidden
              && "Notification" in window
              && Notification.permission === "granted") {
            new Notification("Ranked match found", { body: "Return to your battle." });
          }
          return;
        } catch (error) {
          if (controller.signal.aborted) return;
          if (error instanceof ApiRequestError && error.status >= 400 && error.status < 500) {
            if (error.status === 401) {
              pauseRankedQueue("SESSION_REQUIRED");
              navigate("/sign-in", { replace: true });
            } else {
              pauseRankedQueue(error.status === 409 ? "CONFLICT" : null);
            }
            return;
          }
          try {
            await sleep(750, controller.signal);
          } catch {
            return;
          }
        }
      }
    };
    void poll();
    return () => {
      active = false;
      controller.abort();
    };
  }, [navigate, rankedQueue?.idempotencyKey, rankedQueue?.language, rankedQueue?.status]);

  useEffect(() => {
    if (!showSearchingQueue) return undefined;
    const timer = window.setInterval(() => setNow(Date.now()), 1_000);
    return () => window.clearInterval(timer);
  }, [showSearchingQueue]);

  return (
    <div className="scaffold-shell">
      <header className="scaffold-header">
        <strong className="scaffold-brand">D3</strong>
        <span>Dopamin-Driven Development</span>
        <b>STRUCTURAL PROTOTYPE · VERSIONED API SURFACE</b>
      </header>
      <nav className="scaffold-nav" aria-label="Golden path routes">
        {routes.map(([to, label]) => (
          <NavLink key={to} to={to}>
            {label}
          </NavLink>
        ))}
      </nav>
      {showRejoin && (
        <div className="rejoin-banner" role="status">
          <span className="rejoin-dot" aria-hidden="true" />
          <span>Match in progress</span>
          <Link className="rejoin-action" to={`/battles/${activeMatchId}`}>
            Return to your match →
          </Link>
        </div>
      )}
      {showSearchingQueue && (
        <div className="rejoin-banner" role="status">
          <span className="rejoin-dot" aria-hidden="true" />
          <span>Searching for ranked match{queueElapsed === null ? "" : ` · ${queueElapsed}`}</span>
          <Link className="rejoin-action" to="/ranked">
            View queue
          </Link>
        </div>
      )}
      {showMatchedQueue && (
        <div className="rejoin-banner" role="status">
          <span className="rejoin-dot" aria-hidden="true" />
          <span>Ranked match found</span>
          <Link className="rejoin-action" to={`/battles/${matchedQueueId}`}>
            Enter battle
          </Link>
        </div>
      )}
      <main>
        <Outlet />
      </main>
    </div>
  );
}

function sleep(milliseconds: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const timer = window.setTimeout(resolve, milliseconds);
    signal.addEventListener("abort", () => {
      window.clearTimeout(timer);
      reject(new DOMException("Queue polling aborted.", "AbortError"));
    }, { once: true });
  });
}

function formatElapsed(enqueuedAt: string, now: number): string {
  const totalSeconds = Math.max(0, Math.floor((now - Date.parse(enqueuedAt)) / 1_000));
  return `${Math.floor(totalSeconds / 60).toString().padStart(2, "0")}:${(totalSeconds % 60).toString().padStart(2, "0")}`;
}
