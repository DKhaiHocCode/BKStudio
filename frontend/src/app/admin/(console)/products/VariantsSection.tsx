"use client";

import { useState } from "react";
import { formatUsd } from "@/lib/money";
import { panelClass, secondaryButtonClass } from "../../ui";
import type { Variant } from "./types";
import { VariantForm } from "./VariantForm";

export function VariantsSection({
  productId,
  variants,
  onChange,
  onReload,
}: {
  productId: number;
  variants: Variant[];
  onChange: (fn: (variants: Variant[]) => Variant[]) => void;
  onReload: () => void;
}) {
  // "new" = the add form, a number = the variant being edited.
  const [open, setOpen] = useState<"new" | number | null>(null);

  return (
    <section aria-labelledby="variants-heading" className={`${panelClass} flex flex-col gap-4`}>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 id="variants-heading" className="text-xl font-extrabold">
          Variants
        </h2>
        {open !== "new" && (
          <button type="button" onClick={() => setOpen("new")} className={secondaryButtonClass}>
            Add variant
          </button>
        )}
      </div>
      {open === "new" && (
        <VariantForm
          productId={productId}
          onCancel={() => setOpen(null)}
          onReload={onReload}
          onSaved={(saved) => {
            onChange((list) => [...list, saved]);
            setOpen(null);
          }}
        />
      )}
      {variants.length === 0 && open !== "new" && (
        <p>No variants yet. A product needs an active variant to appear in the shop.</p>
      )}
      <ul className="flex flex-col gap-3">
        {variants.map((v) => (
          <li key={v.id} className="flex flex-col gap-3">
            {open === v.id ? (
              <VariantForm
                // New version after a save: remount so the inputs show what the server stored.
                key={v.version}
                productId={productId}
                variant={v}
                onCancel={() => setOpen(null)}
                onReload={onReload}
                onSaved={(saved) => {
                  onChange((list) => list.map((x) => (x.id === saved.id ? saved : x)));
                  setOpen(null);
                }}
              />
            ) : (
              <div className="flex flex-wrap items-center justify-between gap-3 rounded-[14px] border-[3px] border-[#151515] p-3">
                <span>
                  <strong>{v.scale}</strong> · {formatUsd(v.priceCents)} · stock {v.stockQty}
                  {v.madeToOrderEnabled && ` · made to order (quota ${v.madeToOrderQuota ?? 0})`}
                  {!v.active && (
                    <span className="ml-2 rounded-full border-2 border-[#151515] bg-[#e4dfd6] px-2 py-0.5 text-xs font-bold">
                      INACTIVE
                    </span>
                  )}
                </span>
                <button
                  type="button"
                  onClick={() => setOpen(v.id)}
                  aria-label={`Edit variant ${v.scale}`}
                  className={secondaryButtonClass}
                >
                  Edit
                </button>
              </div>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}
