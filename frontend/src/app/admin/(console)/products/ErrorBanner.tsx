"use client";

import { ApiError } from "@/lib/api";
import { alertClass, secondaryButtonClass } from "../../ui";

function messageOf(e: unknown): string {
  if (!(e instanceof ApiError)) return "Something went wrong. Please try again.";
  if (e.status === 413) return "That file is larger than 10 MB.";
  if (e.status === 429) {
    const wait = e.retryAfter ? ` Try again in ${e.retryAfter} s.` : "";
    return `The server is busy processing images.${wait}`;
  }
  return e.message;
}

/** Shows a failed request; a VERSION_CONFLICT offers a reload of the page data. */
export function ErrorBanner({ error, onReload }: { error: unknown; onReload?: () => void }) {
  if (!error) return null;
  const conflict = error instanceof ApiError && error.code === "VERSION_CONFLICT";
  return (
    <div role="alert" className={`${alertClass} flex flex-wrap items-center gap-3`}>
      <span>
        {conflict
          ? "This was changed in another tab. Reload to see the latest version."
          : messageOf(error)}
        {error instanceof ApiError && error.requestId && (
          <span className="block font-mono">Reference: {error.requestId}</span>
        )}
      </span>
      {conflict && onReload && (
        <button type="button" onClick={onReload} className={secondaryButtonClass}>
          Reload
        </button>
      )}
    </div>
  );
}
