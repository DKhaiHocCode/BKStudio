"use client";

import { useState } from "react";
import { ProductImage } from "@/components/shop/ProductImage";
import { badge, chip, chipActive, focusRing } from "@/components/ui";
import { formatUsd } from "@/lib/money";
import { FIGURES, KIND_LABEL, KIND_TINT, photoStyle, type Figure, type Kind } from "./data";

type Filter = "all" | Kind;
const FILTERS: Filter[] = ["all", "stock", "mto", "commission"];

const fade = "absolute inset-0 size-full object-cover transition-opacity duration-200";
// Tile width per breakpoint (2 / 3 / 4 columns), so the browser picks the 700w file on most screens.
const SIZES = "(min-width: 1024px) 280px, (min-width: 768px) 31vw, 46vw";
// Second view on hover (mouse only) and on keyboard focus; touch uses the swap button.
const PEEK_HIDE =
  "[@media(hover:hover)]:group-hover:opacity-0 group-has-[a:focus-visible]:opacity-0";
const PEEK_SHOW =
  "[@media(hover:hover)]:group-hover:opacity-100 group-has-[a:focus-visible]:opacity-100";

function FigureCard({ figure, priority }: { figure: Figure; priority: boolean }) {
  const [swapped, setSwapped] = useState(false);
  return (
    <li className="group relative flex min-w-0 flex-col gap-3 sm:gap-4">
      {/* No transform on this tile: it would trap the z-10 swap button under the stretched link. */}
      <div
        className={`relative aspect-[4/5] overflow-hidden bg-tint-neutral rounded-[18px] border-[3px] border-border shadow-sticker-sm group-has-[a:focus-visible]:outline-[3px] group-has-[a:focus-visible]:outline-offset-2 group-has-[a:focus-visible]:outline-focus sm:rounded-card sm:shadow-sticker`}
      >
        <ProductImage
          src={figure.cover.src}
          srcSet={figure.cover.srcSet}
          sizes={SIZES}
          alt={figure.cover.alt}
          width={700}
          height={875}
          loading={priority ? "eager" : "lazy"}
          fetchPriority={priority ? "high" : undefined}
          style={photoStyle(figure.cover)}
          className={`${fade} ${!figure.alt ? "" : swapped ? "opacity-0" : `opacity-100 ${PEEK_HIDE}`}`}
        />
        {figure.alt && (
          <ProductImage
            src={figure.alt.src}
            srcSet={figure.alt.srcSet}
            sizes={SIZES}
            alt=""
            aria-hidden="true"
            width={700}
            height={875}
            loading="lazy"
            style={photoStyle(figure.alt)}
            className={`${fade} ${swapped ? "opacity-100" : `opacity-0 ${PEEK_SHOW}`}`}
          />
        )}
        <span className="absolute top-2 left-2 sm:top-3 sm:left-3">
          <span className={`${badge} inline-block ${KIND_TINT[figure.kind]} text-ink`}>
            {KIND_LABEL[figure.kind]}
          </span>
        </span>
        {figure.isNew && (
          <span className="absolute bottom-3 left-2 -rotate-6 rounded-full border-2 border-ink bg-citrus px-2.5 py-0.5 text-[13px] font-extrabold text-ink sm:top-3 sm:right-3 sm:bottom-auto sm:left-auto sm:rotate-6">
            New
          </span>
        )}
        {figure.alt && (
          <button
            type="button"
            aria-pressed={swapped}
            aria-label={`Show another view of ${figure.name}`}
            onClick={() => setSwapped((s) => !s)}
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
        <h3 className="m-0 text-[17px] leading-snug font-extrabold sm:text-[22px]">
          {/* shortcut: prototype has no product pages, link stays on the shelf; point at /products/[slug] when real. */}
          <a
            href="#shelf"
            className="text-fg no-underline after:absolute after:inset-0 focus-visible:outline-none"
          >
            {figure.name}
          </a>
        </h3>
        <p className="m-0 text-sm text-muted sm:text-base">{figure.descriptor}</p>
        <p className="m-0 mt-1 font-bold sm:text-lg">
          {figure.kind === "commission" && (
            <span className="text-sm font-medium text-muted">From </span>
          )}
          {formatUsd(figure.priceCents)}
        </p>
      </div>
    </li>
  );
}

/** `priority`: load the first row eagerly; set it only where the shelf is on the first screen. */
export function TestShelf({ priority = false }: { priority?: boolean }) {
  const [filter, setFilter] = useState<Filter>("all");
  const shown = FIGURES.filter((f) => filter === "all" || f.kind === filter);

  return (
    <>
      <div className="flex flex-col gap-3 border-y-2 border-line py-3 sm:flex-row sm:items-center sm:justify-between">
        <p className="m-0 text-sm font-bold tracking-[0.06em] text-subtle uppercase" role="status">
          {shown.length} {shown.length === 1 ? "figure" : "figures"}
        </p>
        <div
          role="group"
          aria-label="Filter"
          className="-mx-1 flex gap-2 overflow-x-auto p-1 sm:flex-wrap"
        >
          {FILTERS.map((f) => (
            <button
              key={f}
              type="button"
              aria-pressed={filter === f}
              onClick={() => setFilter(f)}
              className={`flex-none text-sm sm:text-base ${filter === f ? chipActive : chip}`}
            >
              {f === "all" ? "All" : KIND_LABEL[f]}
            </button>
          ))}
        </div>
      </div>
      <ul className="m-0 mt-8 grid list-none grid-cols-2 gap-x-4 gap-y-10 p-0 sm:mt-12 sm:gap-x-8 sm:gap-y-14 md:grid-cols-3 lg:grid-cols-4">
        {shown.map((f, i) => (
          <FigureCard key={f.slug} figure={f} priority={priority && i < 4} />
        ))}
      </ul>
    </>
  );
}
