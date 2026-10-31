import type { ReactNode } from "react";
import { inputField } from "@/components/ui";

/** Label + control + inline error, wired with aria-invalid / aria-describedby. */
export function TextField({
  name,
  label,
  error,
  optional,
  type = "text",
  autoComplete,
  hint,
  className = "",
  children,
}: {
  name: string;
  label: string;
  error?: string;
  optional?: boolean;
  type?: "text" | "email" | "tel";
  autoComplete?: string;
  hint?: string;
  className?: string;
  /** Replaces the <input> (used for the country <select>). */
  children?: ReactNode;
}) {
  const id = `f-${name}`;
  const describedBy = [error && `${id}-err`, hint && `${id}-hint`].filter(Boolean).join(" ");
  const aria = {
    id,
    name,
    "aria-invalid": error ? true : undefined,
    "aria-describedby": describedBy || undefined,
    className: inputField,
  } as const;

  return (
    <div className={`flex min-w-0 flex-col gap-1.5 ${className}`}>
      <label htmlFor={id} className="text-[15px] font-bold">
        {label}
        {optional && <span className="font-normal text-muted"> (optional)</span>}
      </label>
      {children ? (
        <select {...aria} autoComplete={autoComplete} defaultValue="">
          {children}
        </select>
      ) : (
        <input {...aria} type={type} autoComplete={autoComplete} />
      )}
      {hint && (
        <span id={`${id}-hint`} className="text-sm text-muted">
          {hint}
        </span>
      )}
      {error && (
        <span id={`${id}-err`} className="text-sm font-bold text-danger">
          {error}
        </span>
      )}
    </div>
  );
}
