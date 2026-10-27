import { z } from "zod";
import { PRICE_PATTERN, dollarsToCents } from "@/lib/money";
import { PRODUCT_STATUSES } from "./types";

/** Mirrors AdminProductDtos.java; the backend re-validates everything. */

export const productSchema = z.object({
  name: z.string().trim().min(1, "Name is required").max(200, "At most 200 characters"),
  // Only used on create; "" means "let the server generate it".
  slug: z.union([
    z.literal(""),
    z
      .string()
      .max(80, "At most 80 characters")
      .regex(/^[a-z0-9]+(-[a-z0-9]+)*$/, "Lowercase letters, digits and single hyphens only"),
  ]),
  series: z.string().trim().max(200, "At most 200 characters"),
  description: z.string().max(10_000, "At most 10,000 characters"),
  status: z.enum(PRODUCT_STATUSES),
});

const wholeNumber = (max: number) =>
  z
    .string()
    .trim()
    .regex(/^\d+$/, "Enter a whole number")
    .transform(Number)
    .refine((n) => n <= max, `At most ${max.toLocaleString("en-US")}`);

const variantShape = {
  price: z
    .string()
    .trim()
    .regex(PRICE_PATTERN, "Enter a price like 49.90")
    .transform(dollarsToCents)
    .refine((c) => c >= 1 && c <= 10_000_000, "Between $0.01 and $100,000.00"),
  stockQty: wholeNumber(100_000),
  madeToOrderEnabled: z.boolean(),
  quota: z.union([z.literal(""), wholeNumber(100_000)]),
  active: z.boolean(),
};

const quotaRule = (v: { madeToOrderEnabled: boolean; quota: "" | number }) =>
  !v.madeToOrderEnabled || v.quota !== "";
const quotaIssue = { path: ["quota"], message: "Required for made-to-order" };

export const variantEditSchema = z.object(variantShape).refine(quotaRule, quotaIssue);
export const variantCreateSchema = z
  .object({
    scale: z
      .string()
      .trim()
      .regex(/^1\/[0-9]{1,2}$/, "Use the form 1/6 or 1/12"),
    ...variantShape,
  })
  .refine(quotaRule, quotaIssue);

export const altTextSchema = z.string().trim().max(200, "At most 200 characters");
