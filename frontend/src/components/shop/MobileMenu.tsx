"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { focusRing } from "@/components/ui";
import { NAV_LINKS } from "./nav";

/** Disclosure pattern: a button toggles a link list; Escape closes it and returns focus. */
export function MobileMenu() {
  const [open, setOpen] = useState(false);
  const button = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setOpen(false);
        button.current?.focus();
      }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [open]);

  return (
    <>
      <button
        ref={button}
        type="button"
        aria-expanded={open}
        aria-controls="mobile-nav"
        aria-label={open ? "Close menu" : "Open menu"}
        onClick={() => setOpen((value) => !value)}
        className={`inline-flex size-11 items-center justify-center rounded-full border-[3px] border-border bg-card text-fg ${focusRing}`}
      >
        <svg
          width="20"
          height="20"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2.6"
          strokeLinecap="round"
          aria-hidden="true"
        >
          {open ? <path d="M6 6l12 12M18 6L6 18" /> : <path d="M4 7h16M4 12h16M4 17h16" />}
        </svg>
      </button>
      {open && (
        <nav
          id="mobile-nav"
          aria-label="Main"
          className="absolute inset-x-0 top-full z-20 flex flex-col border-b-[3px] border-border bg-card px-4 py-2 shadow-sticker"
        >
          {NAV_LINKS.map((link) => (
            <Link
              key={link.href}
              href={link.href}
              onClick={() => setOpen(false)}
              className={`flex min-h-11 items-center font-bold text-fg no-underline ${focusRing}`}
            >
              {link.label}
            </Link>
          ))}
        </nav>
      )}
    </>
  );
}
