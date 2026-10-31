import type { Metadata } from "next";
import { CartView } from "./CartView";

export const metadata: Metadata = {
  title: "Your cart",
  robots: { index: false, follow: false },
};

export default function CartPage() {
  return (
    <main className="mx-auto flex w-full max-w-[1200px] flex-1 flex-col gap-6 px-4 pt-8 pb-16 md:px-6 md:pt-10">
      <h1 className="m-0 text-4xl font-extrabold md:text-[44px]">Your cart</h1>
      <CartView />
    </main>
  );
}
