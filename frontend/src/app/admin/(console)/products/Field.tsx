import type { ReactNode } from "react";

/** Label + control + inline error. The control must set aria-invalid itself. */
export function Field({
  label,
  error,
  hint,
  children,
}: {
  label: string;
  error?: string;
  hint?: string;
  children: ReactNode;
}) {
  return (
    <label className="flex flex-col gap-1 font-medium">
      {label}
      {children}
      {hint && <span className="text-sm font-normal text-[#4a433c]">{hint}</span>}
      {error && (
        <span role="alert" className="text-sm font-bold text-[#a82a10]">
          {error}
        </span>
      )}
    </label>
  );
}
