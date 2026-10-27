import type { z } from "zod";
import { ApiError } from "@/lib/api";

export type FieldErrors = Record<string, string>;

export function zodErrors(error: z.ZodError): FieldErrors {
  const out: FieldErrors = {};
  for (const issue of error.issues) {
    out[String(issue.path[0] ?? "")] ??= issue.message;
  }
  return out;
}

/** Backend field names equal the form field names, except the two the form renames. */
const FORM_FIELD: Record<string, string> = { priceCents: "price", madeToOrderQuota: "quota" };

export function apiFieldErrors(e: unknown): FieldErrors {
  const out: FieldErrors = {};
  if (e instanceof ApiError) {
    for (const f of e.fieldErrors) out[FORM_FIELD[f.field] ?? f.field] ??= f.message;
  }
  return out;
}

/** Moves keyboard focus to the first field marked aria-invalid, once React has rendered the errors. */
export function focusFirstInvalid(form: HTMLElement) {
  requestAnimationFrame(() => form.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus());
}
