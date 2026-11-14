import type { Metadata } from "next";
import { ProductImage } from "@/components/shop/ProductImage";
import { STAGES } from "./_components/data";
import { TestShelf } from "./_components/TestShelf";

// PROTOTYPE (design experiment, not a tracked task): static data, no backend calls, not in any nav.
export const metadata: Metadata = {
  title: "Shelf layout test",
  robots: { index: false, follow: false },
};

const section = "mx-auto w-full max-w-[1200px] px-4 md:px-6";

export default function FrontendTestPage() {
  return (
    <main id="main" className="flex flex-1 flex-col pb-20 md:pb-28">
      <section className={`${section} pt-12 pb-10 md:pt-24 md:pb-16`}>
        <p className="m-0 mb-4 text-sm font-bold tracking-[0.08em] text-subtle uppercase">
          The shelf
        </p>
        <h1 className="m-0 max-w-[900px] text-[clamp(40px,7.4vw,92px)] leading-[0.98] font-extrabold tracking-tight">
          Printed in layers. <span className="block text-accent">Painted by hand.</span>
        </h1>
        <p className="m-0 mt-5 max-w-[520px] text-[17px] leading-relaxed text-body md:mt-7 md:text-[19px]">
          Small-batch anime figures, finished one brush stroke at a time.
        </p>
      </section>

      <section id="shelf" aria-label="Figures" className={`${section} scroll-mt-4`}>
        <TestShelf />
      </section>

      <section className={`${section} mt-20 md:mt-32`}>
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
    </main>
  );
}
