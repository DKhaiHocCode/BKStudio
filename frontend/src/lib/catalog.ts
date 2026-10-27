import { cache } from "react";

/** Server-side client for the public catalog API (API_SPEC §2). Server components only. */

export interface CoverImage {
  url: string;
  alt: string | null;
  width: number;
  height: number;
}

export interface ProductListItem {
  slug: string;
  name: string;
  series: string | null;
  coverImage: CoverImage | null;
  fromPriceCents: number;
  inStock: boolean;
  madeToOrder: boolean;
}

export interface ProductPage {
  items: ProductListItem[];
  page: number;
  size: number;
  total: number;
}

export interface ProductImage extends CoverImage {
  srcset: Record<string, string>;
}

export interface Variant {
  id: number;
  scale: string;
  priceCents: number;
  inStock: { available: boolean; shipsWithinDays: number | null };
  madeToOrder: { available: boolean; estimatedStartMonth: string | null };
}

export interface ProductDetail {
  slug: string;
  name: string;
  series: string | null;
  description: string | null;
  unlisted: boolean;
  images: ProductImage[];
  variants: Variant[];
}

const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";

/** Returns null on 404; any other failure throws so the nearest error boundary shows it. */
async function getJson<T>(path: string): Promise<T | null> {
  const response = await fetch(`${BACKEND_URL}${path}`, {
    headers: { Accept: "application/json" },
    signal: AbortSignal.timeout(5000),
  });
  if (response.status === 404) return null;
  if (!response.ok)
    // Status only: the path may contain an UNLISTED slug, which is a secret.
    throw new Error(`Catalog request failed (${response.status})`);
  return (await response.json()) as T;
}

export async function fetchProducts(opts: { series?: string; page?: number; size?: number } = {}) {
  const query = new URLSearchParams({
    page: String(opts.page ?? 0),
    size: String(opts.size ?? 50),
  });
  if (opts.series) query.set("series", opts.series);
  const page = await getJson<ProductPage>(`/api/products?${query}`);
  if (!page) throw new Error("Catalog list returned 404");
  return page;
}

// cache(): generateMetadata and the page share one request per render.
const SLUG_PATTERN = /^[a-z0-9]+(-[a-z0-9]+)*$/;

export const fetchProduct = cache((slug: string) =>
  SLUG_PATTERN.test(slug) ? getJson<ProductDetail>(`/api/products/${slug}`) : Promise.resolve(null),
);

/** Every ACTIVE product, paging through the list (used by the sitemap). */
export async function fetchAllProducts(): Promise<ProductListItem[]> {
  const all: ProductListItem[] = [];
  for (let page = 0; ; page++) {
    const result = await fetchProducts({ page, size: 50 });
    all.push(...result.items);
    if (all.length >= result.total || result.items.length === 0) return all;
  }
}
