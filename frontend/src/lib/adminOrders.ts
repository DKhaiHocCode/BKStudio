import { z } from "zod";

export const ORDER_STATUSES = [
  "AWAITING_PAYMENT",
  "AWAITING_DEPOSIT",
  "QUEUED",
  "IN_PRODUCTION",
  "AWAITING_BALANCE",
  "PAID_IN_FULL",
  "SHIPPED",
  "COMPLETED",
  "CANCELLED",
  "CANCELLED_DEPOSIT_KEPT",
  "CANCELLED_REFUNDED",
  "FORFEITED",
] as const;

export const ORDER_PAGE_SIZE = 20;

// The order changed under us: reload instead of retrying. Other 409s (CONFLICT) are shown inline.
export const STALE_CODES: readonly string[] = ["VERSION_CONFLICT", "INVALID_TRANSITION"];

export const orderListSchema = z.object({
  items: z.array(
    z.object({
      id: z.number(),
      publicCode: z.string(),
      status: z.string(),
      fulfillmentType: z.string(),
      subtotalCents: z.number(),
      shippingCents: z.number(),
      version: z.number(),
      createdAt: z.string(),
    }),
  ),
  page: z.number(),
  size: z.number(),
  total: z.number(),
});
export type OrderList = z.infer<typeof orderListSchema>;

const nullableText = z.string().nullable();

export const adminOrderSchema = z.object({
  id: z.number(),
  publicCode: z.string(),
  status: z.string(),
  fulfillmentType: z.string(),
  version: z.number(),
  subtotalCents: z.number(),
  shippingCents: z.number(),
  currency: z.string(),
  customer: z.object({
    name: z.string(),
    email: z.string(),
    phone: nullableText,
    line1: z.string(),
    line2: nullableText,
    city: z.string(),
    region: nullableText,
    postalCode: nullableText,
    country: z.string(),
  }),
  items: z.array(
    z.object({
      id: z.number(),
      variantId: z.number(),
      productName: z.string(),
      scale: z.string(),
      unitPriceCents: z.number(),
      quantity: z.number(),
    }),
  ),
  paymentRequests: z.array(
    z.object({
      id: z.number(),
      type: z.string(),
      amountCents: z.number(),
      channel: z.string(),
      paymentUrl: z.string(),
      status: z.string(),
      dueAt: nullableText,
    }),
  ),
  carrier: nullableText,
  trackingNumber: nullableText,
  cancelReason: nullableText,
  adminNote: nullableText,
  createdAt: z.string(),
  updatedAt: z.string(),
  shippedAt: nullableText,
  completedAt: nullableText,
  restockedAt: nullableText,
  history: z.array(
    z.object({
      at: z.string(),
      action: z.string(),
      actorType: z.string(),
      fromStatus: nullableText,
      toStatus: nullableText,
      transition: nullableText,
    }),
  ),
  progress: z.array(
    z.object({
      stage: z.string(),
      approvedAt: z.string(),
      note: nullableText,
      images: z.array(z.object({ url: z.string(), width: z.number(), height: z.number() })),
    }),
  ),
  allowedActions: z.array(z.string()),
});
export type AdminOrder = z.infer<typeof adminOrderSchema>;

// Limits mirror AdminOrderService (carrier 50, tracking 100, reason 500, refundRef 100, note 2000).
export const shipSchema = z.object({
  carrier: z.string().trim().min(1, "Enter the carrier.").max(50, "At most 50 characters."),
  trackingNumber: z
    .string()
    .trim()
    .min(1, "Enter the tracking number.")
    .max(100, "At most 100 characters."),
});
export const cancelSchema = z.object({
  reason: z.string().trim().min(1, "Enter a reason.").max(500, "At most 500 characters."),
  refundRef: z.string().trim().max(100, "At most 100 characters."),
});
export const noteSchema = z.object({
  adminNote: z.string().max(2000, "At most 2000 characters."),
});

export const PAYMENT_CHANNELS = ["ARTISTREE", "SHOPEE_PROXY", "OTHER"] as const;
export const paymentUrlSchema = z
  .string()
  .trim()
  .max(500, "At most 500 characters.")
  .refine((v) => /^https:\/\/\S+$/i.test(v), "Enter an https:// link.");
// Confirm limit mirrors the service (externalRef 1-100, reason 1-500).
export const confirmPaymentSchema = z.object({
  externalRef: z.string().trim().min(1, "Enter the payment reference.").max(100, "At most 100."),
});
export const cancelPaymentSchema = z.object({
  reason: z.string().trim().min(1, "Enter a reason.").max(500, "At most 500 characters."),
});

/** Payment type the server accepts for this order state (API_SPEC), or null when none. */
export function expectedPaymentType(o: {
  fulfillmentType: string;
  status: string;
}): "DEPOSIT" | "FULL" | "BALANCE" | null {
  if (o.fulfillmentType === "MADE_TO_ORDER" && o.status === "AWAITING_DEPOSIT") return "DEPOSIT";
  if (o.fulfillmentType === "IN_STOCK" && o.status === "AWAITING_PAYMENT") return "FULL";
  if (o.fulfillmentType === "MADE_TO_ORDER" && o.status === "AWAITING_BALANCE") return "BALANCE";
  return null;
}

/** Mirror of the server rule; an estimate only, the server validates and wins. */
export function estimatePaymentCents(
  type: "DEPOSIT" | "FULL" | "BALANCE",
  subtotalCents: number,
  shippingCents: number,
): number {
  const deposit = Math.floor((subtotalCents + 1) / 2);
  if (type === "DEPOSIT") return deposit;
  if (type === "FULL") return subtotalCents + shippingCents;
  return subtotalCents - deposit + shippingCents;
}

/** "12.5" -> 1250; null when not a USD amount with at most 2 decimals. */
export function usdToCents(v: string): number | null {
  const t = v.trim();
  if (!/^\d{1,6}(\.\d{1,2})?$/.test(t)) return null;
  return Math.round(Number(t) * 100);
}

export const PROGRESS_STAGES = ["RAW_PRINT", "PREP", "PAINT"] as const;
export const PROGRESS_MAX_FILES = 5;
export const PROGRESS_MAX_BYTES = 10 * 1024 * 1024; // ImageProcessor.MAX_BYTES
// JPEG/PNG only: the server refuses WebP input on purpose (see ImageProcessor javadoc).
export const PROGRESS_MIME = ["image/jpeg", "image/png"];

export function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString("en-GB", { dateStyle: "medium", timeStyle: "short" });
}
