"use client";

import { useState } from "react";
import { chip, chipActive, focusRing, primaryButton } from "@/components/ui";
import type { Variant } from "@/lib/catalog";
import { formatUsd } from "@/lib/money";

type Mode = "stock" | "mto";

/** 50% of the price in cents, rounded half up. Display estimate only; the server decides real amounts. */
const depositCents = (priceCents: number) => Math.floor((priceCents + 1) / 2);

const option =
  "flex items-start gap-3.5 rounded-[18px] border-[3px] border-border px-[18px] py-4 has-[:focus-visible]:outline-[3px] has-[:focus-visible]:outline-offset-2 has-[:focus-visible]:outline-focus";

export function BuyBox({ variants }: { variants: Variant[] }) {
  const [variantId, setVariantId] = useState(variants[0]?.id);
  const [pickedMode, setPickedMode] = useState<Mode | null>(null);
  const [qty, setQty] = useState(1);

  const variant = variants.find((v) => v.id === variantId) ?? variants[0];
  if (!variant) {
    return <p className="font-bold text-muted">This figure has no purchasable options yet.</p>;
  }

  const stockOk = variant.inStock.available;
  const mtoOk = variant.madeToOrder.available;
  // Fall back to whatever is available when the picked mode is not offered for this scale.
  const mode: Mode | null =
    pickedMode === "stock" && stockOk
      ? "stock"
      : pickedMode === "mto" && mtoOk
        ? "mto"
        : stockOk
          ? "stock"
          : mtoOk
            ? "mto"
            : null;
  const days = variant.inStock.shipsWithinDays;
  const month = variant.madeToOrder.estimatedStartMonth;

  return (
    <div className="flex flex-col gap-[22px]">
      <p className="m-0 text-3xl font-bold" aria-live="polite">
        {formatUsd(variant.priceCents)}
      </p>

      {variants.length > 1 && (
        <fieldset className="m-0 flex min-w-0 flex-col gap-2.5 border-0 p-0">
          <legend className="mb-2.5 font-bold">Scale</legend>
          <div className="flex flex-wrap gap-2.5 p-1">
            {variants.map((v) => (
              <button
                key={v.id}
                type="button"
                aria-pressed={v.id === variant.id}
                onClick={() => setVariantId(v.id)}
                className={v.id === variant.id ? chipActive : chip}
              >
                {v.scale}
              </button>
            ))}
          </div>
        </fieldset>
      )}

      <fieldset className="m-0 flex min-w-0 flex-col gap-3 border-0 p-0">
        <legend className="mb-2.5 font-bold">How do you want it?</legend>
        <label
          className={`${option} ${
            stockOk
              ? `cursor-pointer ${mode === "stock" ? "bg-tint-stock text-ink shadow-sticker" : "bg-card"}`
              : "cursor-not-allowed bg-disabled text-muted"
          }`}
        >
          <input
            type="radio"
            name="fulfilment"
            disabled={!stockOk}
            checked={mode === "stock"}
            onChange={() => setPickedMode("stock")}
            className="mt-1 size-[22px] accent-red"
          />
          <span className="flex flex-col gap-1">
            <span className="text-[17px] font-bold">
              Ready to ship
              {!stockOk && (
                <span className="ml-1.5 rounded-full border-2 border-border bg-card px-2 py-0.5 text-[13px]">
                  Sold out
                </span>
              )}
            </span>
            <span className="text-[15px]">
              Pay 100% now{stockOk && days ? `, ships within ~${days} days` : ""}.
            </span>
          </span>
        </label>
        <label
          className={`${option} ${
            mtoOk
              ? `cursor-pointer ${mode === "mto" ? "bg-tint-mto text-ink shadow-sticker" : "bg-card"}`
              : "cursor-not-allowed bg-disabled text-muted"
          }`}
        >
          <input
            type="radio"
            name="fulfilment"
            disabled={!mtoOk}
            checked={mode === "mto"}
            onChange={() => setPickedMode("mto")}
            className="mt-1 size-[22px] accent-red"
          />
          <span className="flex flex-col gap-1">
            <span className="text-[17px] font-bold">
              Made for you
              {!mtoOk && (
                <span className="ml-1.5 rounded-full border-2 border-border bg-card px-2 py-0.5 text-[13px]">
                  Slots full
                </span>
              )}
            </span>
            <span className="text-[15px] leading-normal">
              Pay a 50% deposit (about {formatUsd(depositCents(variant.priceCents))}) now.{" "}
              {month
                ? `Estimated start: ${month}. We confirm the exact month when your deposit is in.`
                : "We confirm the start month when your deposit is in."}
            </span>
          </span>
        </label>
      </fieldset>

      <div className="flex flex-wrap items-center gap-3.5">
        <label htmlFor="qty" className="font-bold">
          Qty
        </label>
        <input
          id="qty"
          type="number"
          min={1}
          max={5}
          value={qty}
          onChange={(e) =>
            setQty(Math.min(5, Math.max(1, Math.trunc(Number(e.target.value)) || 1)))
          }
          className={`min-h-11 w-[72px] rounded-xl border-[3px] border-border bg-card p-3 font-bold ${focusRing}`}
        />
        {/* Cart arrives with T-030. */}
        <button type="button" disabled className={`${primaryButton} flex-[1_1_220px] text-lg`}>
          Coming soon
        </button>
      </div>
    </div>
  );
}
