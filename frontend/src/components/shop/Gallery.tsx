"use client";

import { useEffect, useRef, useState, type KeyboardEvent } from "react";
import { focusRing, secondaryButton } from "@/components/ui";
import type { ProductImage as Img } from "@/lib/catalog";
import { ProductImage, srcSetOf } from "./ProductImage";

// Phones show this many photos until "Show all" is pressed; from `sm` up nothing is cut.
const PHONE_LIMIT = 4;

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
export function Gallery({ images, name }: { images: Img[]; name: string }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const [index, setIndex] = useState(0);
  const list = useRef<HTMLUListElement>(null);
  const [expanded, setExpanded] = useState(false);
  // The "Show all" button disappears once pressed: hand focus to the first revealed photo.
  useEffect(() => {
    if (!expanded) return;
    list.current?.querySelector<HTMLElement>(`li:nth-child(${PHONE_LIMIT + 1}) button`)?.focus();
  }, [expanded]);
  const many = images.length > 1;
  const current = images[index];
  const altOf = (img: Img, i: number) => img.alt || `${name}, photo ${i + 1}`;

  if (!current) {
    return (
      <div className="mr-1.5 mb-1.5 flex aspect-[4/5] max-w-[480px] items-center justify-center rounded-card border-[3px] border-border bg-tint-neutral font-bold text-ink shadow-sticker">
        Photo coming soon
      </div>
    );
  }

  const step = (by: number) => setIndex((i) => (i + by + images.length) % images.length);
  const onKeyDown = (e: KeyboardEvent) => {
    if (!many) return;
    if (e.key === "ArrowLeft") step(-1);
    if (e.key === "ArrowRight") step(1);
  };

  return (
    <>
      <ul
        ref={list}
        id="gallery-photos"
        className={`m-0 list-none gap-4 p-0 pr-1.5 md:gap-5 ${many ? "sm:columns-2" : ""}`}
      >
        {images.map((img, i) => (
          <li
            key={img.url}
            // Hidden photos are loading="lazy" with no box, so the browser does not download them.
            className={`mb-4 break-inside-avoid pb-1.5 md:mb-5 ${i >= PHONE_LIMIT && !expanded ? "max-sm:hidden" : ""}`}
          >
            <button
              type="button"
              aria-label={`Enlarge photo ${i + 1} of ${images.length}: ${altOf(img, i)}`}
              onClick={() => {
                setIndex(i);
                dialog.current?.showModal();
              }}
              className={`block w-full cursor-zoom-in overflow-hidden rounded-card border-[3px] border-border bg-tint-neutral p-0 shadow-sticker ${focusRing}`}
            >
              <ProductImage
                src={img.url}
                srcSet={srcSetOf(img.srcset)}
                sizes={
                  many
                    ? "(min-width: 1024px) 400px, (min-width: 640px) 46vw, 92vw"
                    : "(min-width: 1024px) 760px, 92vw"
                }
                alt={altOf(img, i)}
                width={img.width}
                height={img.height}
                loading={i === 0 ? "eager" : "lazy"}
                fetchPriority={i === 0 ? "high" : undefined}
                className="block h-auto w-full"
              />
            </button>
          </li>
        ))}
      </ul>
      {images.length > PHONE_LIMIT && !expanded && (
        <button
          type="button"
          aria-expanded="false"
          aria-controls="gallery-photos"
          onClick={() => setExpanded(true)}
          className={`${secondaryButton} mr-1.5 w-[calc(100%-6px)] sm:hidden`}
        >
          Show all {images.length} photos
        </button>
      )}

      <dialog
        ref={dialog}
        aria-label={`${name} photos`}
        onKeyDown={onKeyDown}
        // A click on the dark area (the dialog itself, not its children) closes it.
        onClick={(e) => e.target === e.currentTarget && e.currentTarget.close()}
        className="fixed inset-0 m-0 size-full max-h-none max-w-none overscroll-contain bg-ink/95 p-0 backdrop:bg-ink"
      >
        <div className="pointer-events-none flex size-full flex-col gap-3 p-3 *:pointer-events-auto sm:p-5">
          <div className="flex items-center justify-between gap-3">
            <p className="m-0 font-bold text-cream" aria-live="polite">
              {index + 1} / {images.length}
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
              key={current.url}
              src={current.url}
              srcSet={srcSetOf(current.srcset)}
              sizes="100vw"
              alt={altOf(current, index)}
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
