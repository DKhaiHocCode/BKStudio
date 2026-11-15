"use client";

import Link from "next/link";
import { useState } from "react";
import { badge, focusRing } from "@/components/ui";
import type { ProductImage as Img, ProductListItem } from "@/lib/catalog";
import { formatUsd } from "@/lib/money";
import { ProductImage, srcSetOf } from "./ProductImage";

/** A list item plus its first two detail photos (cover with srcset, second view); may be empty. */
export type ShelfProduct = ProductListItem & { photos: Img[] };

const fade = "absolute inset-0 size-full object-cover transition-opacity duration-200";
// Tile width per breakpoint (2 / 3 / 4 columns), so the browser picks the small file on most screens.
const SIZES = "(min-width: 1024px) 280px, (min-width: 768px) 31vw, 46vw";
// Second view on hover (mouse only) and on keyboard focus; touch uses the swap button.
const PEEK_HIDE =
  "[@media(hover:hover)]:group-hover:opacity-0 group-has-[a:focus-visible]:opacity-0";
const PEEK_SHOW =
  "[@media(hover:hover)]:group-hover:opacity-100 group-has-[a:focus-visible]:opacity-100";

export function ProductCard({ product }: { product: ShelfProduct }) {
  const [swapped, setSwapped] = useState(false);
  // The second photo is downloaded only after the first mouse hover, keyboard focus or swap tap,
  // and the cover stays visible until it has loaded.
  const [peek, setPeek] = useState(false);
  const [hoverLoaded, setHoverLoaded] = useState(false);
  const cover = product.photos[0] ?? product.coverImage;
  const coverSrcSet = product.photos[0] ? srcSetOf(product.photos[0].srcset) : undefined;
  const hover = product.photos[1];
  return (
    <li
      className="group relative flex min-w-0 flex-col gap-3 sm:gap-4"
      onPointerEnter={(e) => e.pointerType === "mouse" && setPeek(true)}
      onFocus={(e) => e.target.matches(":focus-visible") && setPeek(true)}
    >
      {/* No transform on this tile: it would trap the z-10 swap button under the stretched link. */}
      <div className="relative flex aspect-[4/5] items-center justify-center overflow-hidden rounded-[18px] border-[3px] border-border bg-tint-neutral shadow-sticker-sm group-has-[a:focus-visible]:outline-[3px] group-has-[a:focus-visible]:outline-offset-2 group-has-[a:focus-visible]:outline-focus sm:rounded-card sm:shadow-sticker">
        {cover ? (
          <ProductImage
            src={cover.url}
            srcSet={coverSrcSet}
            sizes={SIZES}
            alt={cover.alt || product.name}
            width={cover.width}
            height={cover.height}
            loading="lazy"
            className={`${fade} ${!hoverLoaded ? "" : swapped ? "opacity-0" : `opacity-100 ${PEEK_HIDE}`}`}
          />
        ) : (
          <span className="px-3 text-center text-sm font-bold text-ink">Photo coming soon</span>
        )}
        {hover && peek && (
          <ProductImage
            src={hover.url}
            srcSet={srcSetOf(hover.srcset)}
            sizes={SIZES}
            alt=""
            aria-hidden="true"
            width={hover.width}
            height={hover.height}
            onLoad={() => setHoverLoaded(true)}
            className={`${fade} ${swapped ? "opacity-100" : `opacity-0 ${PEEK_SHOW}`}`}
          />
        )}
        <span className="absolute top-2 left-2 flex flex-col items-start gap-1 sm:top-3 sm:left-3">
          {product.inStock && <span className={`${badge} bg-tint-stock text-ink`}>In stock</span>}
          {product.madeToOrder && (
            <span className={`${badge} bg-tint-mto text-ink`}>Made to order</span>
          )}
          {!product.inStock && !product.madeToOrder && (
            <span className={`${badge} bg-cream text-ink`}>Sold out</span>
          )}
        </span>
        {hover && (
          <button
            type="button"
            aria-pressed={swapped}
            aria-label={`Show another view of ${product.name}`}
            onClick={() => {
              setPeek(true);
              setSwapped((s) => !s);
            }}
            className={`absolute right-2 bottom-2 z-10 flex size-11 items-center justify-center rounded-full border-2 border-ink text-ink sm:right-3 sm:bottom-3 ${swapped ? "bg-citrus" : "bg-cream"} ${focusRing}`}
          >
            <svg
              viewBox="0 0 24 24"
              className="size-5"
              fill="none"
              stroke="currentColor"
              strokeWidth="2.4"
              strokeLinecap="round"
              strokeLinejoin="round"
              aria-hidden="true"
            >
              <path d="M4 9a8 8 0 0 1 14-3l2 2M20 4v4h-4M20 15a8 8 0 0 1-14 3l-2-2M4 20v-4h4" />
            </svg>
          </button>
        )}
      </div>
      <div className="flex flex-col gap-0.5">
        <h3 className="m-0 text-[17px] leading-snug font-extrabold wrap-break-word sm:text-[22px]">
          <Link
            href={`/products/${product.slug}`}
            className="text-fg no-underline after:absolute after:inset-0 focus-visible:outline-none"
          >
            {product.name}
          </Link>
        </h3>
        {product.series && <p className="m-0 text-sm text-muted sm:text-base">{product.series}</p>}
        <p className="m-0 mt-1 font-bold sm:text-lg">
          <span className="text-sm font-medium text-muted">From </span>
          {formatUsd(product.fromPriceCents)}
        </p>
      </div>
    </li>
  );
}
