import { z } from "zod";
import type { CartLine, CartMode } from "@/lib/cart";

/** Mirrors CheckoutDtos.java; the backend re-validates everything. */

/** No control, format (bidi, zero-width) or line/paragraph separator characters (server NO_CONTROL). */
const NO_CONTROL = /^[^\p{Cc}\p{Cf}\p{Zl}\p{Zp}]*$/u;
const EMAIL =
  /^[^@\s\p{Cc}\p{Cf}\p{Zl}\p{Zp}]+@[^@\s\p{Cc}\p{Cf}\p{Zl}\p{Zp}]+\.[^@\s\p{Cc}\p{Cf}\p{Zl}\p{Zp}]+$/u;
const BAD_CHARS = "Contains characters that are not allowed";

const text = (label: string, max: number, required: boolean) => {
  const base = z.string().trim();
  return (required ? base.min(1, `${label} is required`) : base)
    .max(max, `At most ${max} characters`)
    .regex(NO_CONTROL, BAD_CHARS);
};

export const checkoutSchema = z.object({
  name: text("Name", 120, true),
  email: z
    .string()
    .trim()
    .min(1, "Email is required")
    .max(254, "At most 254 characters")
    .regex(EMAIL, "Enter a valid email address"),
  phone: text("Phone", 40, false),
  line1: text("Address", 200, true),
  line2: text("Address line 2", 200, false),
  city: text("City", 100, true),
  region: text("State", 100, false),
  postalCode: text("Postcode", 20, false),
  country: z.enum(["SG", "AU"], "Choose Singapore or Australia"),
});

export type CheckoutValues = z.infer<typeof checkoutSchema>;
export type FieldErrors = Record<string, string>;

export function zodErrors(error: z.ZodError): FieldErrors {
  const out: FieldErrors = {};
  for (const issue of error.issues) out[String(issue.path[0] ?? "")] ??= issue.message;
  return out;
}

/** Form input names, in page order (focus goes to the first invalid one). */
export const FIELD_NAMES = [
  "name",
  "email",
  "phone",
  "country",
  "line1",
  "line2",
  "city",
  "region",
  "postalCode",
] as const;

/** Server paths ("customer.email", "shippingAddress.line1", "acceptedTermsVersion") to input names. */
export function formFieldOf(serverField: string): string | null {
  if (serverField === "acceptedTermsVersion") return "terms";
  const match = /^(?:customer|shippingAddress)\.(\w+)$/.exec(serverField);
  return match && (FIELD_NAMES as readonly string[]).includes(match[1]) ? match[1] : null;
}

export interface OrderCreated {
  code: string;
  status: string;
  /** Only on 201; null on an idempotent replay (200). */
  orderUrl: string | null;
  subtotalCents: number;
  depositCents: number;
}

/** Request body of POST /api/orders. Prices are never sent. */
export function toRequest(
  mode: CartMode,
  lines: readonly CartLine[],
  v: CheckoutValues,
  acceptedTermsVersion: string,
) {
  return {
    fulfillmentType: mode === "mto" ? "MADE_TO_ORDER" : "IN_STOCK",
    items: lines.map((l) => ({ variantId: l.variantId, quantity: l.quantity })),
    customer: { name: v.name, email: v.email, phone: v.phone || null },
    shippingAddress: {
      line1: v.line1,
      line2: v.line2 || null,
      city: v.city,
      region: v.region || null,
      postalCode: v.postalCode || null,
      country: v.country,
    },
    acceptedTermsVersion,
  };
}
