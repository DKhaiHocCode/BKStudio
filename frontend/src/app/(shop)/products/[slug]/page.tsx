import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { Suspense } from "react";
import { BuyBox } from "@/components/shop/BuyBox";
import { Gallery } from "@/components/shop/Gallery";
import { focusRing } from "@/components/ui";
import { fetchProduct } from "@/lib/catalog";
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

export default function ProductPage({ params }: PageProps<"/products/[slug]">) {
  return (
    <main className="flex-1">
      <Suspense fallback={<p className="mx-auto max-w-[1200px] px-4 py-10 md:px-6">Loading…</p>}>
        <ProductContent params={params} />
      </Suspense>
    </main>
  );
}

async function ProductContent({ params }: { params: PageProps<"/products/[slug]">["params"] }) {
  const { slug } = await params;
  const product = await fetchProduct(slug);
  if (!product) notFound();

  return (
    <>
      <nav
        aria-label="Breadcrumb"
        className="mx-auto max-w-[1200px] px-4 pt-5 text-[15px] font-bold text-muted md:px-6"
      >
        <Link href="/#shop" className={`inline-flex min-h-11 items-center text-fg ${focusRing}`}>
          Shop
        </Link>
        {product.series && (
          <>
            <span aria-hidden="true"> / </span>
            <Link
              href={`/?series=${encodeURIComponent(product.series)}#shop`}
              className={`inline-flex min-h-11 items-center text-fg ${focusRing}`}
            >
              {product.series}
            </Link>
          </>
        )}
        <span aria-hidden="true"> / </span>
        <span aria-current="page">{product.name}</span>
      </nav>

      <div className="mx-auto flex max-w-[1200px] flex-wrap items-start gap-8 px-4 pt-3 pb-12 md:gap-12 md:px-6">
        <div className="min-w-0 flex-[1_1_320px] md:flex-[1_1_480px]">
          <Gallery images={product.images} name={product.name} />
        </div>

        <div className="flex min-w-0 flex-[1_1_320px] flex-col gap-[22px] md:flex-[1_1_420px]">
          <div className="flex flex-col gap-2">
            {product.series && (
              <span className="text-sm font-bold tracking-[0.06em] text-subtle uppercase">
                {product.series}
              </span>
            )}
            <h1 className="text-[40px] leading-[1.05] font-extrabold md:text-[52px]">
              {product.name}
            </h1>
          </div>

          <BuyBox
            variants={product.variants}
            product={{
              slug: product.slug,
              name: product.name,
              imageUrl: product.images[0]?.srcset["400"] ?? product.images[0]?.url,
            }}
          />

          <div className="flex items-start gap-3 rounded-[18px] border-[3px] border-border bg-card px-[18px] py-4">
            <svg
              width="22"
              height="22"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2.4"
              strokeLinecap="round"
              strokeLinejoin="round"
              aria-hidden="true"
              className="mt-0.5 flex-none"
            >
              <circle cx="12" cy="12" r="9" />
              <path d="M12 8v5" />
              <path d="M12 16.5v.01" />
            </svg>
            <p className="m-0 text-[15px] leading-relaxed">
              The deposit is <strong>non-refundable</strong> if you cancel after your slot is
              confirmed. The balance + shipping is due within 30 days of us sending finished photos.{" "}
              <a href="#" className={`underline ${focusRing}`}>
                Read the full policy
              </a>
            </p>
          </div>

          <div className="flex flex-col border-t-[3px] border-border">
            <details open className="border-b-2 border-border py-2">
              <summary
                className={`flex min-h-11 cursor-pointer items-center text-lg font-bold ${focusRing}`}
              >
                About this figure
              </summary>
              <p className="mt-1 mb-3 whitespace-pre-line text-base leading-relaxed text-body">
                {product.description || "Details for this figure are coming soon."}
              </p>
            </details>
          </div>
        </div>
      </div>
    </>
  );
}
