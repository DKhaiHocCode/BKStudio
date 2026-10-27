// Admin-only Tailwind classes using the docs/design/tokens.css values (global theme arrives in T-014).
export const inputClass =
  "min-h-11 w-full rounded-[14px] border-[3px] border-[#151515] bg-[#fffdf8] px-3 text-base text-[#151515]";

// Shared click feedback: hover tint, press (nudge + smaller hard shadow), visible focus ring, dimmed disabled.
// Transform and transition are dropped under prefers-reduced-motion; the colour change stays.
const feedback =
  "transition-[transform,box-shadow,background-color] duration-100 motion-reduce:transition-none " +
  "focus-visible:outline-[3px] focus-visible:outline-offset-2 focus-visible:outline-[#1b5fd1] " +
  "not-disabled:active:translate-x-0.5 not-disabled:active:translate-y-0.5 not-disabled:active:shadow-[1px_1px_0_#151515] " +
  "motion-reduce:not-disabled:active:translate-x-0 motion-reduce:not-disabled:active:translate-y-0 " +
  "disabled:cursor-not-allowed disabled:bg-[#e4dfd6] disabled:text-[#4a433c] disabled:opacity-60 disabled:shadow-none";
const solid =
  "min-h-11 rounded-[14px] border-[3px] border-[#151515] font-bold shadow-[3px_3px_0_#151515] " +
  feedback;

export const buttonClass = `${solid} bg-[#d63a1a] px-5 text-white not-disabled:hover:bg-[#a82a10] not-disabled:active:bg-[#8a220d]`;
export const secondaryButtonClass = `${solid} bg-[#fffdf8] px-4 text-[#151515] not-disabled:hover:bg-[#f6b634] not-disabled:active:bg-[#e0a32a]`;
export const dangerButtonClass = `${solid} bg-[#fffdf8] px-4 text-[#a82a10] not-disabled:hover:bg-[#f8c9be] not-disabled:active:bg-[#eeb3a5]`;
export const linkButtonClass =
  "min-h-11 px-2 text-sm font-medium text-[#4a433c] underline hover:text-[#151515] active:opacity-70 focus-visible:outline-[3px] focus-visible:outline-offset-2 focus-visible:outline-[#1b5fd1] disabled:cursor-not-allowed disabled:opacity-60";
export const cardClass =
  "w-full max-w-md rounded-[22px] border-[3px] border-[#151515] bg-[#fffdf8] p-6 shadow-[5px_5px_0_#151515]";
export const alertClass = "rounded-[14px] border-[3px] border-[#151515] bg-[#f8c9be] p-3 text-sm";
export const panelClass =
  "rounded-[22px] border-[3px] border-[#151515] bg-[#fffdf8] p-4 shadow-[5px_5px_0_#151515] sm:p-6";
