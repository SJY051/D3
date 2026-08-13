import { NavLink, Outlet } from "react-router-dom";

const routes = [
  ["/feed", "Feed"],
  ["/practice", "Practice"],
  ["/ranked", "Ranked"],
  ["/players/demo", "Record"],
  ["/admin/problems", "Admin"],
] as const;

export function AppShell() {
  return (
    <div className="scaffold-shell">
      <header className="scaffold-header">
        <span className="scaffold-brand">D³</span>
        <span>Dopamin-Driven Development</span>
        <strong>STRUCTURAL PROTOTYPE</strong>
      </header>
      <nav aria-label="Prototype routes" className="scaffold-nav">
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
