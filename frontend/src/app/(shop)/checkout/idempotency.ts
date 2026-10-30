import { z } from "zod";
import type { CartLine, CartMode } from "@/lib/cart";

/**
 * One Idempotency-Key per checkout attempt, kept in sessionStorage per cart type until the order
 * succeeds, so a retry or refresh replays the same order instead of creating a second one.
 * `sig` ties the key to the cart contents: edit the cart and the next attempt gets a fresh key.
 */
const stored = z.object({ sig: z.string(), key: z.uuid() });
const memory = new Map<CartMode, z.infer<typeof stored>>(); // used when sessionStorage is blocked

const storageKey = (mode: CartMode) => `bk.checkout.key.${mode}`;

export const signatureOf = (lines: readonly CartLine[]) =>
  lines
    .map((l) => `${l.variantId}x${l.quantity}`)
    .sort()
    .join(",");

export function getIdempotencyKey(mode: CartMode, sig: string): string {
  let current: z.infer<typeof stored> | undefined = memory.get(mode);
  try {
    const parsed = stored.safeParse(
      JSON.parse(window.sessionStorage.getItem(storageKey(mode)) ?? ""),
    );
    if (parsed.success) current = parsed.data;
  } catch {
    // blocked storage or no/garbled value: fall back to memory
  }
  if (current?.sig === sig) return current.key;

  const fresh = { sig, key: crypto.randomUUID() };
  memory.set(mode, fresh);
  try {
    window.sessionStorage.setItem(storageKey(mode), JSON.stringify(fresh));
  } catch {
    // memory copy is enough for this page view
  }
  return fresh.key;
}

export function clearIdempotencyKey(mode: CartMode) {
  memory.delete(mode);
  try {
    window.sessionStorage.removeItem(storageKey(mode));
  } catch {
    // nothing to clear
  }
}
