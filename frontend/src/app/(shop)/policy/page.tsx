import type { Metadata } from "next";
import { PolicyView } from "./PolicyView";

export const metadata: Metadata = {
  title: "Terms & policy",
  description: "BKStudio terms: deposits, cancellations, production queue and shipping.",
};

export default function PolicyPage() {
  return (
    <main
      id="main"
      className="mx-auto flex w-full max-w-2xl flex-1 flex-col gap-2 px-4 pt-8 pb-16 md:px-6 md:pt-10"
    >
      <h1 className="m-0 text-3xl font-extrabold md:text-[40px]">Terms &amp; policy</h1>
      <PolicyView />
    </main>
  );
}
