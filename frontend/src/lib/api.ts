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
  readonly fieldErrors: { field: string; message: string }[];

  constructor(init: {
    status: number;
    code: string;
    message: string;
    requestId?: string;
    fieldErrors?: { field: string; message: string }[];
  }) {
    super(init.message);
    this.name = "ApiError";
    this.status = init.status;
    this.code = init.code;
    this.requestId = init.requestId;
    this.fieldErrors = init.fieldErrors ?? [];
  }
}

export async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response;
  try {
    response = await fetch(path, {
      ...init,
      headers: { Accept: "application/json", ...init?.headers },
    });
  } catch {
    // Backend down or offline: there is no response, so no requestId either.
    throw new ApiError({
      status: 0,
      code: "NETWORK_ERROR",
      message: "Could not reach the server. Please try again.",
    });
  }

  if (!response.ok) {
    const body = (await response.json().catch(() => null)) as ProblemBody | null;
    throw new ApiError({
      status: response.status,
      code: body?.code ?? "UNKNOWN_ERROR",
      message: body?.detail ?? body?.title ?? `Request failed (${response.status})`,
      // Prefer the body, fall back to the header so even non-JSON errors (e.g. proxy 502) are traceable.
      requestId: body?.requestId ?? response.headers.get("X-Request-Id") ?? undefined,
      fieldErrors: body?.errors,
    });
  }

  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}
