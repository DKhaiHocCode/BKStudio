"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useState } from "react";
import { Notice } from "@/components/shop/Notice";
import { focusRing, primaryButton } from "@/components/ui";
import { removeLines, useCart } from "@/lib/cart";
import { CheckoutForm } from "./CheckoutForm";
import { clearIdempotencyKey } from "./idempotency";
import { OrderSuccess } from "./OrderSuccess";
import type { OrderCreated } from "./schema";

export function CheckoutView() {
  const type = useSearchParams().get("type");
  const mode = type === "stock" || type === "mto" ? type : null;
  const { lines, ready } = useCart();
  const [placed, setPlaced] = useState<OrderCreated | null>(null);

  // The success screen wins over the (now emptied) cart.
  if (placed) return <OrderSuccess order={placed} />;
  if (!ready) return <p className="font-bold text-muted">Loading your cart…</p>;

  const blockLines = lines.filter((l) => l.mode === mode);
  if (!mode || blockLines.length === 0) {
    return (
      <div className="flex flex-col items-start gap-4">
        <Notice tone="warn">
          <p className="m-0 font-bold">There is nothing to check out here.</p>
          <p className="m-0">
            Choose what to check out from your cart: ready-to-ship and made-to-order figures are
            ordered separately.
          </p>
        </Notice>
        <Link href="/cart" className={primaryButton}>
          Go to your cart
        </Link>
      </div>
    );
  }

  return (
    <>
      <div className="flex flex-col gap-1.5">
        <Link href="/cart" className={`self-start text-[15px] font-bold ${focusRing}`}>
          ← Back to cart
        </Link>
        <h1 className="m-0 text-4xl font-extrabold md:text-[40px]">
          Checkout · {mode === "mto" ? "made to order" : "ready to ship"}
        </h1>
        <p className="m-0 font-bold text-muted">Secure checkout. No card details here.</p>
      </div>
      <CheckoutForm
        mode={mode}
        lines={blockLines}
        onPlaced={(order) => {
          setPlaced(order);
          clearIdempotencyKey(mode);
          removeLines(blockLines); // only the submitted lines leave the cart
        }}
      />
    </>
  );
}
