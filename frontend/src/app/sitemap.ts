import type { MetadataRoute } from "next";
import { connection } from "next/server";
import { fetchAllProducts } from "@/lib/catalog";
import { SITE_URL } from "@/lib/site";

// The list API never returns UNLISTED products, so they cannot leak in here.
export default async function sitemap(): Promise<MetadataRoute.Sitemap> {
  await connection(); // always fresh: do not bake the catalog into the build
  const products = await fetchAllProducts();
  return [{ url: SITE_URL }, ...products.map((p) => ({ url: `${SITE_URL}/products/${p.slug}` }))];
}
