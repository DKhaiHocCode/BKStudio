import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { Suspense, type ReactNode } from "react";
import { BuyBox } from "@/components/shop/BuyBox";
import { Gallery } from "@/components/shop/Gallery";
import { ProductCard } from "@/components/shop/ProductCard";
import { RefundNotice } from "@/components/shop/RefundNotice";
import { badge, focusRing, panel } from "@/components/ui";
import { fetchProduct, fetchProducts } from "@/lib/catalog";
import { centsToDollars, formatUsd } from "@/lib/money";

const DESCRIPTION_MAX = 160;

function truncate(text: string, max: number): string {
  const flat = text.replace(/\s+/g, " ").trim();
  return flat.length <= max ? flat : `${flat.slice(0, max - 1).trimEnd()}…`;
}

export async function generateMetadata({
  params,
}: PageProps<"/products/[slug]">): Promise<Metadata> {
  const { slug } = await params;
  const product = await fetchProduct(slug);
  if (!product) return { title: "Figure not found", robots: { index: false } };

  const body = truncate(
    product.description || `${product.name}, a hand-painted 3D printed figure by BKStudio.`,
    DESCRIPTION_MAX,
  );
  const image = product.images[0];
  const imageUrl = image ? (image.srcset["1000"] ?? image.url) : undefined;
  const fromPrice = Math.min(...product.variants.map((v) => v.priceCents));
  const description = Number.isFinite(fromPrice) ? `From ${formatUsd(fromPrice)} · ${body}` : body;
  const title = product.name;

  return {
    title,
    description,
    alternates: { canonical: `/products/${product.slug}` },
    // Direct-link-only (UNLISTED / commission) products must stay out of search results.
    robots: product.unlisted ? { index: false, follow: false } : undefined,
    referrer: product.unlisted ? "no-referrer" : undefined,
    openGraph: {
      type: "website",
      title,
      description,
      images:
        image && imageUrl
          ? [
              {
                url: imageUrl,
                width: image.width,
                height: image.height,
                alt: image.alt || product.name,
              },
            ]
          : undefined,
    },
    twitter: { card: "summary_large_image", title, description },
    other: Number.isFinite(fromPrice)
      ? { "product:price:amount": centsToDollars(fromPrice), "product:price:currency": "USD" }
      : undefined,
  };
}

const section = "mx-auto w-full max-w-[1200px] px-4 md:px-6";
const crumb = `inline-flex min-h-11 items-center text-fg underline ${focusRing}`;
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

export default function ProductPage({ params }: PageProps<"/products/[slug]">) {
  return (
    <main id="main" className="flex flex-1 flex-col pb-16 md:pb-24">
      <Suspense fallback={<p className={`${section} m-0 py-10`}>Loading…</p>}>
        <ProductContent params={params} />
      </Suspense>
    </main>
  );
}

async function ProductContent({ params }: { params: PageProps<"/products/[slug]">["params"] }) {
  const { slug } = await params;
  const product = await fetchProduct(slug);
  if (!product) notFound();

  const inStock = product.variants.some((v) => v.inStock.available);
  const madeToOrder = product.variants.some((v) => v.madeToOrder.available);

  return (
    <>
      <nav aria-label="Breadcrumb" className={`${section} pt-4 md:pt-6`}>
        <ol className="m-0 flex list-none flex-wrap items-center gap-x-1.5 p-0 text-sm font-bold">
          <li>
            <Link href="/#shop" className={crumb}>
              Shop
            </Link>
          </li>
          {product.series && (
            <>
              <li aria-hidden="true">
                <Chevron className="size-4 text-subtle" />
              </li>
              <li>
                <Link
                  href={`/?series=${encodeURIComponent(product.series)}#shop`}
                  className={crumb}
                >
                  {product.series}
                </Link>
              </li>
            </>
          )}
          <li aria-hidden="true">
            <Chevron className="size-4 text-subtle" />
          </li>
          <li aria-current="page" className="min-w-0 truncate text-muted">
            {product.name}
          </li>
        </ol>
      </nav>

      {/* DOM order = phone order (photos, buy panel, details); the grid moves the panel right on desktop. */}
      <div
        className={`${section} mt-2 grid grid-cols-1 gap-x-10 gap-y-8 lg:grid-cols-[minmax(0,1fr)_400px] xl:gap-x-14`}
      >
        <section aria-label="Photos" className="min-w-0 lg:col-start-1 lg:row-start-1">
          <Gallery images={product.images} name={product.name} />
        </section>

        <div className="min-w-0 lg:col-start-2 lg:row-span-2 lg:row-start-1">
          {/* Sticky only where the whole panel fits (about 950px tall with two fulfilment options):
              on a shorter screen a stuck panel would hide "Add to cart". */}
          <div
            className={`${panel} flex flex-col gap-4 lg:[@media(min-height:1040px)]:sticky lg:[@media(min-height:1040px)]:top-6 lg:[@media(min-height:1040px)]:max-h-[calc(100dvh-3rem)] lg:[@media(min-height:1040px)]:overflow-y-auto`}
          >
            {(inStock || madeToOrder || product.unlisted) && (
              <p className="m-0 flex flex-wrap items-center gap-2">
                {inStock && <span className={`${badge} bg-tint-stock text-ink`}>In stock</span>}
                {madeToOrder && (
                  <span className={`${badge} bg-tint-mto text-ink`}>Made to order</span>
                )}
                {product.unlisted && (
                  <span className={`${badge} bg-tint-commission text-ink`}>Private listing</span>
                )}
              </p>
            )}
            <div>
              <h1 className="m-0 text-[34px] leading-[1.05] font-extrabold tracking-tight wrap-break-word md:text-[44px]">
                {product.name}
              </h1>
              {product.series && <p className="m-0 mt-1.5 text-lg text-muted">{product.series}</p>}
            </div>

            <BuyBox
              variants={product.variants}
              product={{
                slug: product.slug,
                name: product.name,
                imageUrl: product.images[0]?.srcset["400"] ?? product.images[0]?.url,
              }}
            />

            <RefundNotice />

            <p className="m-0 border-t-2 border-dashed border-line pt-4 text-[15px] leading-relaxed text-body">
              The deposit is <strong>non-refundable</strong> if you cancel after your slot is
              confirmed. The balance + shipping is due within 30 days after we send you the balance
              payment link.{" "}
              <Link href="/policy" className={`text-fg underline ${focusRing}`}>
                Read the full policy
              </Link>
            </p>
          </div>
        </div>

        <section aria-label="Product information" className="min-w-0 lg:col-start-1 lg:row-start-2">
          <div className="border-t-2 border-line">
            <Fold title="Details" open>
              <p className="m-0 whitespace-pre-line">
                {product.description || "Details for this figure are coming soon."}
              </p>
              <dl className="m-0 mt-4 grid grid-cols-[auto_1fr] gap-x-6 gap-y-1.5">
                {product.variants.length > 0 && (
                  <>
                    <dt className="font-bold text-fg">Scale</dt>
                    <dd className="m-0">{product.variants.map((v) => v.scale).join(", ")}</dd>
                  </>
                )}
                <dt className="font-bold text-fg">Material</dt>
                <dd className="m-0">3D printed resin</dd>
                <dt className="font-bold text-fg">Finish</dt>
                <dd className="m-0">Painted by hand</dd>
              </dl>
            </Fold>
            <Fold title="Shipping & payment">
              <p className="m-0">
                Prices are in USD. This site takes no card details: after you order we email a
                secure payment link. Made-to-order figures need a 50% deposit, the rest is due
                before shipping. Shipping is paid by the customer: we tell you the amount before you
                pay, and we email you when your order ships (the tracking number is on your order
                page).{" "}
                <Link href="/faq" className={`text-fg underline ${focusRing}`}>
                  Read the FAQ
                </Link>
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

      <Suspense fallback={null}>
        <MoreFromShelf slug={product.slug} />
      </Suspense>
    </>
  );
}

/** Pager + other figures. Optional: a failed list call hides it instead of breaking the page. */
async function MoreFromShelf({ slug }: { slug: string }) {
  const list = await fetchProducts().catch(() => null);
  if (!list) return null;
  const { items } = list;
  const others = items.filter((p) => p.slug !== slug).slice(0, 4);
  if (others.length === 0) return null;
  // UNLISTED products are not in the list (at = -1): no previous/next for them.
  const at = items.findIndex((p) => p.slug === slug);
  const prev = at < 0 ? undefined : items[(at - 1 + items.length) % items.length];
  const next = at < 0 ? undefined : items[(at + 1) % items.length];

  return (
    <>
      {prev && next && (
        <nav
          aria-label="Previous and next figure"
          className={`${section} mt-12 flex items-center justify-between gap-4 md:mt-16`}
        >
          <Link href={`/products/${prev.slug}`} className={pager}>
            <Chevron className="size-5 rotate-180" />
            <span className="truncate">{prev.name}</span>
          </Link>
          {/* Two figures on the shelf: previous and next are the same one. */}
          {next.slug !== prev.slug && (
            <Link href={`/products/${next.slug}`} className={pager}>
              <span className="truncate">{next.name}</span>
              <Chevron className="size-5" />
            </Link>
          )}
        </nav>
      )}
      <section className={`${section} mt-10 md:mt-14`}>
        <h2 className="m-0 text-3xl font-extrabold md:text-[40px]">More from the shelf</h2>
        <ul className="m-0 mt-6 grid list-none grid-cols-2 gap-x-4 gap-y-10 p-0 sm:gap-x-8 md:mt-8 md:grid-cols-3 lg:grid-cols-4">
          {others.map((p) => (
            <ProductCard key={p.slug} product={{ ...p, photos: [] }} />
          ))}
        </ul>
      </section>
    </>
  );
}
