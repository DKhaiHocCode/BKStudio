// PROTOTYPE data. Each banner is a flat colour on the left (the HTML text sits there) and a real
// photo cropped into the right half. The old placeholder SVGs stay in public/frontend-test/slides/.
import { photo, type Photo } from "../../_components/data";

export type Slide = {
  photo: Photo;
  kicker: string;
  title: string;
  cta: string;
  href: string;
  /** Tailwind backdrop class of the text half. */
  tone: string;
};

export const SLIDES: Slide[] = [
  {
    photo: photo("chisa", 2, "Chisa figure in a black jacket and sailor uniform", "50% 10%"),
    kicker: "New figure",
    title: "Chisa has landed",
    cta: "See it on the shelf",
    href: "#shelf",
    tone: "bg-tint-pink",
  },
  {
    photo: photo("pneuma", 4, "Pneuma figure with translucent emerald wings", "100% 20%", 1.15),
    kicker: "Made to order",
    title: "December slots are open",
    cta: "Reserve a slot",
    href: "#shelf",
    tone: "bg-tint-mto",
  },
  {
    photo: photo("reze", 1, "Reze figure sitting on a cafe counter diorama", "50% 25%"),
    kicker: "Commission",
    title: "Your character, on your desk",
    cta: "Start a commission",
    href: "#shelf",
    tone: "bg-tint-commission",
  },
  {
    photo: photo("chisa", 1, "Close-up of a hand-painted pond base with pink petals", "50% 0%"),
    kicker: "How we paint",
    title: "From raw print to final coat",
    cta: "See the three stages",
    href: "#process",
    tone: "bg-tint-neutral",
  },
  {
    photo: photo("changli", 3, "Changli figure in a pink dress holding a white dove", "50% 15%"),
    kicker: "Restock",
    title: "Changli is back",
    cta: "Grab one",
    href: "#shelf",
    tone: "bg-tint-stock",
  },
];
