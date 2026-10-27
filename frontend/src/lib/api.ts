/**
 * Thin fetch wrapper for the BKStudio backend.
 *
 * Paths are same-origin (`/api/...`): in dev Next.js rewrites them to the Spring Boot server
 * (see next.config.ts), in production the reverse proxy does (ADR-0001). No CORS involved.
 */

/** Problem Details body returned by the backend (API_SPEC §1). */
interface ProblemBody {
  title?: string;
  detail?: string;
  status?: number;
  code?: string;
  requestId?: string;
  errors?: { field: string; message: string }[];
}

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly requestId?: string;
  /** Seconds from the Retry-After header (429), if present. */
  readonly retryAfter?: number;
  readonly fieldErrors: { field: string; message: string }[];

  constructor(init: {
    status: number;
    code: string;
    message: string;
    requestId?: string;
    retryAfter?: number;
    fieldErrors?: { field: string; message: string }[];
  }) {
    super(init.message);
    this.name = "ApiError";
    this.status = init.status;
    this.code = init.code;
    this.requestId = init.requestId;
    this.retryAfter = init.retryAfter;
    this.fieldErrors = init.fieldErrors ?? [];
  }
}

/** Cookie set by the backend (Spring `csrf.spa()`); JS must echo it in the X-XSRF-TOKEN header. */
function readCsrfCookie(): string | undefined {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/);
  if (!match) return undefined;
  try {
    return decodeURIComponent(match[1]);
  } catch {
    return undefined; // malformed cookie: treat as missing so ensureCsrfToken re-fetches
  }
}

/** The backend issues the cookie lazily on a GET; the anonymous 401 of /me is the cheapest one. */
async function ensureCsrfToken(): Promise<string | undefined> {
  if (!readCsrfCookie()) {
    await fetch("/api/admin/auth/me", { credentials: "same-origin" }).catch(() => undefined);
  }
  return readCsrfCookie();
}

/** Login steps report their own 401 (bad credentials), so they must not trigger the redirect. */
const LOGIN_STEPS = ["/api/admin/auth/login", "/api/admin/auth/totp"];

export async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const method = (init?.method ?? "GET").toUpperCase();
  const headers: Record<string, string> = { Accept: "application/json" };
  if (method !== "GET" && method !== "HEAD") {
    const token = await ensureCsrfToken();
    if (token) headers["X-XSRF-TOKEN"] = token;
  }

  let response: Response;
  try {
    response = await fetch(path, {
      credentials: "same-origin",
      ...init,
      headers: { ...headers, ...init?.headers },
    });
  } catch {
    // Backend down or offline: there is no response, so no requestId either.
    throw new ApiError({
      status: 0,
      code: "NETWORK_ERROR",
      message: "Could not reach the server. Please try again.",
    });
  }

  // Session expired mid-use: send the admin back to sign in.
  if (
    response.status === 401 &&
    path.startsWith("/api/admin/") &&
    !LOGIN_STEPS.includes(path) &&
    !window.location.pathname.startsWith("/admin/login")
  ) {
    // Full reload on purpose: drops any admin state held in memory.
    // eslint-disable-next-line @next/next/no-location-assign-relative-destination
    window.location.href = "/admin/login";
  }

  if (!response.ok) {
    const body = (await response.json().catch(() => null)) as ProblemBody | null;
    throw new ApiError({
      status: response.status,
      code: body?.code ?? "UNKNOWN_ERROR",
      message: body?.detail ?? body?.title ?? `Request failed (${response.status})`,
      // Prefer the body, fall back to the header so even non-JSON errors (e.g. proxy 502) are traceable.
      requestId: body?.requestId ?? response.headers.get("X-Request-Id") ?? undefined,
      retryAfter: Number(response.headers.get("Retry-After")) || undefined,
      fieldErrors: body?.errors,
    });
  }

  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}
