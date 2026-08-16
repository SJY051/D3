import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { GoldenPathPage, type GoldenPathKind } from "../pages/GoldenPathPage";

const cases: Array<[GoldenPathKind, string, string]> = [
  ["sign-in", "WF-01", "Sign in"],
  ["feed", "WF-02", "Loading authoritative data…"],
  ["ranked", "WF-03", "Join ranked queue"],
  ["result", "WF-05", "Invalid match link"],
  ["record", "WF-06", "Player record"],
];

describe("P0 API-backed route surfaces", () => {
  it.each(cases)(
    "renders %s from approved %s without preview data",
    (kind, wireframe, evidence) => {
      const html = renderToStaticMarkup(
        <MemoryRouter>
          <GoldenPathPage kind={kind} />
        </MemoryRouter>,
      );

      expect(html).toContain(wireframe);
      expect(html).toContain(evidence);
      expect(html).toContain("versioned API contract");
      expect(html).not.toContain("Continue with GitHub");
      expect(html).not.toContain("Follow (P1)");
    },
  );
});

describe.skip("D3-UX-001 and D3-UX-002 final acceptance", () => {
  it("waits for two browser sessions and the integrated P0 golden path", () => {
    expect.fail("Route API boundaries are not final end-to-end acceptance evidence.");
  });
});
