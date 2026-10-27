// Admin-only Tailwind classes (semantic tokens from globals.css, so admin follows the theme).
export const inputClass =
  "min-h-11 w-full rounded-[14px] border-[3px] border-border bg-card px-3 text-base text-fg";

// Shared click feedback: hover tint, press (nudge + smaller hard shadow), visible focus ring, dimmed disabled.
// Transform and transition are dropped under prefers-reduced-motion; the colour change stays.
const feedback =
  "transition-[transform,box-shadow,background-color] duration-100 motion-reduce:transition-none " +
  "focus-visible:outline-[3px] focus-visible:outline-offset-2 focus-visible:outline-focus " +
  "not-disabled:active:translate-x-0.5 not-disabled:active:translate-y-0.5 not-disabled:active:shadow-[1px_1px_0_var(--shadow-color)] " +
  "motion-reduce:not-disabled:active:translate-x-0 motion-reduce:not-disabled:active:translate-y-0 " +
  "disabled:cursor-not-allowed disabled:bg-disabled disabled:text-muted disabled:opacity-60 disabled:shadow-none";
const solid =
  "min-h-11 rounded-[14px] border-[3px] border-border font-bold shadow-sticker-sm " + feedback;

export const buttonClass = `${solid} bg-red px-5 text-cream not-disabled:hover:bg-red-dark not-disabled:active:bg-red-deep`;
export const secondaryButtonClass = `${solid} bg-card px-4 text-fg not-disabled:hover:bg-citrus not-disabled:hover:text-ink not-disabled:active:bg-citrus-deep not-disabled:active:text-ink`;
export const dangerButtonClass = `${solid} bg-card px-4 text-danger not-disabled:hover:bg-tint-pink not-disabled:hover:text-red-dark not-disabled:active:bg-tint-pink-deep not-disabled:active:text-red-dark`;
export const linkButtonClass =
  "min-h-11 px-2 text-sm font-medium text-muted underline hover:text-fg active:opacity-70 focus-visible:outline-[3px] focus-visible:outline-offset-2 focus-visible:outline-focus disabled:cursor-not-allowed disabled:opacity-60";
export const cardClass =
  "w-full max-w-md rounded-[22px] border-[3px] border-border bg-card p-6 shadow-sticker";
export const alertClass =
  "rounded-[14px] border-[3px] border-border bg-tint-pink p-3 text-sm text-ink";
export const panelClass =
  "rounded-[22px] border-[3px] border-border bg-card p-4 shadow-sticker sm:p-6";
