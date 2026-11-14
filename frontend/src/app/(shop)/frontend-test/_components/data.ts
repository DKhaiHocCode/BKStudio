// PROTOTYPE data. Placeholder SVGs live in public/frontend-test/; swap real photos in here only.
export type Kind = "stock" | "mto" | "commission";

export type Figure = {
  slug: string;
  name: string;
  descriptor: string;
  priceCents: number;
  kind: Kind;
  isNew?: boolean;
  /** Tailwind backdrop class behind the (transparent) image. */
  tone: string;
  front: string;
  back: string;
};

const img = (n: number, view: "front" | "back") => `/frontend-test/figure-${n}-${view}.svg`;

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
    slug: "ember-blade",
    name: "Ember Blade",
    descriptor: "18 cm, matte finish",
    priceCents: 8900,
    kind: "stock",
    isNew: true,
    tone: "bg-tint-pink",
    front: img(1, "front"),
    back: img(1, "back"),
  },
  {
    slug: "red-cloak",
    name: "Red Cloak",
    descriptor: "16 cm, cloth-look cape",
    priceCents: 7400,
    kind: "mto",
    tone: "bg-tint-mto",
    front: img(2, "front"),
    back: img(2, "back"),
  },
  {
    slug: "twin-comet",
    name: "Twin Comet",
    descriptor: "15 cm, gloss accents",
    priceCents: 6900,
    kind: "stock",
    tone: "bg-tint-commission",
    front: img(3, "front"),
    back: img(3, "back"),
  },
  {
    slug: "old-wanderer",
    name: "Old Wanderer",
    descriptor: "20 cm, weathered paint",
    priceCents: 11900,
    kind: "mto",
    isNew: true,
    tone: "bg-tint-stock",
    front: img(4, "front"),
    back: img(4, "back"),
  },
  {
    slug: "your-character",
    name: "Your Character",
    descriptor: "Custom sculpt and paint",
    priceCents: 15000,
    kind: "commission",
    tone: "bg-capybara",
    front: img(3, "front"),
    back: img(3, "back"),
  },
  {
    slug: "night-courier",
    name: "Night Courier",
    descriptor: "17 cm, metallic trim",
    priceCents: 8200,
    kind: "mto",
    tone: "bg-tint-neutral",
    front: img(1, "front"),
    back: img(1, "back"),
  },
  {
    slug: "harvest-witch",
    name: "Harvest Witch",
    descriptor: "19 cm, hand-shaded robe",
    priceCents: 9800,
    kind: "stock",
    tone: "bg-citrus",
    front: img(4, "front"),
    back: img(4, "back"),
  },
  {
    slug: "duo-diorama",
    name: "Duo Diorama",
    descriptor: "Two figures, one base",
    priceCents: 24000,
    kind: "commission",
    tone: "bg-tint-pink",
    front: img(2, "front"),
    back: img(2, "back"),
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
