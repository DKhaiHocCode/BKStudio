"use client";

import { useState } from "react";
import { chip, chipActive } from "@/components/ui";
import { ProductCard, type ShelfProduct } from "./ProductCard";

type Filter = "all" | "stock" | "mto";

const FILTERS: { id: Filter; label: string }[] = [
  { id: "all", label: "All" },
  { id: "stock", label: "In stock" },
  { id: "mto", label: "Made to order" },
];

/** Filters the already-fetched page in the browser; the data itself comes from the server. */
export function ShelfFilter({ items }: { items: ShelfProduct[] }) {
  const [filter, setFilter] = useState<Filter>("all");
  const shown = items.filter(
    (p) => filter === "all" || (filter === "stock" ? p.inStock : p.madeToOrder),
  );

  return (
    <>
      <div className="flex flex-col gap-3 border-y-2 border-line py-3 sm:flex-row sm:items-center sm:justify-between">
        <p className="m-0 text-sm font-bold tracking-[0.06em] text-subtle uppercase" role="status">
          {shown.length} {shown.length === 1 ? "figure" : "figures"}
        </p>
        <div
          role="group"
          aria-label="Filter"
          className="-mx-1 flex gap-2 overflow-x-auto p-1 sm:flex-wrap"
        >
          {FILTERS.map((f) => (
            <button
              key={f.id}
              type="button"
              aria-pressed={filter === f.id}
              onClick={() => setFilter(f.id)}
              className={`flex-none text-sm sm:text-base ${filter === f.id ? chipActive : chip}`}
            >
              {f.label}
            </button>
          ))}
        </div>
      </div>
      {shown.length === 0 ? (
        <p className="m-0 mt-8 rounded-card border-[3px] border-dashed border-line p-8 text-center font-bold text-muted">
          {items.length === 0
            ? "The shelf is empty right now. New figures are on the way."
            : "Nothing matches this filter."}
        </p>
      ) : (
        <ul className="m-0 mt-8 grid list-none grid-cols-2 gap-x-4 gap-y-10 p-0 sm:mt-12 sm:gap-x-8 sm:gap-y-14 md:grid-cols-3 lg:grid-cols-4">
          {shown.map((p) => (
            <ProductCard key={p.slug} product={p} />
          ))}
        </ul>
      )}
    </>
  );
}
