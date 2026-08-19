import { useEffect } from "react";
import { Link, NavLink, Outlet, useLocation } from "react-router-dom";

import { waitForRankedMatch } from "../api/goldenPathApi";
import { currentSessionUserId } from "../api/session";
import { setActiveMatch, useActiveMatch } from "../battle/useActiveMatch";
import { updateRankedQueue, useRankedQueue } from "../battle/useRankedQueue";

const routes = [
  ["/sign-in", "Sign in"],
  ["/feed", "Feed"],
  ["/ranked", "Ranked"],
] as const;

export function AppShell() {
  const activeMatchId = useActiveMatch();
  const rankedQueue = useRankedQueue();
  const { pathname } = useLocation();
  const matchedQueueId = rankedQueue?.status === "MATCHED" ? rankedQueue.matchId : null;
  const showMatchedQueue = matchedQueueId !== null && pathname !== `/battles/${matchedQueueId}`;
  const showRejoin = !showMatchedQueue && activeMatchId !== null && pathname !== `/battles/${activeMatchId}`;
  const showSearchingQueue = !showMatchedQueue && !showRejoin && rankedQueue?.status === "QUEUED";

  useEffect(() => {
    if (rankedQueue?.status !== "QUEUED") return undefined;
    let active = true;
    const controller = new AbortController();
    void waitForRankedMatch(rankedQueue.language, {
      idempotencyKey: rankedQueue.idempotencyKey,
      signal: controller.signal,
      onTicket: (ticket) => {
        if (ticket.status === "QUEUED") updateRankedQueue(ticket);
      },
    }).then((ticket) => {
      if (!active || ticket.matchId === null) return;
      updateRankedQueue(ticket);
      setActiveMatch(ticket.matchId, currentSessionUserId());
      if (typeof document !== "undefined"
          && document.hidden
          && "Notification" in window
          && Notification.permission === "granted") {
        new Notification("Ranked match found", { body: "Return to your battle." });
      }
    }).catch(() => undefined);
    return () => {
      active = false;
      controller.abort();
    };
  }, [rankedQueue?.idempotencyKey, rankedQueue?.language, rankedQueue?.status]);

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
          <span>Searching for ranked match</span>
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
