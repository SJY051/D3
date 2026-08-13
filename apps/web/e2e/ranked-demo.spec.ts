import { expect, test } from "@playwright/test";

test.skip("D3-UX-002 deterministic ranked battle demonstration", async ({ page }) => {
  await page.goto("/ranked");
  await expect(page.getByText("Ranked matchmaking")).toBeVisible();
  throw new Error("Enable after matchmaking, battle, result, and record projection are implemented");
});
