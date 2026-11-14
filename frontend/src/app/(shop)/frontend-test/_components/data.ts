import type { CSSProperties } from "react";

// PROTOTYPE data. Real photos are optimised copies in public/frontend-test/real/ (gitignored).
// The "How we paint" stages still use the placeholder SVGs in public/frontend-test/.
export type Kind = "stock" | "mto" | "commission";

export type Photo = {
  src: string;
  srcSet: string;
  alt: string;
  /** CSS object-position: keeps the face in frame when the tile crops the photo. */
  position: string;
  /** Scale around `position`; above 1 it pushes a phone-camera watermark or desk clutter out of frame. */
  zoom: number;
};

export type Figure = {
  slug: string;
  name: string;
  descriptor: string;
  priceCents: number;
  kind: Kind;
  isNew?: boolean;
  cover: Photo;
  /** Second angle; the card has no swap button without it. */
  alt?: Photo;
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

export const KIND_LABEL: Record<Kind, string> = {
  stock: "In stock",
  mto: "Made to order",
  commission: "Commission",
};

export const KIND_TINT: Record<Kind, string> = {
  stock: "bg-tint-stock",
  mto: "bg-tint-mto",
  commission: "bg-tint-commission",
};

export const FIGURES: Figure[] = [
  {
    slug: "hiyuki",
    name: "Hiyuki",
    descriptor: "30 cm, flowing red hakama",
    priceCents: 16900,
    kind: "stock",
    cover: photo(
      "hiyuki",
      3,
      "Hiyuki figure, front view: white ponytail, white top and a red hakama swept by the wind",
      "100% 30%",
      1.2,
    ),
    alt: photo("hiyuki", 2, "Hiyuki figure, back view", "70% 20%", 1.25),
  },
  {
    slug: "chisa",
    name: "Chisa",
    descriptor: "26 cm, black sailor uniform",
    priceCents: 13900,
    kind: "mto",
    isNew: true,
    cover: photo(
      "chisa",
      2,
      "Chisa figure, front view: long black hair, black jacket over a sailor uniform, red blade",
      "55% 50%",
    ),
    alt: photo("chisa", 4, "Chisa figure, back view", "45% 50%"),
  },
  {
    slug: "changli",
    name: "Changli",
    descriptor: "18 cm, seated on a stone base",
    priceCents: 11900,
    kind: "stock",
    cover: photo(
      "changli",
      1,
      "Changli figure, front view: pink hair, pink dress, a white dove on her red glove",
      "50% 30%",
    ),
    alt: photo("changli", 2, "Changli figure, back view", "50% 40%"),
  },
  {
    slug: "herta",
    name: "Herta",
    descriptor: "28 cm, witch hat and staff",
    priceCents: 15900,
    kind: "mto",
    isNew: true,
    cover: photo(
      "herta",
      2,
      "Herta figure, front view: wide witch hat, black and white dress, tall staff, glowing base",
      "50% 0%",
      1.06,
    ),
    alt: photo("herta", 1, "Herta figure, back view", "40% 0%", 1.15),
  },
  {
    slug: "pneuma",
    name: "Pneuma",
    descriptor: "32 cm, clear emerald wings",
    priceCents: 24000,
    kind: "commission",
    cover: photo(
      "pneuma",
      2,
      "Pneuma figure, front view: green hair, white armour, translucent emerald wings",
      "66% 40%",
      1.1,
    ),
    alt: photo("pneuma", 1, "Pneuma figure, side view", "35% 0%", 1.15),
  },
  {
    slug: "reze",
    name: "Reze",
    descriptor: "24 cm, cafe diorama base",
    priceCents: 14900,
    kind: "mto",
    cover: photo(
      "reze",
      1,
      "Reze figure sitting on a cafe counter next to a small table, a menu board and a plant",
      "50% 0%",
      1.05,
    ),
  },
];

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
