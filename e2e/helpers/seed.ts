import { randomBytes } from "node:crypto";
import type { AdminApi } from "./admin";

export const PAY_DOMAINS = ["artistree.io", "shopee.sg"];

interface Settings {
  defaultCapacity: number;
  paymentAllowedDomains: string[];
  social: Record<string, string | null>;
}

/** Idempotent: adds the payment link domains to the existing settings (keeps everything else). */
export async function ensureSettings(api: AdminApi): Promise<void> {
  const s = await api.get<Settings>("/api/admin/settings");
  const domains = [...new Set([...s.paymentAllowedDomains, ...PAY_DOMAINS])];
  await api.put("/api/admin/settings", { ...s, paymentAllowedDomains: domains });
}

/** Checkout needs a published terms version; publishes one only when none exists yet (terms are immutable). */
export async function ensureTerms(api: AdminApi): Promise<void> {
  const res = await api.ctx.get("/api/terms/current");
  if (res.ok()) return;
  await api.post("/api/admin/terms", {
    contentMd: "E2E terms. Deposits are non-refundable once queued.",
  });
}

export const uid = (): string => randomBytes(4).toString("hex");

interface Variant {
  id: number;
}
interface Product {
  id: number;
  slug: string;
  name: string;
}

export interface SeededProduct {
  productId: number;
  slug: string;
  name: string;
  variantId: number;
  priceCents: number;
}

export interface VariantSpec {
  priceCents: number;
  stockQty: number;
  madeToOrderEnabled: boolean;
  madeToOrderQuota?: number;
}

/** Creates an ACTIVE (or UNLISTED) product with one 1/7 variant. Unique name per call. */
export async function createProduct(
  api: AdminApi,
  status: "ACTIVE" | "UNLISTED",
  spec: VariantSpec,
): Promise<SeededProduct> {
  const name = `E2E Figure ${uid()}`;
  const product = await api.post<Product>("/api/admin/products", {
    name,
    series: "E2E Series",
    description: "Created by the E2E suite.",
    status,
  });
  const variant = await api.post<Variant>(`/api/admin/products/${product.id}/variants`, {
    scale: "1/7",
    active: true,
    ...spec,
  });
  return {
    productId: product.id,
    slug: product.slug,
    name,
    variantId: variant.id,
    priceCents: spec.priceCents,
  };
}
