import { useSyncExternalStore } from "react";
import { z } from "zod";

/**
 * Cart kept in localStorage (no accounts, no server cart). Everything read back is untrusted, so it
 * is re-validated with zod and bad lines are dropped. `priceCents` is a display snapshot only: the
 * server prices every order from the database.
 */

export type CartMode = "stock" | "mto";

/** Server limits (CheckoutDtos.java): 1-10 lines per order, quantity 1-5 per line. */
export const MAX_LINES = 10;
export const MAX_QUANTITY = 5;

const lineSchema = z.object({
  variantId: z.number().int().positive(),
  // Same shape as catalog.ts SLUG_PATTERN; the slug ends up in a request path.
  productSlug: z
    .string()
    .max(100)
    .regex(/^[a-z0-9]+(-[a-z0-9]+)*$/),
  productName: z.string().min(1).max(200),
  scale: z.string().min(1).max(20),
  mode: z.enum(["stock", "mto"]),
  quantity: z.number().int().min(1).max(MAX_QUANTITY),
  priceCents: z.number().int().min(0).max(10_000_000),
  // Rendered as <img src>: absolute http(s) only (http covers dev MinIO); production CSP img-src restricts the hosts.
  imageUrl: z
    .string()
    .max(2000)
    .regex(/^https?:\/\//)
    .optional(),
});

export type CartLine = z.infer<typeof lineSchema>;

const EMPTY: readonly CartLine[] = [];
const KEY = "bk.cart.v1";

const sameLine = (a: CartLine, b: { variantId: number; mode: CartMode }) =>
  a.variantId === b.variantId && a.mode === b.mode;

function parse(raw: string | null): readonly CartLine[] {
  if (!raw) return EMPTY;
  let data: unknown;
  try {
    data = JSON.parse(raw);
  } catch {
    return EMPTY;
  }
  if (!Array.isArray(data)) return EMPTY;
  const lines: CartLine[] = [];
  for (const item of data) {
    const result = lineSchema.safeParse(item);
    if (result.success && !lines.some((l) => sameLine(l, result.data))) lines.push(result.data);
  }
  return lines.length === 0 ? EMPTY : lines.slice(0, MAX_LINES);
}

// Memory is the source of truth in this tab; localStorage is a best-effort mirror, so the cart
// still works (for this page view) when storage is blocked.
// shortcut: if setItem fails but getItem works (quota), the next read restores the stored copy; fine for a tiny cart.
let snapshot: readonly CartLine[] = EMPTY;
let rawSeen: string | null = null;
const listeners = new Set<() => void>();

function getSnapshot(): readonly CartLine[] {
  let raw: string | null;
  try {
    raw = window.localStorage.getItem(KEY);
  } catch {
    return snapshot; // storage blocked
  }
  if (raw !== rawSeen) {
    rawSeen = raw;
    snapshot = parse(raw);
  }
  return snapshot;
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  const onStorage = (event: StorageEvent) => {
    if (event.key === KEY || event.key === null) listener();
  };
  window.addEventListener("storage", onStorage);
  return () => {
    listeners.delete(listener);
    window.removeEventListener("storage", onStorage);
  };
}

function write(input: readonly CartLine[]) {
  // Re-validate on every write so tampered values (e.g. edited DOM) never enter the snapshot.
  const lines = input.filter((l) => lineSchema.safeParse(l).success);
  snapshot = lines.length === 0 ? EMPTY : lines;
  try {
    const raw = lines.length === 0 ? null : JSON.stringify(lines);
    if (raw === null) window.localStorage.removeItem(KEY);
    else window.localStorage.setItem(KEY, raw);
    rawSeen = raw;
  } catch {
    // Blocked or full: keep the in-memory copy.
  }
  listeners.forEach((l) => l());
}

const subscribeNever = () => () => {};

/** `ready` is false during SSR/hydration, so pages can avoid flashing "your cart is empty". */
export function useCart(): { lines: readonly CartLine[]; ready: boolean } {
  const lines = useSyncExternalStore(subscribe, getSnapshot, () => EMPTY);
  const ready = useSyncExternalStore(
    subscribeNever,
    () => true,
    () => false,
  );
  return { lines, ready };
}

export const countItems = (lines: readonly CartLine[]) =>
  lines.reduce((sum, l) => sum + l.quantity, 0);

export const subtotalOf = (lines: readonly CartLine[]) =>
  lines.reduce((sum, l) => sum + l.priceCents * l.quantity, 0);

/** Mirrors the server: depositCents = (subtotalCents + 1) / 2. Estimate only. */
export const depositOf = (subtotalCents: number) => Math.floor((subtotalCents + 1) / 2);

export type AddResult = "added" | "capped" | "full" | "invalid";

/** Same variant + mode merges quantity (capped at 5). */
export function addToCart(input: CartLine): AddResult {
  const checked = lineSchema.safeParse({
    ...input,
    quantity: Math.min(MAX_QUANTITY, Math.max(1, Math.trunc(input.quantity) || 1)),
  });
  if (!checked.success) return "invalid";
  const line = checked.data;
  const current = getSnapshot();
  const existing = current.find((l) => sameLine(l, line));
  if (existing) {
    const wanted = existing.quantity + line.quantity;
    const quantity = Math.min(MAX_QUANTITY, wanted);
    write(current.map((l) => (l === existing ? { ...l, ...line, quantity } : l)));
    return wanted > MAX_QUANTITY ? "capped" : "added";
  }
  if (current.length >= MAX_LINES) return "full";
  write([...current, line]);
  return "added";
}

export function updateLine(
  target: { variantId: number; mode: CartMode },
  patch: Partial<Pick<CartLine, "quantity" | "priceCents">>,
) {
  write(getSnapshot().map((l) => (sameLine(l, target) ? { ...l, ...patch } : l)));
}

export function removeLine(target: { variantId: number; mode: CartMode }) {
  write(getSnapshot().filter((l) => !sameLine(l, target)));
}

/** After a successful order only the submitted lines leave the cart. */
export function removeLines(targets: readonly { variantId: number; mode: CartMode }[]) {
  write(getSnapshot().filter((l) => !targets.some((t) => sameLine(l, t))));
}
