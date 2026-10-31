"use client";

import Link from "next/link";
import { Notice } from "@/components/shop/Notice";
import { ProductImage } from "@/components/shop/ProductImage";
import { badge, focusRing, panel, primaryButton, secondaryButton } from "@/components/ui";
import {
  MAX_QUANTITY,
  depositOf,
  removeLine,
  subtotalOf,
  updateLine,
  useCart,
  type CartLine,
  type CartMode,
} from "@/lib/cart";
import { formatUsd } from "@/lib/money";
import { useLineChecks, type LineCheck } from "./useLineChecks";

const BLOCKS: { mode: CartMode; title: string; badgeClass: string }[] = [
  { mode: "stock", title: "In stock", badgeClass: "bg-tint-stock" },
  { mode: "mto", title: "Made to order", badgeClass: "bg-tint-mto" },
];

export function CartView() {
  const { lines, ready } = useCart();
  const checkOf = useLineChecks(lines);

  if (!ready) return <p className="font-bold text-muted">Loading your cart…</p>;
  if (lines.length === 0) {
    return (
      <div className="flex flex-col items-start gap-4">
        <p className="m-0 text-lg">Your cart is empty.</p>
        <Link href="/#shop" className={primaryButton}>
          Browse the shop
        </Link>
      </div>
    );
  }

  return (
    <>
      <Notice>
        <p className="m-0">
          Ready-to-ship and made-to-order figures are paid differently, so they check out{" "}
          <strong>separately</strong>. Pick which one to check out first; the other stays in your
          cart. Prices here are indicative: the shop confirms the real amount in your payment link.
        </p>
      </Notice>
      <div className="flex flex-wrap items-start gap-6">
        {BLOCKS.map(({ mode, title, badgeClass }) => {
          const blockLines = lines.filter((l) => l.mode === mode);
          if (blockLines.length === 0) return null;
          return (
            <CartBlock
              key={mode}
              mode={mode}
              title={title}
              badgeClass={badgeClass}
              lines={blockLines}
              checkOf={checkOf}
            />
          );
        })}
      </div>
      <Link href="/#shop" className={`self-start font-bold ${focusRing}`}>
        ← Keep shopping
      </Link>
    </>
  );
}

function CartBlock({
  mode,
  title,
  badgeClass,
  lines,
  checkOf,
}: {
  mode: CartMode;
  title: string;
  badgeClass: string;
  lines: CartLine[];
  checkOf: (line: CartLine) => LineCheck;
}) {
  const subtotal = subtotalOf(lines);
  const deposit = depositOf(subtotal);
  const checks = lines.map(checkOf);
  const blocked = checks.some((c) => c.kind === "changed" || c.kind === "unavailable");
  const pending = checks.some((c) => c.kind === "pending");
  const checkout = `${mode === "mto" ? primaryButton : secondaryButton} text-[17px]`;
  const hintId = `hint-${mode}`;

  return (
    <section
      aria-labelledby={`block-${mode}`}
      className={`${panel} flex min-w-0 flex-[1_1_440px] flex-col gap-4`}
    >
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 id={`block-${mode}`} className="m-0 text-2xl font-extrabold">
          {title}
        </h2>
        <span className={`${badge} ${badgeClass} text-ink`}>
          {mode === "mto" ? "50% deposit now" : "Pay 100% now"}
        </span>
      </div>

      <ul className="m-0 flex list-none flex-col p-0">
        {lines.map((line, i) => (
          <CartLineRow key={line.variantId} line={line} check={checks[i]} />
        ))}
      </ul>

      <dl className="m-0 flex flex-col gap-2">
        <Row label="Subtotal (estimate)" value={formatUsd(subtotal)} />
        {mode === "mto" ? (
          <>
            <Row strong label="Deposit due now (50%)" value={formatUsd(deposit)} />
            <Row muted label="Balance when finished" value={formatUsd(subtotal - deposit)} />
          </>
        ) : (
          <Row strong label="Due now (100%)" value={formatUsd(subtotal)} />
        )}
        <Row muted label="Shipping" value="Quoted in your payment link" />
      </dl>

      {mode === "mto" && (
        <Notice tone="warn">
          <p className="m-0 text-[15px]">
            The deposit is <strong>non-refundable</strong> once your figure is queued for
            production.
          </p>
        </Notice>
      )}

      {blocked ? (
        <>
          <button type="button" disabled aria-describedby={hintId} className={checkout}>
            Checkout
          </button>
          <p id={hintId} className="m-0 text-sm font-bold text-danger">
            Resolve the items marked above (accept the new price or remove them) to continue.
          </p>
        </>
      ) : pending ? (
        <button type="button" disabled className={checkout}>
          Checking prices…
        </button>
      ) : (
        <Link href={`/checkout?type=${mode}`} className={checkout}>
          Checkout
        </Link>
      )}
    </section>
  );
}

function Row({
  label,
  value,
  strong,
  muted,
}: {
  label: string;
  value: string;
  strong?: boolean;
  muted?: boolean;
}) {
  return (
    <div className={`flex flex-wrap justify-between gap-x-4 ${muted ? "text-muted" : ""}`}>
      <dt>{label}</dt>
      <dd className={`m-0 ${strong ? "text-xl font-extrabold" : "font-bold"}`}>{value}</dd>
    </div>
  );
}

function CartLineRow({ line, check }: { line: CartLine; check: LineCheck }) {
  const qtyId = `qty-${line.mode}-${line.variantId}`;
  return (
    <li className="flex flex-col gap-3 border-t-2 border-dashed border-line py-4 last:border-b-2">
      <div className="flex items-start gap-4">
        <div className="flex size-20 flex-none items-center justify-center overflow-hidden rounded-2xl border-[3px] border-border bg-capybara sm:size-24">
          {line.imageUrl ? (
            <ProductImage src={line.imageUrl} alt="" className="size-full object-cover" />
          ) : (
            <span className="text-xs font-bold text-bark">No photo</span>
          )}
        </div>
        <div className="flex min-w-0 flex-1 flex-col gap-1">
          <Link
            href={`/products/${line.productSlug}`}
            className={`text-lg font-extrabold break-words text-fg ${focusRing}`}
          >
            {line.productName}
          </Link>
          <span className="text-[15px] text-muted">{line.scale} scale</span>
          <div className="mt-1 flex flex-wrap items-center gap-2">
            <label htmlFor={qtyId} className="text-sm font-bold">
              Qty
            </label>
            <select
              id={qtyId}
              value={line.quantity}
              onChange={(e) => updateLine(line, { quantity: Number(e.target.value) })}
              className={`min-h-11 rounded-xl border-2 border-border bg-card px-2 font-bold ${focusRing}`}
            >
              {Array.from({ length: MAX_QUANTITY }, (_, i) => i + 1).map((n) => (
                <option key={n} value={n}>
                  {n}
                </option>
              ))}
            </select>
            <button
              type="button"
              onClick={() => removeLine(line)}
              aria-label={`Remove ${line.productName}, ${line.scale}`}
              className={`min-h-11 px-3 text-sm font-bold underline ${focusRing}`}
            >
              Remove
            </button>
          </div>
        </div>
        <span className="flex-none text-lg font-extrabold">
          {formatUsd(line.priceCents * line.quantity)}
        </span>
      </div>
      <LineNote line={line} check={check} />
    </li>
  );
}

/** The role="status" wrapper is always rendered so screen readers announce notes when they appear. */
function LineNote({ line, check }: { line: CartLine; check: LineCheck }) {
  return (
    <div role="status">
      {check.kind === "changed" && (
        <Notice tone="error">
          <p className="m-0 text-[15px] font-bold">
            The price changed from {formatUsd(line.priceCents)} to {formatUsd(check.priceCents)}{" "}
            each.
          </p>
          <button
            type="button"
            onClick={() => updateLine(line, { priceCents: check.priceCents })}
            className={`${secondaryButton} mt-2 text-[15px]`}
          >
            Accept new price
          </button>
        </Notice>
      )}
      {check.kind === "unavailable" && (
        <Notice tone="error">
          <p className="m-0 text-[15px] font-bold">
            This option is no longer available as{" "}
            {line.mode === "mto" ? "made to order" : "ready to ship"}. Remove it to continue.
          </p>
        </Notice>
      )}
      {check.kind === "unknown" && (
        <p className="m-0 text-sm text-muted">
          Could not check the latest price. The shop confirms the final amount.
        </p>
      )}
    </div>
  );
}
