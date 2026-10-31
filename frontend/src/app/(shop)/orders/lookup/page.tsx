import type { Metadata } from "next";
import { LookupForm } from "./LookupForm";

export const metadata: Metadata = {
  title: "Find your order",
  robots: { index: false, follow: false },
  referrer: "no-referrer",
};

export default function LookupPage() {
  return (
    <main className="mx-auto flex w-full max-w-2xl flex-1 flex-col gap-6 px-4 pt-8 pb-16 md:px-6 md:pt-10">
      <div className="flex flex-col gap-2">
        <h1 className="m-0 text-3xl font-extrabold md:text-[40px]">Find your order</h1>
        <p className="m-0 text-muted">
          Lost your order link? Enter your order code and the email you ordered with. If they match,
          we email you a new link. We never show order details on this page.
        </p>
      </div>
      <LookupForm />
    </main>
  );
}
