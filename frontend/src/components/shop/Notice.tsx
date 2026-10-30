import type { ReactNode } from "react";

const TONE = {
  info: "bg-tint-commission text-ink border-border",
  warn: "bg-tint-mto text-ink border-border",
  error: "bg-card text-fg border-danger",
} as const;

/** Callout box (mockup "info box"). Wrap it in a role="alert"/"status" element for dynamic messages. */
export function Notice({
  tone = "info",
  children,
}: {
  tone?: keyof typeof TONE;
  children: ReactNode;
}) {
  return (
    <div
      className={`flex items-start gap-3 rounded-[18px] border-[3px] px-[18px] py-3.5 leading-normal ${TONE[tone]}`}
    >
      <svg
        width="22"
        height="22"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2.4"
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
        className="mt-0.5 flex-none"
      >
        <circle cx="12" cy="12" r="9" />
        <path d="M12 8v5" />
        <path d="M12 16.5v.01" />
      </svg>
      <div className="min-w-0">{children}</div>
    </div>
  );
}
