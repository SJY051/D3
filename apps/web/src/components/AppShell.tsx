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
        <strong className="scaffold-brand" aria-label="D cubed">D<sup>3</sup></strong>
        <span className="scaffold-product-name">Dopamin-Driven Development</span>
        <b className="scaffold-status">Developer network · live coding arena</b>
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
