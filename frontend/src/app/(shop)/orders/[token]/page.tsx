import type { Metadata } from "next";
import { Suspense } from "react";
import { OrderView } from "./OrderView";

export const metadata: Metadata = {
  title: "Your order",
  robots: { index: false, follow: false },
  // The URL holds the secret token: never leak it through a Referer header.
  referrer: "no-referrer",
};

export default function OrderPage() {
  return (
    <main className="mx-auto flex w-full max-w-[1200px] flex-1 flex-col gap-6 px-4 pt-8 pb-16 md:px-6 md:pt-10">
      <Suspense fallback={<p className="font-bold text-muted">Loading your order…</p>}>
        <OrderView />
      </Suspense>
    </main>
  );
}
