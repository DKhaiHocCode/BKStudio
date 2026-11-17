import type { Metadata } from "next";
import Link from "next/link";
import { SocialLinks } from "@/components/shop/SocialLinks";
import { focusRing } from "@/components/ui";
import { CONTACT_EMAIL } from "@/lib/contact";

export const metadata: Metadata = {
  title: "Commission a figure",
  description: "Want a custom figure? Contact BKStudio on social media to start a commission.",
};

const STEPS = [
  "Message us on one of the channels below with your idea, the character, the scale you want and reference images.",
  "We tell you if we can make it, the price in USD and an estimated start month.",
  "If you agree, we create a private product page just for you and send you its link. Only people with the link can see it.",
  "Place your order from that link. Like any made-to-order figure, it needs a 50% deposit. Your slot in the monthly queue is set once we confirm the deposit.",
  "We print and paint your figure and email you when each stage is done. Work-in-progress photos are on your order page. Revisions are free until painting starts.",
  "When your figure is finished we email you a balance payment link. You then have 30 days to pay the remaining 50% plus shipping, and we ship once it is paid.",
];

const NOTES = [
  "Commissions follow the same rules as other made-to-order figures: the 50% deposit is not refunded if you cancel after it is confirmed, and it is refunded in full if we cancel. Refunds are sent within 10 business days after we confirm the cancellation, back through the same channel you paid with.",
  "Your figure is made just for you. Please do not copy it, or make a mould or cast of it.",
  "Please agree the details (character, pose, scale, colours) before you order. Changes are free until painting starts.",
  "Dates are estimates. Printing and hand-painting can take longer than planned; we tell you early if the start month changes.",
  "If you go quiet while we are agreeing details or waiting for your feedback, we do not cancel your commission automatically. We follow up with you later. Payment link deadlines still apply.",
  "Your private link is just for you. Please do not share it.",
];

const KIT_NOTES = [
  "Kit painting is by prior agreement and is not a standard service. We decide case by case, so we cannot always take a kit on.",
  "We give a quote for each piece. The quote depends on the state of the kit: for example its size, how many parts it has, and how much cleaning, repair or assembly it needs.",
  "Kit painting is not ordered through this website. Talk to us on social media or by email and we agree everything with you there.",
  "You pay shipping and customs both ways. While the kit is on its way to us, it is at your risk. On the way back to you, it is at ours.",
  "We do not have sample photos of this service to show yet.",
];

const listClass = "m-0 flex list-disc flex-col gap-3 pl-6";

export default function CommissionPage() {
  return (
    <main
      id="main"
      className="mx-auto flex w-full max-w-2xl flex-1 flex-col gap-6 px-4 pt-8 pb-16 md:px-6 md:pt-10"
    >
      <div className="flex flex-col gap-2">
        <h1 className="m-0 text-3xl font-extrabold md:text-[40px]">Commission a figure</h1>
        <p className="m-0 text-muted">
          Have a character in mind that is not in the shop? We print and paint it from scratch.
          Commissions are arranged through social media, not through this website.
        </p>
      </div>
      <ol className="m-0 flex list-decimal flex-col gap-3 pl-6">
        {STEPS.map((step) => (
          <li key={step}>{step}</li>
        ))}
      </ol>
      <section aria-labelledby="good-to-know" className="flex flex-col gap-3">
        <h2 id="good-to-know" className="m-0 text-2xl font-extrabold">
          Good to know
        </h2>
        <ul className={listClass}>
          {NOTES.map((note) => (
            <li key={note}>{note}</li>
          ))}
        </ul>
        <p className="m-0">
          <Link href="/policy" className={`font-bold underline hover:text-danger ${focusRing}`}>
            See our terms and policy
          </Link>
        </p>
      </section>
      <section aria-labelledby="kit-painting" className="flex flex-col gap-3">
        <h2 id="kit-painting" className="m-0 scroll-mt-24 text-2xl font-extrabold">
          Painting your kit
        </h2>
        <p className="m-0">
          Already have a garage kit or a custom piece of your own? Sometimes we can paint it for
          you.
        </p>
        <ul className={listClass}>
          {KIT_NOTES.map((note) => (
            <li key={note}>{note}</li>
          ))}
        </ul>
      </section>
      <section aria-labelledby="contact" className="flex flex-col gap-1">
        <h2 id="contact" className="m-0 text-2xl font-extrabold">
          Get in touch
        </h2>
        <p className="m-0">
          Email{" "}
          <a
            href={`mailto:${CONTACT_EMAIL}`}
            className={`font-bold underline hover:text-danger ${focusRing}`}
          >
            {CONTACT_EMAIL}
          </a>{" "}
          or message us here:
        </p>
        <SocialLinks />
      </section>
    </main>
  );
}
