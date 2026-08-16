import { NavLink, Outlet } from "react-router-dom";

const routes = [
  ["/sign-in", "Sign in"],
  ["/feed", "Feed"],
  ["/ranked", "Ranked"],
] as const;

export function AppShell() {
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
      <main>
        <Outlet />
      </main>
    </div>
  );
}
