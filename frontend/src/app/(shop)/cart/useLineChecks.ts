import { useEffect, useRef, useState } from "react";
import { ApiError, apiFetch } from "@/lib/api";
import type { CartLine } from "@/lib/cart";
import type { ProductDetail } from "@/lib/catalog";

/** What the live catalog says about one cart line. Indicative only: the server decides at checkout. */
export type LineCheck =
  | { kind: "pending" }
  | { kind: "unknown" } // refresh failed (network / server error): do not block
  | { kind: "ok" }
  | { kind: "changed"; priceCents: number }
  | { kind: "unavailable" };

type Fetched = ProductDetail | "missing" | "failed";

function checkLine(line: CartLine, fetched: Fetched | undefined): LineCheck {
  if (fetched === undefined) return { kind: "pending" };
  if (fetched === "failed") return { kind: "unknown" };
  if (fetched === "missing") return { kind: "unavailable" };
  const variant = fetched.variants.find((v) => v.id === line.variantId);
  const offered =
    line.mode === "stock" ? variant?.inStock.available : variant?.madeToOrder.available;
  if (!variant || !offered) return { kind: "unavailable" };
  if (variant.priceCents !== line.priceCents)
    return { kind: "changed", priceCents: variant.priceCents };
  return { kind: "ok" };
}

/** Fetches GET /api/products/{slug} once per distinct slug while the cart page is open. */
export function useLineChecks(lines: readonly CartLine[]): (line: CartLine) => LineCheck {
  const [products, setProducts] = useState<Record<string, Fetched>>({});
  const requested = useRef(new Set<string>());
  const slugs = [...new Set(lines.map((l) => l.productSlug))].join(",");

  useEffect(() => {
    for (const slug of slugs ? slugs.split(",") : []) {
      if (requested.current.has(slug)) continue;
      requested.current.add(slug);
      apiFetch<ProductDetail>(`/api/products/${slug}`)
        .then((product): Fetched => product)
        .catch((e: unknown): Fetched =>
          e instanceof ApiError && e.status === 404 ? "missing" : "failed",
        )
        .then((result) => setProducts((prev) => ({ ...prev, [slug]: result })));
    }
  }, [slugs]);

  return (line) => checkLine(line, products[line.productSlug]);
}
