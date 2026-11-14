"use client";

import { useRef, useState, type KeyboardEvent } from "react";
import { ProductImage } from "@/components/shop/ProductImage";
import { focusRing } from "@/components/ui";
import { srcSetOf } from "../../_components/data";
import type { DemoPhoto } from "../../_components/products.generated";

const round =
  "flex size-11 flex-none items-center justify-center rounded-full border-2 border-ink bg-cream text-ink hover:bg-citrus " +
  focusRing;

function Arrow({ d }: { d: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      className="size-5"
      fill="none"
      stroke="currentColor"
      strokeWidth="3"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d={d} />
    </svg>
  );
}

/**
 * All photos at once (CSS columns = masonry without JS). Each one opens a native <dialog>:
 * showModal() gives the focus trap, Escape and focus return to the thumbnail.
 */
export function PhotoGallery({ photos, name }: { photos: DemoPhoto[]; name: string }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const [index, setIndex] = useState(0);
  const many = photos.length > 1;
  const current = photos[index];
  // With two columns the first row is photo 1 and the first photo of the second column.
  const secondColumn = Math.ceil(photos.length / 2);

  const step = (by: number) => setIndex((i) => (i + by + photos.length) % photos.length);
  const onKeyDown = (e: KeyboardEvent) => {
    if (!many) return;
    if (e.key === "ArrowLeft") step(-1);
    if (e.key === "ArrowRight") step(1);
  };

  return (
    <>
      <ul className={`m-0 list-none gap-4 p-0 pr-1.5 md:gap-5 ${many ? "sm:columns-2" : ""}`}>
        {photos.map((p, i) => (
          <li key={p.src} className="mb-4 break-inside-avoid pb-1.5 md:mb-5">
            <button
              type="button"
              aria-label={`Enlarge photo ${i + 1} of ${photos.length}: ${p.alt}`}
              onClick={() => {
                setIndex(i);
                dialog.current?.showModal();
              }}
              className={`block w-full cursor-zoom-in overflow-hidden rounded-card border-[3px] border-border bg-tint-neutral p-0 shadow-sticker ${focusRing}`}
            >
              <ProductImage
                src={p.src}
                srcSet={srcSetOf(p)}
                sizes="(min-width: 1024px) 400px, (min-width: 640px) 46vw, 92vw"
                alt={p.alt}
                width={p.width}
                height={p.height}
                loading={i === 0 || i === secondColumn ? "eager" : "lazy"}
                fetchPriority={i === 0 ? "high" : undefined}
                className="block h-auto w-full"
              />
            </button>
          </li>
        ))}
      </ul>

      <dialog
        ref={dialog}
        aria-label={`${name} photos`}
        onKeyDown={onKeyDown}
        // A click on the dark area (the dialog itself, not its children) closes it.
        onClick={(e) => e.target === e.currentTarget && e.currentTarget.close()}
        className="fixed inset-0 m-0 size-full max-h-none max-w-none bg-ink/95 p-0 backdrop:bg-ink"
      >
        <div className="pointer-events-none flex size-full flex-col gap-3 p-3 sm:p-5 *:pointer-events-auto">
          <div className="flex items-center justify-between gap-3">
            <p className="m-0 font-bold text-cream" aria-live="polite">
              {index + 1} / {photos.length}
            </p>
            <button
              type="button"
              aria-label="Close photos"
              onClick={() => dialog.current?.close()}
              className={round}
            >
              <Arrow d="M6 6l12 12M18 6L6 18" />
            </button>
          </div>
          <div className="pointer-events-none! flex min-h-0 flex-1 items-center justify-center">
            <ProductImage
              key={current.src}
              src={current.src}
              srcSet={srcSetOf(current)}
              sizes="100vw"
              alt={current.alt}
              width={current.width}
              height={current.height}
              loading="lazy"
              className="pointer-events-auto h-auto max-h-full w-auto max-w-full rounded-button border-[3px] border-cream"
            />
          </div>
          {many && (
            <div className="flex items-center justify-center gap-4">
              <button
                type="button"
                aria-label="Previous photo"
                onClick={() => step(-1)}
                className={round}
              >
                <Arrow d="M15 5l-7 7 7 7" />
              </button>
              <button
                type="button"
                aria-label="Next photo"
                onClick={() => step(1)}
                className={round}
              >
                <Arrow d="M9 5l7 7-7 7" />
              </button>
            </div>
          )}
        </div>
      </dialog>
    </>
  );
}
