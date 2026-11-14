"use client";

import { useState } from "react";
import { primaryButton } from "@/components/ui";

/** Visual only: the prototype has no cart, so the button answers with an inline note. */
export function AddToCart() {
  const [clicked, setClicked] = useState(false);
  return (
    <div className="flex flex-col gap-3">
      <button
        type="button"
        onClick={() => setClicked(true)}
        className={`${primaryButton} w-full text-lg`}
      >
        Add to cart
      </button>
      {/* Always in the DOM so screen readers announce the text when it appears. */}
      <p role="status" className="m-0 empty:hidden">
        {clicked && (
          <span className="inline-block rounded-button border-2 border-ink bg-citrus px-3 py-1.5 text-sm font-bold text-ink">
            Prototype: cart not wired
          </span>
        )}
      </p>
    </div>
  );
}
