import type { Metadata } from "next";
import Link from "next/link";
import { Suspense } from "react";
import { HeroImage, HeroImageFallback } from "@/components/shop/HeroImage";
import { Shelf, ShelfSkeleton } from "@/components/shop/Shelf";
import { badge, focusRing, primaryButton, secondaryButton } from "@/components/ui";

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
    dot: "bg-card",
  },
];

// Real links arrive with T-020; "#" placeholders until then.
const SOCIALS = ["Reddit", "Facebook", "Instagram", "X"];

const section = "mx-auto w-full max-w-[1200px] px-4 md:px-6";
const heroBadge = `${badge} md:px-3 md:py-1.5 md:text-sm`;

export default function HomePage({ searchParams }: PageProps<"/">) {
  return (
    <main className="flex-1">
      <section
        className={`${section} flex flex-wrap items-center gap-10 pt-8 pb-6 md:pt-14 md:pb-10`}
      >
        <div className="flex min-w-0 flex-[1_1_320px] flex-col gap-4 md:flex-[1_1_460px] md:gap-[22px]">
          <div className="flex flex-wrap gap-2 md:gap-2.5">
            <span className={`${heroBadge} bg-card`}>3D printed</span>
            <span className={`${heroBadge} bg-tint-mto text-ink`}>Hand-painted</span>
            <span className={`${heroBadge} hidden bg-tint-pink text-ink sm:inline`}>
              One at a time
            </span>
          </div>
          <h1 className="text-[38px] leading-[1.08] font-extrabold tracking-[-0.01em] md:text-[clamp(40px,5.4vw,68px)] md:leading-[1.05]">
            Your favourite characters, <span className="text-accent">painted by hand.</span>
          </h1>
          <p className="max-w-[520px] text-[17px] leading-relaxed text-body md:text-[19px]">
            Anime figures printed in resin and finished with a brush in our small studio. Grab one
            that&apos;s ready to ship, or reserve a slot and we&apos;ll make yours.
          </p>
          <div className="mt-1 flex flex-col gap-3.5 sm:flex-row sm:flex-wrap">
            <Link href="/#shop" className={`${primaryButton} text-[17px]`}>
              Shop figures
            </Link>
            <Link href="/commission" className={`${secondaryButton} text-[17px]`}>
              Request a commission
            </Link>
          </div>
        </div>
        <div className="relative min-w-0 flex-[1_1_300px] md:flex-[1_1_420px]">
          <Suspense fallback={<HeroImageFallback />}>
            <HeroImage />
          </Suspense>
          <div className="absolute bottom-5 -left-2 hidden max-w-[220px] -rotate-[5deg] rounded-2xl border-[3px] border-border bg-card px-4 py-3 text-[15px] font-bold shadow-sticker-sm sm:block md:-left-4">
            Ships worldwide · paid in USD
          </div>
        </div>
      </section>

      <section id="shop" className={`${section} scroll-mt-4 py-8 md:py-10`}>
        <h2 className="mb-4 text-3xl font-extrabold md:mb-6 md:text-[40px]">The shelf</h2>
        <div className="flex flex-col gap-4">
          <Suspense fallback={<ShelfSkeleton />}>
            <Shelf searchParams={searchParams} />
          </Suspense>
        </div>
      </section>

      <section id="how" className={`${section} scroll-mt-4 py-8 md:py-12`}>
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
