import type { Metadata } from "next";
import Link from "next/link";
import { ProductImage } from "@/components/shop/ProductImage";
import { focusRing } from "@/components/ui";
import { STAGES } from "../_components/data";
import { TestShelf } from "../_components/TestShelf";
import { HeroSlider } from "./_components/HeroSlider";

// PROTOTYPE draft 2 (design experiment, not a tracked task): draft 1's shelf under a hero slider.
// Static data, no backend calls, not in any nav.
export const metadata: Metadata = {
  title: "Shelf layout test: slider",
  robots: { index: false, follow: false },
};

const section = "mx-auto w-full max-w-[1200px] px-4 md:px-6";

export default function FrontendTestSliderPage() {
  return (
    <main id="main" className="flex flex-1 flex-col pb-20 md:pb-28">
      <HeroSlider />

      {/* shortcut: static announcement, wire to real news when the shop has any. */}
      <div className="mx-auto mt-1 w-full max-w-[1600px] border-t-2 border-line">
        <a
          href="#shelf"
          className={`group mx-auto flex min-h-11 w-fit max-w-full items-center gap-2.5 px-4 text-sm text-fg no-underline ${focusRing}`}
        >
          <span
            aria-hidden="true"
            className="flex size-6 flex-none items-center justify-center rounded-full border-2 border-ink bg-citrus text-xs font-extrabold text-ink"
          >
            !
          </span>
          <time dateTime="2026-11-12" className="flex-none font-bold text-subtle">
            12 Nov
          </time>
          <span className="truncate group-hover:underline">
            December made-to-order slots are open: 6 of 8 left
          </span>
          <svg
            viewBox="0 0 24 24"
            className="size-4 flex-none text-accent"
            fill="none"
            stroke="currentColor"
            strokeWidth="3"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <path d="M9 5l7 7-7 7" />
          </svg>
        </a>
      </div>

      <div className="mx-auto w-full max-w-[1600px] rounded-b-[50%_28px] border-y-[3px] border-border bg-citrus px-4 pt-6 pb-10 md:rounded-b-[50%_56px] md:pt-8 md:pb-16">
        {/* Visual only: the prototype has no search, the form just reloads the home page. */}
        <form
          role="search"
          action="/"
          method="get"
          className="mx-auto flex w-full max-w-[640px] items-center gap-1 rounded-full border-[3px] border-ink bg-cream p-1 pl-4 shadow-[5px_5px_0_var(--color-ink)] focus-within:outline-[3px] focus-within:outline-offset-2 focus-within:outline-focus"
        >
          <svg
            viewBox="0 0 24 24"
            className="size-5 flex-none text-ink"
            fill="none"
            stroke="currentColor"
            strokeWidth="2.6"
            strokeLinecap="round"
            aria-hidden="true"
          >
            <circle cx="11" cy="11" r="6.5" />
            <path d="M16 16l4.5 4.5" />
          </svg>
          <input
            type="search"
            name="q"
            aria-label="Search figures"
            placeholder="Search figures"
            className="min-h-11 min-w-0 flex-1 bg-transparent px-2 text-ink placeholder:text-ink/60 focus-visible:outline-none"
          />
          <button
            type="submit"
            className={`min-h-11 flex-none rounded-full border-2 border-ink bg-red px-5 font-bold text-cream hover:bg-red-dark sm:px-7 ${focusRing}`}
          >
            Search
          </button>
        </form>
      </div>

      <section className={`${section} pt-10 pb-8 md:pt-16 md:pb-12`}>
        <p className="m-0 mb-3 text-sm font-bold tracking-[0.08em] text-subtle uppercase">
          The shelf
        </p>
        <h1 className="m-0 max-w-[900px] text-[clamp(32px,5vw,60px)] leading-[1.02] font-extrabold tracking-tight">
          Printed in layers. <span className="text-accent">Painted by hand.</span>
        </h1>
      </section>

      {/* Same sections as draft 1 (../page.tsx), copied because that file must stay untouched. */}
      <section id="shelf" aria-label="Figures" className={`${section} scroll-mt-4`}>
        <TestShelf />
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
                <ProductImage
                  src={stage.src}
                  alt={stage.alt}
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

      <p className={`${section} m-0 mt-16 text-sm text-subtle`}>
        Draft 2 of 2.{" "}
        <Link href="/frontend-test" className="inline-flex min-h-11 items-center text-fg underline">
          See draft 1 (no slider)
        </Link>
      </p>
    </main>
  );
}
