import Link from "next/link";
import { badge, focusRing } from "@/components/ui";
import type { ProductListItem } from "@/lib/catalog";
import { formatUsd } from "@/lib/money";
import { ProductImage } from "./ProductImage";

export function ProductCard({ product }: { product: ProductListItem }) {
  const { coverImage } = product;
  return (
    <Link
      href={`/products/${product.slug}`}
      className={`flex flex-col overflow-hidden rounded-[18px] border-[3px] border-border bg-card text-fg no-underline shadow-sticker-sm sm:rounded-card sm:shadow-[6px_6px_0_var(--shadow-color)] ${focusRing}`}
    >
      <div className="relative flex aspect-square items-center justify-center border-b-[3px] border-border bg-capybara">
        {coverImage ? (
          <ProductImage
            src={coverImage.url}
            alt={coverImage.alt || product.name}
            width={coverImage.width}
            height={coverImage.height}
            sizes="(min-width: 1024px) 280px, 50vw"
            loading="lazy"
            className="absolute inset-0 size-full object-cover"
          />
        ) : (
          <span className="text-sm font-bold text-bark">Photo coming soon</span>
        )}
        <span className="absolute top-2 left-2 flex flex-wrap gap-1 sm:top-3 sm:left-3">
          {product.inStock && <span className={`${badge} bg-tint-stock text-ink`}>In stock</span>}
          {product.madeToOrder && (
            <span className={`${badge} bg-tint-mto text-ink`}>Made to order</span>
          )}
        </span>
      </div>
      <div className="flex flex-col gap-1 px-3 pt-2.5 pb-3.5 sm:gap-1.5 sm:px-[18px] sm:pt-4 sm:pb-5">
        {product.series && (
          <span className="text-xs font-bold tracking-[0.06em] text-subtle uppercase sm:text-[13px]">
            {product.series}
          </span>
        )}
        <span className="text-base font-extrabold sm:text-[22px]">{product.name}</span>
        <span className="font-bold sm:mt-1.5 sm:text-lg">
          <span className="text-sm font-medium text-muted">From </span>
          {formatUsd(product.fromPriceCents)}
        </span>
      </div>
    </Link>
  );
}
