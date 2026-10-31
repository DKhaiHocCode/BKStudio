import type { Metadata } from "next";
import { Suspense } from "react";
import { CheckoutView } from "./CheckoutView";

export const metadata: Metadata = {
  title: "Checkout",
  robots: { index: false, follow: false },
  // Defence in depth: the secret order link is page content, not part of the URL, but never send a Referer from here.
  referrer: "no-referrer",
};

export default function CheckoutPage() {
  return (
    <main className="mx-auto flex w-full max-w-[1200px] flex-1 flex-col gap-6 px-4 pt-8 pb-16 md:px-6 md:pt-10">
      <Suspense fallback={<p className="font-bold text-muted">Loading…</p>}>
        <CheckoutView />
      </Suspense>
    </main>
  );
}
