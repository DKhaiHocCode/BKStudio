import { z } from "zod";

/** Shapes of the public order API (CustomerOrderDtos / ProgressDtos). Responses are untrusted: parse, never cast. */

/** 256-bit base64url token. Anything else cannot exist, so we skip the request. */
export const TOKEN_PATTERN = /^[A-Za-z0-9_-]{43}$/;

const cents = z.number().int().nonnegative();
const text = z.string().max(2000);

export const orderSchema = z.object({
  publicCode: z.string().max(40),
  fulfillmentType: z.enum(["IN_STOCK", "MADE_TO_ORDER"]),
  status: z.string().max(40),
  subtotalCents: cents,
  depositCents: cents,
  shippingCents: cents,
  items: z.array(
    z.object({
      productName: text,
      variantLabel: text.nullable(),
      quantity: z.number().int().positive(),
      unitPriceCents: cents,
    }),
  ),
  paymentRequests: z.array(
    z.object({
      type: z.string().max(20),
      amountCents: cents,
      // Kept as a plain string: a bad URL must not hide the order, it just is not rendered as a link.
      paymentUrl: z.string().max(2000).nullable(),
      dueAt: z.string().nullable(),
    }),
  ),
  estimatedProductionMonth: z.string().nullable(),
  carrier: text.nullable(),
  trackingNumber: text.nullable(),
  history: z.array(z.object({ status: z.string().max(40), at: z.string() })),
  createdAt: z.string(),
  canCancel: z.boolean(),
  cancelWarning: z.boolean(),
});
export type OrderView = z.infer<typeof orderSchema>;

const photoSchema = z.object({
  url: z.string().max(2000),
  width: z.number().int().positive(),
  height: z.number().int().positive(),
});

export const progressSchema = z.object({
  stages: z.array(
    z.object({
      stage: z.string().max(40),
      label: z.string().max(120),
      explanation: z.string().max(2000),
      state: z.enum(["DONE", "CURRENT", "PENDING"]),
      approvedAt: z.string().nullable(),
      note: z.string().max(2000).nullable(),
      images: z.array(photoSchema).nullable(),
    }),
  ),
});
export type StageView = z.infer<typeof progressSchema>["stages"][number];

export const STATUS_LABEL: Record<string, string> = {
  AWAITING_PAYMENT: "Awaiting payment",
  AWAITING_DEPOSIT: "Awaiting deposit",
  QUEUED: "In the queue",
  IN_PRODUCTION: "In production",
  AWAITING_BALANCE: "Awaiting final payment",
  PAID_IN_FULL: "Paid in full",
  SHIPPED: "Shipped",
  COMPLETED: "Completed",
  CANCELLED: "Cancelled",
  CANCELLED_DEPOSIT_KEPT: "Cancelled (deposit kept)",
  CANCELLED_REFUNDED: "Cancelled (refunded)",
  FORFEITED: "Closed (final payment not received)",
};

export const PAYMENT_LABEL: Record<string, string> = {
  DEPOSIT: "Deposit",
  BALANCE: "Final payment",
  FULL: "Payment",
};

/** Unknown codes are shown as plain text, never dropped. */
export function statusLabel(code: string): string {
  return STATUS_LABEL[code] ?? code.replaceAll("_", " ").toLowerCase();
}

/** "2026-11-05T10:00:00Z" to "5 Nov 2026" in the visitor's time zone; "" when unparseable. */
export function formatDate(iso: string | null): string {
  if (!iso) return "";
  const date = new Date(iso);
  return Number.isNaN(date.getTime())
    ? ""
    : date.toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" });
}

/** "2027-01" to "January 2027"; "" when malformed. */
export function formatMonth(ym: string | null): string {
  const match = ym ? /^(\d{4})-(0[1-9]|1[0-2])$/.exec(ym) : null;
  if (!match) return "";
  return new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, 1)).toLocaleDateString("en-GB", {
    month: "long",
    year: "numeric",
    timeZone: "UTC",
  });
}

/** Payment links must be https; anything else (javascript:, http:, garbage) is not rendered as a link. */
export function httpsUrl(raw: string | null): string | null {
  if (!raw) return null;
  try {
    const url = new URL(raw);
    return url.protocol === "https:" ? url.href : null;
  } catch {
    return null;
  }
}

/** WIP photos: https, or plain http only for local dev storage. */
export function imageUrl(raw: string): string | null {
  try {
    const url = new URL(raw);
    const local = url.hostname === "localhost" || url.hostname === "127.0.0.1";
    return url.protocol === "https:" || (url.protocol === "http:" && local) ? url.href : null;
  } catch {
    return null;
  }
}

/**
 * Same-site path of a returned orderUrl, or null when it does not have the exact /orders/<token> shape.
 * Only the path is used, so a wrong host in the URL can never send the visitor elsewhere.
 */
export function orderPathOf(orderUrl: string): string | null {
  try {
    const path = new URL(orderUrl, "http://localhost").pathname;
    return new RegExp(`^/orders/${TOKEN_PATTERN.source.slice(1)}`).test(path) ? path : null;
  } catch {
    return null;
  }
}

/** Retry-After seconds as human text. */
export function waitText(seconds?: number): string {
  if (!seconds) return "a little while";
  return seconds >= 120 ? `${Math.ceil(seconds / 60)} minutes` : `${seconds} seconds`;
}
