import { mkdirSync } from "node:fs";
import { baseUrl, loginAndSave, newAdminApi } from "./helpers/admin";
import { ensureSettings, ensureTerms } from "./helpers/seed";

// This suite creates products, orders and settings and logs in as admin: never aim it at real data.
function assertLocalTarget(): void {
  const host = new URL(baseUrl()).hostname;
  if (["localhost", "127.0.0.1"].includes(host) || process.env.E2E_ALLOW_REMOTE === "1") return;
  throw new Error(
    `Refusing to run E2E against "${host}": it writes test data and uses admin credentials. ` +
      "Use localhost, or set E2E_ALLOW_REMOTE=1 if you really mean a disposable remote environment.",
  );
}

// Runs once: one admin login (TOTP codes are single-use and failed logins are rate limited), then idempotent
// settings and terms. Per-flow data (products, orders) is created inside each test with unique names.
export default async function globalSetup(): Promise<void> {
  assertLocalTarget();
  mkdirSync(".auth", { recursive: true });
  await loginAndSave();
  const api = await newAdminApi();
  try {
    await ensureSettings(api);
    await ensureTerms(api);
  } finally {
    await api.ctx.dispose();
  }
}
