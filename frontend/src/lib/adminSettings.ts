import { z } from "zod";

export const settingsSchema = z.object({
  defaultCapacity: z.number(),
  paymentAllowedDomains: z.array(z.string()),
  social: z.object({
    reddit: z.string().nullable(),
    facebook: z.string().nullable(),
    instagram: z.string().nullable(),
    x: z.string().nullable(),
  }),
});
export type SettingsData = z.infer<typeof settingsSchema>;

export const currentTermsSchema = z.object({ version: z.string(), contentMd: z.string() });

export const SOCIAL_KEYS = ["reddit", "facebook", "instagram", "x"] as const;
export const MAX_DOMAINS = 20;
export const TERMS_MAX = 50_000;

// Mirrors SettingsDtos.HOSTNAME_REGEX; the server validates again.
export const HOSTNAME_RE =
  /^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/;
export const TERMS_VERSION_RE = /^\d{4}-\d{2}-\d{2}(-\d{1,3})?$/;

export function normalizeDomain(v: string): string {
  return v.trim().toLowerCase();
}

/** Empty string when valid, otherwise the message. The server validates again. */
export function capacityMessage(v: string): string {
  const t = v.trim();
  if (!/^\d+$/.test(t)) return "Enter a whole number.";
  const n = Number(t);
  return n < 1 || n > 50 ? "Must be between 1 and 50." : "";
}

/** Empty string when valid (empty = remove). Host rules are enforced by the server. */
export function socialMessage(v: string): string {
  const t = v.trim();
  if (t === "") return "";
  if (t.length > 300) return "At most 300 characters.";
  return t.startsWith("https://") ? "" : "Must start with https://";
}

/** Server field errors keyed by field, e.g. `social.reddit`, `paymentAllowedDomains[0]`. */
export function fieldErrorMap(
  errors: { field: string; message: string }[],
): Record<string, string> {
  const out: Record<string, string> = {};
  for (const e of errors) out[e.field] ??= e.message;
  return out;
}
