import { z } from "zod";

export const productionMonthsSchema = z.array(
  z.object({ month: z.string(), capacity: z.number(), allocated: z.number() }),
);

export const queueSchema = z.object({
  month: z.string(),
  entries: z.array(
    z.object({
      id: z.number(),
      orderItemId: z.number(),
      unitIndex: z.number(),
      status: z.string(),
      queuedAt: z.string(),
    }),
  ),
});

export const MONTH_RE = /^\d{4}-(0[1-9]|1[0-2])$/;

/** Current UTC month, yyyy-MM. */
export function currentMonth(): string {
  return new Date().toISOString().slice(0, 7);
}

/** yyyy-MM plus n months (pure arithmetic, no timezone involved). */
export function addMonths(month: string, n: number): string {
  const [y, m] = month.split("-").map(Number);
  const idx = y * 12 + (m - 1) + n;
  return `${Math.floor(idx / 12)}-${String((idx % 12) + 1).padStart(2, "0")}`;
}

/** Empty string when valid, otherwise the message. The server validates again. */
export function capacityError(v: string): string {
  const t = v.trim();
  if (!/^\d+$/.test(t)) return "Enter a whole number.";
  return Number(t) > 1000 ? "At most 1000." : "";
}
