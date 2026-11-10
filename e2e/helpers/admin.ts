import { existsSync } from "node:fs";
import { request as pwRequest, type APIRequestContext } from "@playwright/test";
import { totpCode } from "./totp";

export const AUTH_FILE = ".auth/admin.json";

export function requiredEnv(name: string): string {
  const v = process.env[name];
  if (!v) throw new Error(`Missing env var ${name}`);
  return v;
}

export function baseUrl(): string {
  return process.env.E2E_BASE_URL ?? "http://localhost:3000";
}

/** Error carries status + problem code only: bodies and payloads may hold personal data. */
export class ApiFailure extends Error {
  constructor(
    readonly status: number,
    readonly code: string | undefined,
    where: string,
  ) {
    super(`${where} -> ${status} ${code ?? ""}`.trim());
  }
}

/**
 * Authenticated admin API client. Mutating calls echo the XSRF-TOKEN cookie in X-XSRF-TOKEN
 * (the backend issues the cookie on the first GET, API_SPEC section 3).
 */
export class AdminApi {
  constructor(readonly ctx: APIRequestContext) {}

  private async csrf(): Promise<string> {
    const read = async () =>
      (await this.ctx.storageState()).cookies.find((c) => c.name === "XSRF-TOKEN")?.value;
    let token = await read();
    if (!token) {
      await this.ctx.get("/api/admin/auth/me");
      token = await read();
    }
    if (!token) throw new Error("No XSRF-TOKEN cookie issued by the backend");
    return decodeURIComponent(token);
  }

  async send<T = unknown>(
    method: "GET" | "POST" | "PUT" | "PATCH",
    path: string,
    body?: { json?: unknown; multipart?: Record<string, string> },
  ): Promise<T> {
    const headers: Record<string, string> =
      method === "GET" ? {} : { "X-XSRF-TOKEN": await this.csrf() };
    const res = await this.ctx.fetch(path, {
      method,
      headers,
      data: body?.json,
      multipart: body?.multipart,
    });
    if (!res.ok()) {
      const code = ((await res.json().catch(() => ({}))) as { code?: string }).code;
      throw new ApiFailure(res.status(), code, `${method} ${path}`);
    }
    return (res.status() === 204 ? undefined : await res.json()) as T;
  }

  get = <T>(path: string) => this.send<T>("GET", path);
  post = <T>(path: string, json?: unknown) => this.send<T>("POST", path, { json });
  put = <T>(path: string, json: unknown) => this.send<T>("PUT", path, { json });
}

/** True when AUTH_FILE holds a session the backend still accepts (a rerun then needs no new TOTP code). */
async function savedSessionIsValid(): Promise<boolean> {
  if (!existsSync(AUTH_FILE)) return false;
  const ctx = await pwRequest.newContext({ baseURL: baseUrl(), storageState: AUTH_FILE });
  try {
    return (await ctx.get("/api/admin/auth/me")).ok();
  } finally {
    await ctx.dispose();
  }
}

/** Password + TOTP login once (unless a saved session is still valid), then persist the cookies to AUTH_FILE. */
export async function loginAndSave(): Promise<void> {
  if (await savedSessionIsValid()) return;
  const email = requiredEnv("E2E_ADMIN_EMAIL");
  const password = requiredEnv("E2E_ADMIN_PASSWORD");
  const secret = requiredEnv("E2E_TOTP_SECRET");

  const ctx = await pwRequest.newContext({ baseURL: baseUrl() });
  try {
    const api = new AdminApi(ctx);
    await api.post("/api/admin/auth/login", { email, password });
    // A previous run may have used this 30 s step already (replay guard rejects it with 401). Wait for the
    // next step boundary and retry, at most twice, so the failed-login limiter (5 per 15 min) is never near.
    for (let attempt = 0; ; attempt++) {
      try {
        await api.post("/api/admin/auth/totp", { code: totpCode(secret) });
        break;
      } catch (e) {
        if (!(e instanceof ApiFailure) || e.status !== 401 || attempt >= 2) throw e;
        await new Promise((r) => setTimeout(r, 30_000 - (Date.now() % 30_000) + 500));
      }
    }
    await ctx.storageState({ path: AUTH_FILE });
  } finally {
    await ctx.dispose();
  }
}

export async function newAdminApi(): Promise<AdminApi> {
  const ctx = await pwRequest.newContext({ baseURL: baseUrl(), storageState: AUTH_FILE });
  return new AdminApi(ctx);
}
