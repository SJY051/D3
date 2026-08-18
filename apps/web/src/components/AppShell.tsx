import { Link, NavLink, Outlet, useLocation } from "react-router-dom";

import { useActiveMatch } from "../battle/useActiveMatch";

const routes = [
  ["/sign-in", "Sign in"],
  ["/feed", "Feed"],
  ["/ranked", "Ranked"],
] as const;

export function AppShell() {
  const activeMatchId = useActiveMatch();
  const { pathname } = useLocation();
  const showRejoin = activeMatchId !== null && pathname !== `/battles/${activeMatchId}`;

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
      <main>
        <Outlet />
      </main>
    </div>
  );
}
