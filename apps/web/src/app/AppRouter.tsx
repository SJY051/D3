import { Navigate, RouterProvider, createBrowserRouter } from "react-router-dom";

import { AppShell } from "../components/AppShell";
import { ScaffoldPage } from "../pages/ScaffoldPage";
import { LiveBattlePage } from "../pages/LiveBattlePage";
import { GoldenPathPage } from "../pages/GoldenPathPage";

const router = createBrowserRouter([
  {
    path: "/",
    element: <AppShell />,
    children: [
      { index: true, element: <Navigate to="/feed" replace /> },
      { path: "sign-in", element: <GoldenPathPage kind="sign-in" /> },
      { path: "feed", element: <GoldenPathPage kind="feed" /> },
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
      { path: "ranked", element: <GoldenPathPage kind="ranked" /> },
      {
        path: "battles/:matchId",
        element: <LiveBattlePage />,
      },
      { path: "results/:matchId", element: <GoldenPathPage kind="result" /> },
      { path: "players/:playerId", element: <GoldenPathPage kind="record" /> },
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
