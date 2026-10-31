import { useCallback, useEffect, useState } from "react";
import { z } from "zod";
import { ApiError, apiFetch } from "@/lib/api";

const termsSchema = z.object({ version: z.string().min(1).max(64), contentMd: z.string() });

export type TermsState =
  | { status: "loading" }
  | { status: "ready"; version: string; contentMd: string }
  | { status: "none" } // nothing published yet (404): checkout stays closed
  | { status: "error" };

/** Current terms (GET /api/terms/current). `reload` bypasses the HTTP cache (needed after TERMS_OUTDATED). */
export function useTerms(): { terms: TermsState; reload: () => void } {
  const [terms, setTerms] = useState<TermsState>({ status: "loading" });

  const fetchTerms = useCallback(() => {
    apiFetch<unknown>("/api/terms/current", { cache: "no-store" })
      .then((body): TermsState => {
        const parsed = termsSchema.safeParse(body);
        if (!parsed.success) return { status: "error" };
        return parsed.data.contentMd.trim() === ""
          ? { status: "none" }
          : { status: "ready", ...parsed.data };
      })
      .catch((e: unknown): TermsState =>
        e instanceof ApiError && e.status === 404 ? { status: "none" } : { status: "error" },
      )
      .then(setTerms);
  }, []);

  useEffect(fetchTerms, [fetchTerms]);

  const reload = useCallback(() => {
    setTerms({ status: "loading" });
    fetchTerms();
  }, [fetchTerms]);

  return { terms, reload };
}
