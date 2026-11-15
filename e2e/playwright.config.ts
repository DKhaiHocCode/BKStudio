import { defineConfig, devices } from "@playwright/test";

// One origin, like production (ADR-0001): the frontend serves the pages and rewrites /api/* to the backend.
const baseURL = process.env.E2E_BASE_URL ?? "http://localhost:3000";

export default defineConfig({
  testDir: "./tests",
  globalSetup: "./global-setup.ts",
  // One worker: the flows share one admin session (one TOTP login, see global-setup.ts).
  workers: 1,
  fullyParallel: false,
  retries: 0,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
  use: {
    baseURL,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
