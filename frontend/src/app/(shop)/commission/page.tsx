import type { Metadata } from "next";
import { SocialLinks } from "@/components/shop/SocialLinks";

export const metadata: Metadata = {
  title: "Commission a figure",
  description: "Want a custom figure? Contact BKStudio on social media to start a commission.",
};

const STEPS = [
  "Contact us on one of the channels below and send your idea and reference images.",
  "We agree the details with you there, then create a private product page just for you and send you its link.",
  "Place your order from that link. Like any made-to-order figure, it needs a 50% deposit.",
  "Your figure takes one slot in our monthly production queue.",
  "When it is finished, you pay the remaining 50% (plus shipping) and we ship it to you.",
];

export default function CommissionPage() {
  return (
    <main className="mx-auto flex w-full max-w-2xl flex-1 flex-col gap-6 px-4 pt-8 pb-16 md:px-6 md:pt-10">
      <div className="flex flex-col gap-2">
        <h1 className="m-0 text-3xl font-extrabold md:text-[40px]">Commission a figure</h1>
        <p className="m-0 text-muted">
          Have a character in mind that is not in the shop? Commissions are arranged through social
          media, not through this website.
        </p>
      </div>
      <ol className="m-0 flex list-decimal flex-col gap-3 pl-6">
        {STEPS.map((step) => (
          <li key={step}>{step}</li>
        ))}
      </ol>
      <section aria-labelledby="contact" className="flex flex-col gap-1">
        <h2 id="contact" className="m-0 text-2xl font-extrabold">
          Get in touch
        </h2>
        <SocialLinks />
      </section>
    </main>
  );
}
