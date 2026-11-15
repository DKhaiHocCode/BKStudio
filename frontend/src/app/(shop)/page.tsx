import type { Metadata } from "next";
import { Suspense } from "react";
import { HeroSlider } from "@/components/shop/HeroSlider";
import { ProductImage } from "@/components/shop/ProductImage";
import { Shelf, ShelfSkeleton } from "@/components/shop/Shelf";
import { focusRing } from "@/components/ui";

export const metadata: Metadata = {
  title: { absolute: "BKStudio | Hand-painted 3D printed anime figures" },
  description:
    "Anime figures printed in resin and finished with a brush in our small studio. Ready to ship, or reserve a slot and we make yours.",
  alternates: { canonical: "/" },
};

const STEPS = [
  {
    n: "1",
    title: "Place your order",
    body: "Pick a figure and scale. We email you a secure payment link.",
    dot: "bg-tint-pink",
  },
  {
    n: "2",
    title: "Pay 50% deposit",
    body: "Your slot is locked in and we confirm the month we start.",
    dot: "bg-tint-commission",
  },
  {
    n: "3",
    title: "We print & paint",
    body: "Resin printing, cleanup, priming, then hand-painting.",
    dot: "bg-tint-stock",
  },
  {
    n: "4",
    title: "Pay the rest, we ship",
    body: "Photos of your finished figure, then balance and tracked shipping.",
    dot: "bg-cream",
  },
];

// Real links arrive with T-020; "#" placeholders until then.
const SOCIALS = ["Reddit", "Facebook", "Instagram", "X"];

const STAGES = [
  {
    src: "/shop/stage-1-raw.svg",
    title: "Raw print",
    text: "Printed in resin, supports cut, seams sanded smooth.",
    tone: "bg-tint-neutral",
  },
  {
    src: "/shop/stage-2-primed.svg",
    title: "Primed",
    text: "A grey coat shows every flaw before colour goes on.",
    tone: "bg-tint-commission",
  },
  {
    src: "/shop/stage-3-painted.svg",
    title: "Painted",
    text: "Base colours, shading and details, all by brush.",
    tone: "bg-tint-mto",
  },
];

const section = "mx-auto w-full max-w-[1200px] px-4 md:px-6";

export default function HomePage({ searchParams }: PageProps<"/">) {
  return (
    <main id="main" className="flex-1">
      <HeroSlider />

      <section className={`${section} pt-10 pb-8 md:pt-16 md:pb-12`}>
        <p className="m-0 mb-3 text-sm font-bold tracking-[0.08em] text-subtle uppercase">
          The shelf
        </p>
        <h1 className="m-0 max-w-[900px] text-[clamp(32px,5vw,60px)] leading-[1.02] font-extrabold tracking-tight">
          Printed in layers. <span className="text-accent">Painted by hand.</span>
        </h1>
        <p className="m-0 mt-4 max-w-[520px] text-[17px] leading-relaxed text-body md:text-[19px]">
          Anime figures printed in resin and finished with a brush in our small studio. Grab one
          that&apos;s ready to ship, or reserve a slot and we&apos;ll make yours.
        </p>
      </section>

      <section id="shop" aria-labelledby="shop-heading" className={`${section} scroll-mt-4`}>
        <h2 id="shop-heading" className="sr-only">
          Figures
        </h2>
        <Suspense fallback={<ShelfSkeleton />}>
          <Shelf searchParams={searchParams} />
        </Suspense>
      </section>

      <section id="process" className={`${section} mt-20 scroll-mt-4 md:mt-32`}>
        <h2 className="m-0 text-3xl font-extrabold md:text-[40px]">How we paint</h2>
        <p className="m-0 mt-2 max-w-[520px] text-body md:text-lg">
          Every figure goes through the same three stages before it ships.
        </p>
        <ol className="m-0 mt-8 grid list-none grid-cols-1 gap-8 p-0 sm:grid-cols-3 md:mt-10">
          {STAGES.map((stage, i) => (
            <li key={stage.title} className="flex flex-col gap-4">
              <div
                className={`relative aspect-[4/3] overflow-hidden rounded-card border-[3px] border-border shadow-sticker sm:aspect-square ${stage.tone}`}
              >
                {/* Decorative drawing: the heading and text below describe the stage. */}
                <ProductImage
                  src={stage.src}
                  alt=""
                  width={300}
                  height={375}
                  loading="lazy"
                  className="absolute inset-0 size-full object-contain p-4"
                />
                <span className="absolute top-3 left-3 flex size-9 items-center justify-center rounded-full border-2 border-ink bg-cream text-sm font-extrabold text-ink">
                  {i + 1}
                </span>
              </div>
              <div>
                <h3 className="m-0 text-[22px] font-extrabold">{stage.title}</h3>
                <p className="m-0 mt-1 text-body">{stage.text}</p>
              </div>
            </li>
          ))}
        </ol>
      </section>

      <section id="how" className={`${section} mt-12 scroll-mt-4 py-8 md:mt-20 md:py-12`}>
        <div className="flex flex-col gap-6 rounded-3xl border-[3px] border-border bg-citrus p-5 text-ink shadow-sticker md:gap-7 md:rounded-panel md:p-8 md:shadow-sticker-lg lg:p-10">
          <h2 className="max-w-[620px] text-[26px] font-extrabold md:text-4xl">
            How made-to-order works
          </h2>
          <ol className="m-0 flex list-none flex-wrap gap-3.5 p-0 md:gap-[18px]">
            {STEPS.map((s) => (
              <li
                key={s.n}
                className="flex min-w-0 flex-[1_1_220px] items-start gap-3 rounded-2xl border-[3px] border-border bg-card p-3.5 text-fg md:flex-col md:gap-2.5 md:rounded-[20px] md:p-5"
              >
                <span
                  className={`flex size-[34px] flex-none items-center justify-center rounded-full border-[3px] border-border font-extrabold text-ink md:size-11 md:text-lg ${s.dot}`}
                >
                  {s.n}
                </span>
                <span className="flex flex-col gap-0.5">
                  <span className="font-extrabold md:text-xl">{s.title}</span>
                  <span className="text-sm leading-relaxed text-body md:text-base">{s.body}</span>
                </span>
              </li>
            ))}
          </ol>
          <p className="m-0 text-[15px] font-bold">
            Heads up: the 50% deposit is non-refundable once your slot is confirmed. If we
            can&apos;t make your figure, you get the full deposit back.
          </p>
        </div>
      </section>

      <section className={`${section} pt-4 pb-10 md:pt-8 md:pb-14`}>
        <div className="flex flex-wrap items-center justify-between gap-7 rounded-3xl border-[3px] border-border bg-red p-5 text-cream shadow-sticker md:rounded-panel md:p-8 md:shadow-sticker-lg lg:p-10">
          <div className="flex min-w-0 flex-[1_1_320px] flex-col gap-3">
            <h2 className="text-[26px] font-extrabold md:text-4xl">
              Want a character we don&apos;t have?
            </h2>
            <p className="m-0 text-lg leading-relaxed">
              Message us with your reference pictures. We&apos;ll quote a price and make a private
              listing just for you.
            </p>
          </div>
          <div className="flex flex-wrap gap-2.5">
            {SOCIALS.map((label) => (
              <a
                key={label}
                href="#"
                className={`inline-flex min-h-11 items-center rounded-button border-[3px] border-border bg-cream px-[18px] font-bold text-ink no-underline shadow-sticker-sm hover:bg-citrus ${focusRing}`}
              >
                {label}
              </a>
            ))}
          </div>
        </div>
      </section>
    </main>
  );
}
