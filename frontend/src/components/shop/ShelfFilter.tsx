"use client";

import { useState } from "react";
import { chip, chipActive } from "@/components/ui";
import type { ProductListItem } from "@/lib/catalog";
import { ProductCard } from "./ProductCard";

type Filter = "all" | "stock" | "mto";

const FILTERS: { id: Filter; label: string }[] = [
  { id: "all", label: "All" },
  { id: "stock", label: "In stock" },
  { id: "mto", label: "Made to order" },
];

/** Filters the already-fetched page in the browser; the data itself comes from the server. */
export function ShelfFilter({ items }: { items: ProductListItem[] }) {
  const [filter, setFilter] = useState<Filter>("all");
  const shown = items.filter(
    (p) => filter === "all" || (filter === "stock" ? p.inStock : p.madeToOrder),
  );

  return (
    <>
      <div role="group" aria-label="Filter" className="flex gap-2 overflow-x-auto p-1 sm:flex-wrap">
        {FILTERS.map((f) => (
          <button
            key={f.id}
            type="button"
            aria-pressed={filter === f.id}
            onClick={() => setFilter(f.id)}
            className={`flex-none ${filter === f.id ? chipActive : chip}`}
          >
            {f.label}
          </button>
        ))}
      </div>
      {shown.length === 0 ? (
        <p className="rounded-card border-[3px] border-dashed border-line p-8 text-center font-bold text-muted">
          {items.length === 0
            ? "The shelf is empty right now. New figures are on the way."
            : "Nothing matches this filter."}
        </p>
      ) : (
        <ul className="m-0 grid list-none grid-cols-2 gap-3.5 p-0 sm:gap-6 lg:grid-cols-4">
          {shown.map((p) => (
            <li key={p.slug} className="flex min-w-0 flex-col [&>a]:flex-1">
              <ProductCard product={p} />
            </li>
          ))}
        </ul>
      )}
    </>
  );
}
