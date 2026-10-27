import Link from "next/link";
import { chip } from "@/components/ui";
import { fetchProducts } from "@/lib/catalog";
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

  return (
    <>
      {series ? (
        <p className="flex flex-wrap items-center gap-3 font-bold">
          Series: {series}
          <Link href="/#shop" scroll={false} className={chip}>
            Show all series
          </Link>
        </p>
      ) : (
        seriesNames.length > 1 && (
          <nav aria-label="Series" className="flex gap-2 overflow-x-auto p-1 sm:flex-wrap">
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
      <ShelfFilter items={items} />
    </>
  );
}

export function ShelfSkeleton() {
  return (
    <div aria-hidden="true" className="grid grid-cols-2 gap-3.5 sm:gap-6 lg:grid-cols-4">
      {[0, 1, 2, 3].map((i) => (
        <div
          key={i}
          className="aspect-[3/4] animate-pulse rounded-card border-[3px] border-border bg-disabled motion-reduce:animate-none"
        />
      ))}
    </div>
  );
}
