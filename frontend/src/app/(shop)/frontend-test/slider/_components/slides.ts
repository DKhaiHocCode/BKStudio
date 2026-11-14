// PROTOTYPE data. Banners are 16:7 placeholder SVGs in public/frontend-test/slides/; swap real
// art in here only. Keep the left half of a banner calm: the text is laid over it in HTML.
export type Slide = {
  src: string;
  alt: string;
  kicker: string;
  title: string;
  cta: string;
  href: string;
  /** Tailwind backdrop class shown while the banner loads. */
  tone: string;
};

const banner = (name: string) => `/frontend-test/slides/${name}.svg`;

export const SLIDES: Slide[] = [
  {
    src: banner("slide-1-new"),
    alt: "Placeholder banner: the Ember Blade figure in front of a yellow circle",
    kicker: "New figure",
    title: "Ember Blade has landed",
    cta: "See it on the shelf",
    href: "#shelf",
    tone: "bg-tint-pink",
  },
  {
    src: banner("slide-2-mto"),
    alt: "Placeholder banner: two made-to-order figures under a row of eight slots, six still open",
    kicker: "Made to order",
    title: "December slots are open",
    cta: "Reserve a slot",
    href: "#shelf",
    tone: "bg-tint-mto",
  },
  {
    src: banner("slide-3-commission"),
    alt: "Placeholder banner: a dashed outline with a question mark turning into a painted figure",
    kicker: "Commission",
    title: "Your character, on your desk",
    cta: "Start a commission",
    href: "#shelf",
    tone: "bg-tint-commission",
  },
  {
    src: banner("slide-4-process"),
    alt: "Placeholder banner: one figure as a raw print, primed in grey, then fully painted",
    kicker: "How we paint",
    title: "From raw print to final coat",
    cta: "See the three stages",
    href: "#process",
    tone: "bg-tint-neutral",
  },
  {
    src: banner("slide-5-restock"),
    alt: "Placeholder banner: three Twin Comet figures lined up on a shelf",
    kicker: "Restock",
    title: "Twin Comet is back",
    cta: "Grab one",
    href: "#shelf",
    tone: "bg-tint-stock",
  },
];
