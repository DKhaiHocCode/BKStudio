"use client";

import { useState } from "react";
import { inputClass, secondaryButtonClass } from "../../ui";

/** Copies the public product URL; if the clipboard is unavailable, shows it selected for manual copy. */
export function CopyLinkButton({ slug }: { slug: string }) {
  const [state, setState] = useState<"idle" | "copied" | "failed">("idle");
  const url = `${window.location.origin}/products/${encodeURIComponent(slug)}`;

  async function copy() {
    try {
      await navigator.clipboard.writeText(url);
      setState("copied");
      setTimeout(() => setState("idle"), 2000);
    } catch {
      setState("failed");
    }
  }

  return (
    <span className="flex flex-wrap items-center gap-2">
      <button type="button" onClick={() => void copy()} className={secondaryButtonClass}>
        Copy link
      </button>
      <span role="status" className="text-sm font-medium">
        {state === "copied" && "Copied"}
      </span>
      {state === "failed" && (
        <input
          readOnly
          autoFocus
          aria-label="Product link"
          value={url}
          onFocus={(e) => e.currentTarget.select()}
          className={`${inputClass} w-72 max-w-full`}
        />
      )}
    </span>
  );
}
