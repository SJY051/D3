import { Navigate, RouterProvider, createBrowserRouter } from "react-router-dom";

import { AppShell } from "../components/AppShell";
import { ScaffoldPage } from "../pages/ScaffoldPage";
import { LiveBattlePage } from "../pages/LiveBattlePage";

const router = createBrowserRouter([
  {
    path: "/",
    element: <AppShell />,
    children: [
      { index: true, element: <Navigate to="/feed" replace /> },
      {
        path: "sign-in",
        element: (
          <ScaffoldPage
            wireframe="WF-01"
            requirement="D3-ID-001"
            title="Sign in"
            sections={["Password login", "OAuth entry", "Session recovery"]}
          />
        ),
      },
      {
        path: "feed",
        element: (
          <ScaffoldPage
            wireframe="WF-02"
            requirement="D3-COM-001"
            title="Developer feed"
            sections={["Composer", "Timeline", "Rank identity"]}
          />
        ),
      },
      {
        path: "practice",
        element: (
          <ScaffoldPage
            wireframe="WF-08"
            requirement="D3-SOLO-001"
            title="Solo practice"
            sections={["Problem catalog", "Practice editor", "Private solution history"]}
          />
        ),
      },
      {
        path: "ranked",
        element: (
          <ScaffoldPage
            wireframe="WF-03"
            requirement="D3-BTL-001"
            title="Ranked matchmaking"
            sections={["Language pool", "Queue state", "Current rank"]}
          />
        ),
      },
      {
        path: "battles/:matchId",
        element: <LiveBattlePage />,
      },
      {
        path: "results/:matchId",
        element: (
          <ScaffoldPage
            wireframe="WF-05"
            requirement="D3-BTL-003 · D3-BTL-005"
            title="Battle result"
            sections={["Outcome", "Score breakdown", "Rating change"]}
          />
        ),
      },
      {
        path: "players/:handle",
        element: (
          <ScaffoldPage
            wireframe="WF-06"
            requirement="D3-STAT-001"
            title="Player record"
            sections={["Tier and RP", "Public rating", "Match history"]}
          />
        ),
      },
      {
        path: "admin/problems",
        element: (
          <ScaffoldPage
            wireframe="WF-07"
            requirement="D3-ADM-001"
            title="Problem administration"
            sections={["Problem list", "Review state", "Publish controls"]}
          />
        ),
      },
    ],
  },
]);

export function AppRouter() {
  return <RouterProvider router={router} />;
}
