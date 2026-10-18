"use client";

import { useEffect, useState } from "react";
import { apiFetch } from "@/lib/api";

type State =
  | { kind: "loading" }
  | { kind: "ok"; status: string }
  | { kind: "error"; message: string; requestId?: string };

/** Calls /api/health from the browser, proving the same-origin proxy to the backend works. */
export function HealthStatus() {
  const [state, setState] = useState<State>({ kind: "loading" });

  useEffect(() => {
    let cancelled = false;
    apiFetch<{ status: string }>("/api/health")
      .then((body) => {
        if (!cancelled) setState({ kind: "ok", status: body.status });
      })
      .catch((e: Error & { requestId?: string }) => {
        if (!cancelled) setState({ kind: "error", message: e.message, requestId: e.requestId });
      });
    return () => {
      cancelled = true;
    };
  }, []);

  if (state.kind === "loading") return <p>Checking backend…</p>;
  if (state.kind === "error") {
    return (
      <p role="alert">
        Backend unreachable: {state.message}
        {state.requestId && <span className="font-mono"> ({state.requestId})</span>}
      </p>
    );
  }
  return (
    <p>
      Backend status: <strong data-testid="backend-status">{state.status}</strong>
    </p>
  );
}
