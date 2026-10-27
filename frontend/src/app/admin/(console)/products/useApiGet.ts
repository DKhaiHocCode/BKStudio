"use client";

import { useCallback, useEffect, useState } from "react";
import { apiFetch } from "@/lib/api";

interface Loaded<T> {
  key: string;
  data?: T;
  error?: unknown;
}

/** GET `path` on mount and whenever it changes; `reload` refetches; `update` edits the cached copy. */
export function useApiGet<T>(path: string) {
  const [attempt, setAttempt] = useState(0);
  const [state, setState] = useState<Loaded<T> | null>(null);
  const key = `${path}#${attempt}`;

  useEffect(() => {
    let cancelled = false;
    apiFetch<T>(path)
      .then((data) => !cancelled && setState({ key, data }))
      .catch((error: unknown) => !cancelled && setState({ key, error }));
    return () => {
      cancelled = true;
    };
  }, [path, key]);

  const update = useCallback((fn: (data: T) => T) => {
    setState((s) => (s?.data === undefined ? s : { ...s, data: fn(s.data) }));
  }, []);
  const reload = useCallback(() => setAttempt((n) => n + 1), []);

  const settled = state?.key === key;
  return {
    loading: !settled,
    data: settled ? state.data : undefined,
    error: settled ? state.error : undefined,
    reload,
    update,
  };
}
