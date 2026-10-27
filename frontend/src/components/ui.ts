// Shared public-shop Tailwind classes (semantic tokens only; see globals.css).
// Click feedback: hover tint, press (nudge + smaller hard shadow), visible focus ring, dimmed
// disabled. Transform/transition are dropped under prefers-reduced-motion; the colour stays.
export const focusRing =
  "focus-visible:outline-[3px] focus-visible:outline-offset-2 focus-visible:outline-focus";

const feedback =
  "transition-[transform,box-shadow,background-color] duration-100 motion-reduce:transition-none " +
  focusRing +
  " not-disabled:active:translate-x-0.5 not-disabled:active:translate-y-0.5 not-disabled:active:shadow-[1px_1px_0_var(--shadow-color)] " +
  "motion-reduce:not-disabled:active:translate-x-0 motion-reduce:not-disabled:active:translate-y-0 " +
  "disabled:cursor-not-allowed disabled:bg-disabled disabled:text-muted disabled:opacity-60 disabled:shadow-none";

const solid =
  "inline-flex min-h-11 items-center justify-center rounded-button border-[3px] border-border " +
  "px-6 py-3 text-center font-bold shadow-sticker no-underline " +
  feedback;

export const primaryButton = `${solid} bg-red text-cream not-disabled:hover:bg-red-dark`;
export const secondaryButton = `${solid} bg-card text-fg not-disabled:hover:bg-citrus not-disabled:hover:text-ink`;

const pill =
  "inline-flex min-h-11 items-center rounded-full border-2 border-border px-4 font-bold " +
  feedback;
export const chip = `${pill} bg-card text-fg not-disabled:hover:bg-citrus not-disabled:hover:text-ink`;
export const chipActive = `${pill} bg-inverse text-on-inverse`;

export const badge =
  "rounded-full border-2 border-border px-2.5 py-0.5 text-xs font-bold sm:text-[13px]";
// Tinted badges keep ink text in every theme (the tints stay light): add text-ink next to bg-tint-*.
