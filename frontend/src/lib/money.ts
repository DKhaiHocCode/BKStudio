/** Dollars typed by a human, e.g. "49" or "49.90" (max 100000.00 = the backend's 10_000_000 cents). */
export const PRICE_PATTERN = /^\d{1,6}(\.\d{1,2})?$/;

/** String to integer cents without floats. Call only on a string that matches PRICE_PATTERN. */
export function dollarsToCents(dollars: string): number {
  const [whole, fraction = ""] = dollars.split(".");
  return Number(whole) * 100 + Number(fraction.padEnd(2, "0"));
}

/** Integer cents to "49.90" (for input values). */
export function centsToDollars(cents: number): string {
  return `${Math.floor(cents / 100)}.${String(cents % 100).padStart(2, "0")}`;
}

export function formatUsd(cents: number): string {
  return `$${Math.floor(cents / 100).toLocaleString("en-US")}.${String(cents % 100).padStart(2, "0")}`;
}
