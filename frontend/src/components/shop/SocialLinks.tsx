"use client";

import { focusRing } from "@/components/ui";
import { type SiteInfo, useSiteInfo } from "@/lib/siteInfo";

const NETWORKS: { key: keyof SiteInfo; label: string }[] = [
  { key: "reddit", label: "Reddit" },
  { key: "facebook", label: "Facebook" },
  { key: "instagram", label: "Instagram" },
  { key: "x", label: "X" },
];

/** Social links from /api/site-info; nulls are hidden, none (or a failed load) shows a fallback. */
export function SocialLinks({ className = "" }: { className?: string }) {
  const state = useSiteInfo();
  if (state.status === "loading") return <div className={className} aria-hidden="true" />;

  const links = state.status === "ready" ? NETWORKS.filter((n) => state.info[n.key] !== null) : [];
  if (state.status !== "ready" || links.length === 0) {
    return <p className={`m-0 text-muted ${className}`}>Contact links coming soon</p>;
  }
  return (
    <ul className={`m-0 flex list-none flex-wrap gap-x-5 p-0 font-bold ${className}`}>
      {links.map(({ key, label }) => (
        <li key={key}>
          <a
            href={state.info[key] ?? undefined}
            target="_blank"
            rel="noopener noreferrer"
            aria-label={`BKStudio on ${label} (opens in a new tab)`}
            className={`inline-flex min-h-11 items-center text-fg hover:text-danger ${focusRing}`}
          >
            {label}
          </a>
        </li>
      ))}
    </ul>
  );
}
