import type { Metadata } from "next";
import Link from "next/link";
import type { ReactNode } from "react";
import { focusRing } from "@/components/ui";
import { CONTACT_EMAIL } from "@/lib/contact";

export const metadata: Metadata = {
  title: "FAQ",
  description: "Answers about in-stock and made-to-order figures, deposits, payment and shipping.",
};

const linkClass = `font-bold underline hover:text-danger ${focusRing}`;

// Answers render inside one <p>, so later paragraphs are block spans.
const paraClass = "mt-2 block";

const supportEmail = (
  <a href={`mailto:${CONTACT_EMAIL}`} className={linkClass}>
    {CONTACT_EMAIL}
  </a>
);

const FAQ: { q: string; a: ReactNode }[] = [
  {
    q: "What is the difference between in-stock and made-to-order?",
    a: "In-stock figures are already finished and ship soon after you pay in full. Made-to-order figures are printed and hand-painted for you after you order, so there is a wait.",
  },
  {
    q: "How does the deposit work?",
    a: "Made-to-order figures need a 50% deposit when you order. Your slot in the queue is set once we confirm the deposit. You pay the other 50%, plus shipping, when your figure is finished. In-stock figures are paid in full in one payment.",
  },
  {
    q: "What is the monthly queue?",
    a: "We make a limited number of figures each month and every figure takes one slot. The start month shown before you order is an estimate; we confirm the official month once your deposit is received. If a month is full you can still order and join the queue, with a longer wait.",
  },
  {
    q: "How do I pay?",
    a: "After you order, we email you a secure payment link. The deposit on a made-to-order figure is paid on Artistree. In-stock figures, and the balance on a made-to-order figure, are paid on Shopee through a proxy buyer service we arrange with you, because Shopee has no direct way for overseas buyers to pay. This website never takes card details. Deposit and in-stock payment links expire 48 hours after we send them; the balance link gives you 30 days.",
  },
  {
    q: "How long do I have to pay the balance?",
    a: "30 days after we send the balance payment link, once your figure is finished. We send reminders 7 days and 1 day before the deadline.",
  },
  {
    q: "What does shipping cost?",
    a: "Shipping is paid by you. We tell you the exact amount before you pay. We email you when your figure ships. The carrier and tracking number are on your order page. Please check your address carefully: if a parcel comes back to us because the address was wrong, you pay to ship it again.",
  },
  {
    q: "Do I pay customs, GST or import duty?",
    a: "Possibly. Import taxes and duties depend on your country and the value of your parcel. They are not included in our prices or in the shipping fee, and they are paid by you. We cannot tell you the exact amount in advance.",
  },
  {
    q: "Can I cancel my order?",
    a: (
      <>
        Before you pay: if the deposit or in-stock payment link expires, the order is cancelled and
        nothing is charged.
        <span className={paraClass}>
          After your deposit is confirmed on a made-to-order figure: if you change your mind, the
          50% deposit is not refunded, because your slot is held and work is planned for you.
        </span>
        <span className={paraClass}>
          If we cancel (for example we cannot make it, a print fails, or we stop taking orders), you
          get your deposit back in full.
        </span>
        <span className={paraClass}>
          In-stock figure: if you change your mind, tell us before the parcel ships and we refund
          you. Preparing an in-stock order takes about 3 days, and we accept refund requests made in
          that time. Once the parcel has shipped, we do not take it back for a change of mind.
        </span>
        <span className={paraClass}>
          If the balance is not paid within 30 days, the order is forfeited and the deposit is kept.
        </span>
        <span className={paraClass}>
          Refunds are sent within 10 business days after we confirm the cancellation, back through
          the same channel you paid with. If the payment platform (Artistree for a deposit, Shopee
          for an in-stock payment or a balance) or the proxy buyer service does not return its fees,
          whoever cancelled pays them; if it is not clear who cancelled, we do.
        </span>
        <span className={paraClass}>
          These rules are about changing your mind. They do not take away any rights you have under
          consumer law where you live, for example if a figure arrives damaged or not as described.
        </span>
      </>
    ),
  },
  {
    q: "What if my figure arrives damaged or not as described?",
    a: (
      <>
        Email us at {supportEmail} within 7 days of delivery, with your order code and photos of the
        figure and the packaging. We will work out a fix with you: a repair, a replacement or a
        refund. This does not limit your rights under consumer law where you live. Hand-painted
        figures can have tiny differences in colour and finish from the photos; that is part of
        being hand-painted and is not a fault.
      </>
    ),
  },
  {
    q: "What if my parcel is lost?",
    a: (
      <>
        We email you when your figure ships. The carrier and tracking number are on your order page.
        We insure every parcel we ship overseas, so if yours is lost or damaged in transit you do
        not bear the loss: contact us at {supportEmail} and choose a reship or a refund. For a
        made-to-order figure, a reship means we make you a new one. For an in-stock figure, we send
        a replacement from stock if we have one; if we do not, we refund you. This does not limit
        your rights under consumer law where you live.
      </>
    ),
  },
  {
    q: "Do you keep my details?",
    a: (
      <>
        Only what we need to make and ship your order. We remove your name, email, phone and address
        2 years after your order ends. Encrypted backups can keep removed data for up to about 60
        days longer. See our{" "}
        <Link href="/privacy" className={linkClass}>
          privacy page
        </Link>
        .
      </>
    ),
  },
  {
    q: "How do I track my order?",
    a: (
      <>
        Use the private link in your order emails. If you lost it,{" "}
        <Link href="/orders/lookup" className={linkClass}>
          look up your order
        </Link>{" "}
        with your order code and email and we will send a new link.
      </>
    ),
  },
  {
    q: "Can I commission a custom figure?",
    a: (
      <>
        Yes. See the{" "}
        <Link href="/commission" className={linkClass}>
          commission page
        </Link>{" "}
        for how it works, and read the full{" "}
        <Link href="/policy" className={linkClass}>
          terms &amp; policy
        </Link>{" "}
        for details.
      </>
    ),
  },
  {
    q: "Can you paint a kit I already own?",
    a: (
      <>
        Sometimes, by prior agreement. We decide case by case, so we cannot always take a kit on.
        This is not ordered through the website: message us on social media or email {supportEmail}.
        If we can paint it, we give you a quote for each piece. You pay shipping and customs both
        ways. The kit is at your risk on the way to us, and at ours on the way back. See{" "}
        <Link href="/commission#kit-painting" className={linkClass}>
          Painting your kit
        </Link>
        .
      </>
    ),
  },
];

export default function FaqPage() {
  return (
    <main
      id="main"
      className="mx-auto flex w-full max-w-2xl flex-1 flex-col gap-6 px-4 pt-8 pb-16 md:px-6 md:pt-10"
    >
      <h1 className="m-0 text-3xl font-extrabold md:text-[40px]">FAQ</h1>
      {FAQ.map(({ q, a }) => (
        <section key={q} className="flex flex-col gap-1">
          <h2 className="m-0 text-xl font-extrabold">{q}</h2>
          <p className="m-0">{a}</p>
        </section>
      ))}
    </main>
  );
}
