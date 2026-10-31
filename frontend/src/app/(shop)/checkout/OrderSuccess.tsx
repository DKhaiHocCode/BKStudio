"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { Notice } from "@/components/shop/Notice";
import { focusRing, inputField, panel, secondaryButton } from "@/components/ui";
import { formatUsd } from "@/lib/money";
import type { OrderCreated } from "./schema";

const STATUS_LABEL: Record<string, string> = {
  AWAITING_PAYMENT: "Awaiting payment",
  AWAITING_DEPOSIT: "Awaiting deposit",
};

/** Never log or send `order.code` / `order.orderUrl` anywhere: the URL is the customer's only key. */
export function OrderSuccess({ order }: { order: OrderCreated }) {
  const heading = useRef<HTMLHeadingElement>(null);
  const [copy, setCopy] = useState<"idle" | "copied" | "failed">("idle");

  useEffect(() => heading.current?.focus(), []);

  async function copyLink() {
    if (!order.orderUrl) return;
    try {
      await navigator.clipboard.writeText(order.orderUrl);
      setCopy("copied");
    } catch {
      setCopy("failed");
    }
  }

  return (
    <div className={`${panel} mx-auto flex w-full max-w-2xl flex-col gap-5`}>
      <h1
        ref={heading}
        tabIndex={-1}
        className="m-0 text-3xl font-extrabold outline-none md:text-4xl"
      >
        Thank you, your order is placed
      </h1>

      <dl className="m-0 flex flex-col gap-2">
        <div className="flex flex-wrap justify-between gap-x-4">
          <dt>Order code</dt>
          <dd className="m-0 font-mono font-bold">{order.code}</dd>
        </div>
        <div className="flex flex-wrap justify-between gap-x-4">
          <dt>Status</dt>
          <dd className="m-0 font-bold">{STATUS_LABEL[order.status] ?? order.status}</dd>
        </div>
        <div className="flex flex-wrap justify-between gap-x-4">
          <dt>Subtotal</dt>
          <dd className="m-0 font-bold">{formatUsd(order.subtotalCents)}</dd>
        </div>
        {order.depositCents > 0 && (
          <div className="flex flex-wrap justify-between gap-x-4">
            <dt>Deposit (50%)</dt>
            <dd className="m-0 font-bold">{formatUsd(order.depositCents)}</dd>
          </div>
        )}
      </dl>

      {order.orderUrl ? (
        <div className="flex flex-col gap-3">
          <Notice tone="warn">
            <p className="m-0 font-bold">Save this link now.</p>
            <p className="m-0">
              Keep this link safe: anyone with it can view your order. It is shown just once here,
              and a confirmation email with the same link is on its way. If you lose it, you can
              request a new link with your order code and email (the order lookup page is coming
              later).
            </p>
          </Notice>
          <label htmlFor="order-url" className="font-bold">
            Your private order link
          </label>
          <input
            id="order-url"
            readOnly
            value={order.orderUrl}
            onFocus={(e) => e.currentTarget.select()}
            className={`${inputField} font-mono text-sm`}
          />
          <div className="flex flex-wrap items-center gap-3">
            <button type="button" onClick={() => void copyLink()} className={secondaryButton}>
              Copy link
            </button>
            <span role="status" className="font-bold">
              {copy === "copied" && "Copied"}
              {copy === "failed" && "Could not copy: select the link above and copy it."}
            </span>
          </div>
        </div>
      ) : (
        <Notice tone="info">
          <p className="m-0">
            This order was already placed with the details you first entered. Use the link in your
            confirmation email to see it (check your spam folder too). If anything was changed
            since, please contact the shop to update it: new details sent now are ignored.
          </p>
        </Notice>
      )}

      <p className="m-0">
        Nothing has been charged. The shop will email you a secure payment link (Artistree or
        Shopee) to pay by hand: there are no card details on this site.
      </p>

      <Link href="/" className={`self-start font-bold ${focusRing}`}>
        ← Back to the shop
      </Link>
    </div>
  );
}
