import type { Metadata } from "next";
import Link from "next/link";
import type { ReactNode } from "react";
import { focusRing } from "@/components/ui";

export const metadata: Metadata = {
  title: "FAQ",
  description: "Answers about in-stock and made-to-order figures, deposits, payment and shipping.",
};

const linkClass = `font-bold underline hover:text-danger ${focusRing}`;

// TODO(owner): confirm shipping wording and the balance payment platform (Artistree vs Shopee +
// proxy service is not settled yet, REQUIREMENTS.md section 1).
const FAQ: { q: string; a: ReactNode }[] = [
  {
    q: "What is the difference between in-stock and made-to-order?",
    a: "In-stock figures are already finished and ship soon after you pay in full. Made-to-order figures are printed and hand-painted for you after you order, so there is a wait.",
  },
  {
    q: "How does the deposit work?",
    a: "Made-to-order figures need a 50% deposit when you order. You pay the remaining 50% when your figure is finished. In-stock figures are paid in full in one payment.",
  },
  {
    q: "What is the monthly queue?",
    a: "We make a limited number of figures each month and every figure takes one slot. The start month shown before you order is an estimate; we confirm the official month once your deposit is received. If a month is full you can still order and join the queue, with a longer wait.",
  },
  {
    q: "How do I pay?",
    a: "After you order, we email you a payment link (Artistree or Shopee). This website never takes card details. Deposit and in-stock payment links expire 48 hours after we send them; the balance link gives you 30 days.",
  },
  {
    q: "How long do I have to pay the balance?",
    a: "30 days after we send the balance payment link, once your figure is finished. We send reminders 7 days and 1 day before the deadline.",
  },
  {
    q: "What does shipping cost?",
    a: "Shipping is paid by the customer. We tell you the amount before you pay, and send tracking details when your order ships.",
  },
  {
    q: "Can I cancel my order?",
    a: "The deposit is non-refundable if you cancel a made-to-order figure. If we cancel (for example we cannot make it, a print fails, or we stop taking orders), you get your deposit back in full. If the deposit or in-stock payment deadline passes, the order is cancelled and nothing is charged. If the balance deadline passes, the order is forfeited and the deposit is kept.",
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
