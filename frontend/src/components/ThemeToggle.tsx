"use client";

import { useSyncExternalStore } from "react";
import { focusRing } from "@/components/ui";
import { THEME_KEY } from "@/lib/theme";

type Choice = "system" | "light" | "dark";

const ORDER: Choice[] = ["system", "light", "dark"];
const LABEL: Record<Choice, string> = { system: "System", light: "Light", dark: "Dark" };
const listeners = new Set<() => void>();

// The inline script in layout.tsx sets data-theme before paint, so the DOM attribute is the source of truth.
function read(): Choice {
  const value = document.documentElement.dataset.theme;
  return value === "light" || value === "dark" ? value : "system";
}

function subscribe(onChange: () => void) {
  listeners.add(onChange);
  return () => void listeners.delete(onChange);
}

function choose(next: Choice) {
  if (next === "system") delete document.documentElement.dataset.theme;
  else document.documentElement.dataset.theme = next;
  try {
    if (next === "system") localStorage.removeItem(THEME_KEY);
    else localStorage.setItem(THEME_KEY, next);
  } catch {
    // Storage blocked: the choice still applies for this page view.
  }
  listeners.forEach((l) => l());
}

const ICON: Record<Choice, string> = {
  system: "M3 5h18v11H3zM9 20h6M12 16v4",
  light:
    "M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8zM12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4",
  dark: "M20 14.5A8 8 0 0 1 9.5 4 8 8 0 1 0 20 14.5z",
};

/** Cycles System -> Light -> Dark. Server and first client render show "System", then it syncs. */
export function ThemeToggle({ className = "" }: { className?: string }) {
  const current = useSyncExternalStore(subscribe, read, () => "system" as Choice);
  const next = ORDER[(ORDER.indexOf(current) + 1) % ORDER.length];
  return (
    <button
      type="button"
      onClick={() => choose(next)}
      aria-label={`Theme: ${LABEL[current]}. Switch to ${LABEL[next]}.`}
      title={`Theme: ${LABEL[current]}`}
      className={`inline-flex size-11 flex-none items-center justify-center rounded-full border-[3px] border-border bg-card text-fg hover:bg-citrus hover:text-ink ${focusRing} ${className}`}
    >
      <svg
        width="20"
        height="20"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2.4"
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
      >
        <path d={ICON[current]} />
      </svg>
    </button>
  );
}
