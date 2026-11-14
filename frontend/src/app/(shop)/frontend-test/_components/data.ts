import type { CSSProperties } from "react";
import type { DemoPhoto, DemoStatus } from "./products.generated";

// PROTOTYPE data. Real photos are optimised copies in public/frontend-test/real/ (gitignored).
// Products live in products.generated.ts (npm run demo:photos); `photo()` below is only for the
// hand-picked hero slides.
// The "How we paint" stages still use the placeholder SVGs in public/frontend-test/.
export type Photo = {
  src: string;
  srcSet: string;
  alt: string;
  /** CSS object-position: keeps the face in frame when the tile crops the photo. */
  position: string;
  /** Scale around `position`; above 1 it pushes a phone-camera watermark or desk clutter out of frame. */
  zoom: number;
};

export function photo(slug: string, n: number, alt: string, position = "50% 50%", zoom = 1): Photo {
  const base = `/frontend-test/real/${slug}/0${n}`;
  return {
    src: `${base}.webp`,
    srcSet: `${base}-700.webp 700w, ${base}.webp 1400w`,
    alt,
    position,
    zoom,
  };
}

/** The <img> must sit in an overflow-hidden box: a zoomed photo is larger than its own box. */
export function photoStyle(p: Photo): CSSProperties {
  return {
    objectPosition: p.position,
    transformOrigin: p.position,
    transform: p.zoom === 1 ? undefined : `scale(${p.zoom})`,
  };
}

export const STATUS_LABEL: Record<DemoStatus, string> = {
  IN_STOCK: "In stock",
  MADE_TO_ORDER: "Made to order",
  COMMISSION: "Commission",
};

export const STATUS_TINT: Record<DemoStatus, string> = {
  IN_STOCK: "bg-tint-stock",
  MADE_TO_ORDER: "bg-tint-mto",
  COMMISSION: "bg-tint-commission",
};

/** Real file widths, so the browser can pick the 700px copy for small tiles. */
export function srcSetOf(p: DemoPhoto): string {
  return `${p.src700} ${p.width700}w, ${p.src} ${p.width}w`;
}

export const STAGES = [
  {
    src: "/frontend-test/stage-1-raw.svg",
    alt: "Placeholder: figure straight off the printer with layer lines and supports",
    title: "Raw print",
    text: "Printed in resin, supports cut, seams sanded smooth.",
    tone: "bg-tint-neutral",
  },
  {
    src: "/frontend-test/stage-2-primed.svg",
    alt: "Placeholder: the same figure in flat grey primer",
    title: "Primed",
    text: "A grey coat shows every flaw before colour goes on.",
    tone: "bg-tint-commission",
  },
  {
    src: "/frontend-test/stage-3-painted.svg",
    alt: "Placeholder: the same figure fully painted",
    title: "Painted",
    text: "Base colours, shading and details, all by brush.",
    tone: "bg-tint-mto",
  },
];
