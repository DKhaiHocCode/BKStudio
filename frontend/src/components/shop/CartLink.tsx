"use client";

import Link from "next/link";
import { focusRing } from "@/components/ui";
import { countItems, useCart } from "@/lib/cart";
import { CartIcon } from "./CartIcon";

const pill =
  "inline-flex min-h-11 items-center gap-2 rounded-full border-[3px] border-border bg-citrus px-4 font-bold text-ink no-underline shadow-sticker-sm " +
  focusRing;
const icon =
  "relative inline-flex size-11 items-center justify-center rounded-full border-[3px] border-border bg-citrus text-ink " +
  focusRing;

/** Header cart link with a live item count (reads the localStorage cart). */
export function CartLink({ variant }: { variant: "pill" | "icon" }) {
  const count = countItems(useCart().lines);
  const label = `Cart, ${count} ${count === 1 ? "item" : "items"}`;

  if (variant === "pill") {
    return (
      <Link href="/cart" aria-label={label} className={pill}>
        <CartIcon />
        <span aria-hidden="true">Cart · {count}</span>
      </Link>
    );
  }
  return (
    <Link href="/cart" aria-label={label} className={icon}>
      <CartIcon />
      {count > 0 && (
        <span
          aria-hidden="true"
          className="absolute -top-1.5 -right-1.5 min-w-5 rounded-full border-2 border-border bg-red px-1 text-center text-xs leading-4 font-bold text-cream"
        >
          {count}
        </span>
      )}
    </Link>
  );
}
