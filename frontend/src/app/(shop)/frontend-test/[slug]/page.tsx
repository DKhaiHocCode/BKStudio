import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { Suspense, type ReactNode } from "react";
import { ProductImage } from "@/components/shop/ProductImage";
import { badge, focusRing, panel } from "@/components/ui";
import { formatUsd } from "@/lib/money";
import { STATUS_LABEL, STATUS_TINT, srcSetOf } from "../_components/data";
import { PRODUCTS, type DemoStatus } from "../_components/products.generated";
import { AddToCart } from "./_components/AddToCart";
import { PhotoGallery } from "./_components/PhotoGallery";

// PROTOTYPE (design experiment, not a tracked task): static data, no backend calls, not in any nav.
type Props = PageProps<"/frontend-test/[slug]">;

// Every product is known at build time, so the pages are prerendered (cacheComponents needs a
// non-empty list). An unknown slug still renders on demand and ends in notFound().
export function generateStaticParams() {
  return PRODUCTS.map((p) => ({ slug: p.slug }));
}

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const { slug } = await params;
  const product = PRODUCTS.find((p) => p.slug === slug);
  return {
    title: product ? `${product.name} (layout test)` : "Figure not found",
    robots: { index: false, follow: false },
  };
}

// Static prototype copy: the real page reads stock and slots from the backend.
const NOTE: Record<DemoStatus, string> = {
  IN_STOCK: "Ready to ship: packed within 3 working days.",
  MADE_TO_ORDER:
    "Painted after you order. A 50% deposit holds your slot: December has 6 of 8 slots left.",
  COMMISSION: "Starting price. Tell us your character and we send a quote before any payment.",
};

const section = "mx-auto w-full max-w-[1200px] px-4 md:px-6";
const pager = `inline-flex min-h-11 min-w-0 items-center gap-2 font-bold text-fg no-underline hover:underline ${focusRing}`;

function Chevron({ className }: { className: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      className={`flex-none ${className}`}
      fill="none"
      stroke="currentColor"
      strokeWidth="3"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d="M9 5l7 7-7 7" />
    </svg>
  );
}

function Fold({ title, open, children }: { title: string; open?: boolean; children: ReactNode }) {
  return (
    <details open={open} className="group border-b-2 border-line">
      <summary
        className={`flex min-h-14 cursor-pointer list-none items-center justify-between gap-4 text-xl font-extrabold [&::-webkit-details-marker]:hidden ${focusRing}`}
      >
        {title}
        <Chevron className="size-5 rotate-90 transition-transform group-open:-rotate-90 motion-reduce:transition-none" />
      </summary>
      <div className="max-w-[640px] pb-6 leading-relaxed text-body">{children}</div>
    </details>
  );
}

// params is URL data: reading it inside Suspense keeps client navigations instant (same pattern
// as /products/[slug]). The known slugs are still prerendered in full.
export default function FrontendTestProductPage({ params }: Props) {
  return (
    <main id="main" className="flex flex-1 flex-col pb-20 md:pb-28">
      <Suspense fallback={<p className={`${section} m-0 py-10`}>Loading…</p>}>
        <ProductContent params={params} />
      </Suspense>
    </main>
  );
}

async function ProductContent({ params }: { params: Props["params"] }) {
  const { slug } = await params;
  const at = PRODUCTS.findIndex((p) => p.slug === slug);
  const product = PRODUCTS[at];
  if (!product) notFound();

  const others = PRODUCTS.filter((p) => p.slug !== slug);
  const prev = PRODUCTS[(at - 1 + PRODUCTS.length) % PRODUCTS.length];
  const next = PRODUCTS[(at + 1) % PRODUCTS.length];

  return (
    <>
      <nav aria-label="Breadcrumb" className={`${section} pt-4 md:pt-6`}>
        <ol className="m-0 flex list-none items-center gap-1.5 p-0 text-sm font-bold">
          <li>
            <Link
              href="/frontend-test"
              className={`inline-flex min-h-11 items-center text-fg underline ${focusRing}`}
            >
              Shelf
            </Link>
          </li>
          <li aria-hidden="true">
            <Chevron className="size-4 text-subtle" />
          </li>
          <li aria-current="page" className="min-w-0 truncate text-muted">
            {product.name}
          </li>
        </ol>
      </nav>

      <div
        className={`${section} mt-2 grid grid-cols-1 gap-x-10 gap-y-8 lg:grid-cols-[minmax(0,1fr)_360px] xl:gap-x-14`}
      >
        {/* First in the DOM (name and price before the photos on a phone), right column on desktop. */}
        <div className="max-lg:order-2 lg:col-start-2 lg:row-span-2 lg:row-start-1">
          <div className={`${panel} flex flex-col gap-4 lg:sticky lg:top-6`}>
            <p className="m-0 flex flex-wrap items-center gap-2">
              <span className={`${badge} ${STATUS_TINT[product.status]} text-ink`}>
                {STATUS_LABEL[product.status]}
              </span>
              {product.isNew && <span className={`${badge} bg-citrus text-ink`}>New</span>}
            </p>
            <div>
              <h1 className="m-0 text-[34px] leading-[1.05] font-extrabold tracking-tight md:text-[44px]">
                {product.name}
              </h1>
              <p className="m-0 mt-1.5 text-lg text-muted">{product.descriptor}</p>
            </div>
            <p className="m-0 text-[32px] leading-none font-extrabold">
              {product.status === "COMMISSION" && (
                <span className="text-base font-medium text-muted">From </span>
              )}
              {formatUsd(product.priceCents)}{" "}
              <span className="text-base font-bold text-subtle">USD</span>
            </p>
            <AddToCart />
            <p className="m-0 border-t-2 border-dashed border-line pt-4 text-[15px] leading-relaxed text-body">
              {NOTE[product.status]}
            </p>
          </div>
        </div>

        <section
          aria-label="Photos"
          className="min-w-0 max-lg:order-1 lg:col-start-1 lg:row-start-1"
        >
          <PhotoGallery photos={product.photos} name={product.name} />
        </section>

        <section aria-label="Product information" className="min-w-0 max-lg:order-3 lg:col-start-1">
          <div className="border-t-2 border-line">
            <Fold title="Details" open>
              <p className="m-0">{product.description}</p>
              <dl className="m-0 mt-4 grid grid-cols-[auto_1fr] gap-x-6 gap-y-1.5">
                <dt className="font-bold text-fg">Height</dt>
                <dd className="m-0">{product.heightCm} cm</dd>
                <dt className="font-bold text-fg">Material</dt>
                <dd className="m-0">3D printed resin</dd>
                <dt className="font-bold text-fg">Finish</dt>
                <dd className="m-0">Hand-painted acrylics, matte clear coat</dd>
              </dl>
            </Fold>
            <Fold title="Shipping & payment">
              <p className="m-0">
                We ship to Singapore and Australia in a foam-lined box with tracking. Prices are in
                USD. This site takes no card details: after you order we email a payment link.
                Made-to-order figures need a 50% deposit, the rest is due before shipping.
              </p>
            </Fold>
            <Fold title="How it's made">
              <p className="m-0">
                Printed in resin, supports cut and seams sanded smooth. A grey primer coat shows
                every flaw before colour goes on. Then base colours, shading and details, all by
                brush.
              </p>
            </Fold>
          </div>
        </section>
      </div>

      <nav
        aria-label="Previous and next figure"
        className={`${section} mt-12 flex items-center justify-between gap-4 md:mt-16`}
      >
        <Link href={`/frontend-test/${prev.slug}`} className={pager}>
          <Chevron className="size-5 rotate-180" />
          <span className="truncate">{prev.name}</span>
        </Link>
        <Link href={`/frontend-test/${next.slug}`} className={pager}>
          <span className="truncate">{next.name}</span>
          <Chevron className="size-5" />
        </Link>
      </nav>

      <section className={`${section} mt-10 md:mt-14`}>
        <h2 className="m-0 text-3xl font-extrabold md:text-[40px]">More from the shelf</h2>
        <ul className="m-0 mt-6 grid list-none grid-cols-2 gap-x-4 gap-y-8 p-0 sm:grid-cols-3 sm:gap-x-6 md:mt-8 lg:grid-cols-5">
          {others.map((o) => {
            const cover = o.photos[o.cover];
            return (
              <li key={o.slug} className="group relative flex min-w-0 flex-col gap-2.5">
                <div className="relative aspect-[4/5] overflow-hidden rounded-[18px] border-[3px] border-border bg-tint-neutral shadow-sticker-sm group-has-[a:focus-visible]:outline-[3px] group-has-[a:focus-visible]:outline-offset-2 group-has-[a:focus-visible]:outline-focus">
                  <ProductImage
                    src={cover.src700}
                    srcSet={srcSetOf(cover)}
                    sizes="(min-width: 1024px) 215px, (min-width: 640px) 31vw, 46vw"
                    alt=""
                    width={700}
                    height={875}
                    loading="lazy"
                    style={{ objectPosition: cover.objectPosition }}
                    className="absolute inset-0 size-full object-cover"
                  />
                </div>
                <div>
                  <h3 className="m-0 text-[17px] leading-snug font-extrabold">
                    <Link
                      href={`/frontend-test/${o.slug}`}
                      className="text-fg no-underline after:absolute after:inset-0 focus-visible:outline-none"
                    >
                      {o.name}
                    </Link>
                  </h3>
                  <p className="m-0 text-sm font-bold text-muted">{formatUsd(o.priceCents)}</p>
                </div>
              </li>
            );
          })}
        </ul>
      </section>
    </>
  );
}
