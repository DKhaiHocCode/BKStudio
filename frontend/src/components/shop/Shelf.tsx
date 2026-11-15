import Link from "next/link";
import { chip } from "@/components/ui";
import { fetchProducts, fetchShelfPhotos } from "@/lib/catalog";
import { ShelfFilter } from "./ShelfFilter";

/** Uncached request-time data: render inside <Suspense>. Shows the first 50 products (API max). */
export async function Shelf({
  searchParams,
}: {
  searchParams: Promise<{ series?: string | string[] }>;
}) {
  const { series: rawSeries } = await searchParams;
  const series = Array.isArray(rawSeries) ? rawSeries[0] : rawSeries;
  const { items } = await fetchProducts({ series });
  const seriesNames = [...new Set(items.map((p) => p.series).filter((s): s is string => !!s))];
  // The list API has only the cover (no srcset, no second photo), so the hover view comes from
  // each product's detail (cached for minutes; this list call stays uncached so stock is live).
  // A failed call just means that tile has no second view.
  const photos = await Promise.all(items.map((p) => fetchShelfPhotos(p.slug).catch(() => [])));
  const products = items.map((p, i) => ({ ...p, photos: photos[i] }));

  return (
    <>
      {series ? (
        <p className="m-0 mb-4 flex flex-wrap items-center gap-3 font-bold">
          Series: {series}
          <Link href="/#shop" scroll={false} className={chip}>
            Show all series
          </Link>
        </p>
      ) : (
        seriesNames.length > 1 && (
          <nav
            aria-label="Series"
            className="-mx-1 mb-3 flex gap-2 overflow-x-auto p-1 sm:flex-wrap"
          >
            {seriesNames.map((name) => (
              <Link
                key={name}
                href={`/?series=${encodeURIComponent(name)}#shop`}
                className={`flex-none ${chip}`}
              >
                {name}
              </Link>
            ))}
          </nav>
        )
      )}
      <ShelfFilter items={products} />
    </>
  );
}

export function ShelfSkeleton() {
  return (
    <div
      aria-hidden="true"
      className="mt-8 grid grid-cols-2 gap-x-4 gap-y-10 sm:mt-12 sm:gap-x-8 md:grid-cols-3 lg:grid-cols-4"
    >
      {[0, 1, 2, 3].map((i) => (
        <div
          key={i}
          className="aspect-[4/5] animate-pulse rounded-card border-[3px] border-border bg-disabled motion-reduce:animate-none"
        />
      ))}
    </div>
  );
}
