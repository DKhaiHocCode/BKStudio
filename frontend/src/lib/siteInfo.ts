import { useEffect, useState } from "react";
import { z } from "zod";
import { apiFetch } from "@/lib/api";

// Each link is an https URL or null (admin edits them in /admin/settings).
const link = z.url({ protocol: /^https$/ }).nullable();
const siteInfoSchema = z.object({ reddit: link, facebook: link, instagram: link, x: link });

export type SiteInfo = z.infer<typeof siteInfoSchema>;
export type SiteInfoState =
  { status: "loading" } | { status: "ready"; info: SiteInfo } | { status: "error" };

/** Social links (GET /api/site-info). The public GET is cached for 60s by the backend. */
export function useSiteInfo(): SiteInfoState {
  const [state, setState] = useState<SiteInfoState>({ status: "loading" });
  useEffect(() => {
    apiFetch<unknown>("/api/site-info")
      .then((body): SiteInfoState => {
        const parsed = siteInfoSchema.safeParse(body);
        return parsed.success ? { status: "ready", info: parsed.data } : { status: "error" };
      })
      .catch((): SiteInfoState => ({ status: "error" }))
      .then(setState);
  }, []);
  return state;
}
