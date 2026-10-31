"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { Notice } from "@/components/shop/Notice";
import { badge, focusRing, panel, primaryButton, secondaryButton } from "@/components/ui";
import { ApiError, apiFetch } from "@/lib/api";
import { formatUsd } from "@/lib/money";
import {
  PAYMENT_LABEL,
  TOKEN_PATTERN,
  formatDate,
  formatMonth,
  httpsUrl,
  orderSchema,
  statusLabel,
  waitText,
  type OrderView as Order,
} from "@/lib/orders";
import { CancelDialog } from "./CancelDialog";
import { ProgressSection } from "./ProgressSection";

type Load =
  | { kind: "loading" }
  | { kind: "ready"; order: Order }
  | { kind: "error"; status: number; retryAfter?: number; requestId?: string };

const h2 = "m-0 text-xl font-extrabold";
const row = "flex flex-wrap justify-between gap-x-4";
const CLOSED = new Set([
  "COMPLETED",
  "CANCELLED",
  "CANCELLED_DEPOSIT_KEPT",
  "CANCELLED_REFUNDED",
  "FORFEITED",
]);

/** Status-line text for a refresh that failed while an order is already on screen. */
function failureText(f: { status: number; retryAfter?: number; requestId?: string }): string {
  if (f.status === 404)
    return "This order link no longer works. Request a new link from the lookup page.";
  if (f.status === 429) return `Too many requests. Please try again in ${waitText(f.retryAfter)}.`;
  if (f.status === 0) return "We could not reach the server. Showing the last details we have.";
  return `Could not refresh. Showing the last details we have.${f.requestId ? ` Reference: ${f.requestId}` : ""}`;
}

/** The token in the URL is the customer's only key: never log it, never send it anywhere but our own API. */
export function OrderView() {
  const params = useParams<{ token: string }>();
  const token = typeof params.token === "string" ? params.token : "";
  const validToken = TOKEN_PATTERN.test(token);

  const [load, setLoad] = useState<Load>(
    validToken ? { kind: "loading" } : { kind: "error", status: 404 },
  );
  const [reloadKey, setReloadKey] = useState(0);
  const [progressKey, setProgressKey] = useState(0);
  const focusAfterLoad = useRef(false);
  const [refreshing, setRefreshing] = useState(false);
  const [notice, setNotice] = useState("");
  const heading = useRef<HTMLHeadingElement>(null);
  const dialog = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    if (!validToken) return;
    let stale = false;
    apiFetch<unknown>(`/api/orders/${token}`, { cache: "no-store" })
      .then((body): Load => {
        const parsed = orderSchema.safeParse(body);
        return parsed.success
          ? { kind: "ready", order: parsed.data }
          : { kind: "error", status: -1 };
      })
      .catch((e: unknown): Load =>
        e instanceof ApiError
          ? { kind: "error", status: e.status, retryAfter: e.retryAfter, requestId: e.requestId }
          : { kind: "error", status: -1 },
      )
      .then((result) => {
        if (stale) return;
        // A failed refresh must not wipe an order that is already on screen.
        setLoad((prev) => (result.kind === "error" && prev.kind === "ready" ? prev : result));
        if (result.kind === "error") setNotice(failureText(result));
        setRefreshing(false);
        if (focusAfterLoad.current) {
          focusAfterLoad.current = false;
          requestAnimationFrame(() => heading.current?.focus());
        }
      });
    return () => {
      stale = true;
    };
  }, [token, validToken, reloadKey]);

  // Move focus to the page heading (or the error title) once the first load settles.
  const settled = load.kind !== "loading";
  useEffect(() => {
    if (settled) heading.current?.focus();
  }, [settled]);

  function reload() {
    focusAfterLoad.current = load.kind === "error";
    setRefreshing(true);
    setNotice("");
    setReloadKey((k) => k + 1);
    setProgressKey((k) => k + 1);
  }

  if (load.kind === "loading") {
    return (
      <div role="status" className="flex flex-col gap-4" aria-live="polite">
        <p className="m-0 font-bold text-muted">Loading your order…</p>
        <div className={`${panel} min-h-64`} aria-hidden="true" />
        <div className={`${panel} min-h-40`} aria-hidden="true" />
      </div>
    );
  }

  if (load.kind === "error") return <LoadError load={load} heading={heading} onRetry={reload} />;

  const order = load.order;
  const isMto = order.fulfillmentType === "MADE_TO_ORDER";
  const month = formatMonth(order.estimatedProductionMonth);
  const payments = order.paymentRequests;
  const placed = formatDate(order.createdAt);

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div className="flex min-w-0 flex-col gap-1.5">
          {placed && <span className="font-bold text-muted">Placed {placed}</span>}
          <h1
            ref={heading}
            tabIndex={-1}
            className="m-0 text-3xl font-extrabold break-words outline-none md:text-[40px]"
          >
            Order {order.publicCode}
          </h1>
        </div>
        <span
          className={`${badge} text-ink ${CLOSED.has(order.status) ? "bg-tint-neutral" : "bg-tint-mto"}`}
        >
          {statusLabel(order.status)}
        </span>
      </div>

      <div className="flex flex-wrap items-center gap-3">
        <button type="button" onClick={reload} disabled={refreshing} className={secondaryButton}>
          {refreshing ? "Refreshing…" : "Refresh"}
        </button>
        <span role="status" className="font-bold">
          {refreshing ? "Refreshing…" : notice}
        </span>
      </div>

      {isMto && month && (
        <div className={`${badge} self-start bg-tint-mto text-ink`}>Production month: {month}</div>
      )}

      {isMto && <ProgressSection token={token} reloadKey={progressKey} />}

      {payments.length > 0 && (
        <section aria-labelledby="pay-h" className={`${panel} flex flex-col gap-3`}>
          <h2 id="pay-h" className={h2}>
            Payments due
          </h2>
          <p className="m-0 text-muted">
            Pay by hand on the shop&apos;s payment page. This site never takes card details.
          </p>
          <ul className="m-0 flex list-none flex-col gap-3 p-0">
            {payments.map((p, i) => {
              const href = httpsUrl(p.paymentUrl);
              const due = formatDate(p.dueAt);
              return (
                <li
                  key={`${p.type}-${i}`}
                  className="flex flex-wrap items-center justify-between gap-3 rounded-[14px] border-2 border-border p-3.5"
                >
                  <span className="flex min-w-0 flex-col">
                    <span className="font-bold">
                      {PAYMENT_LABEL[p.type] ?? "Payment"} · {formatUsd(p.amountCents)}
                    </span>
                    {due && <span className="text-sm text-muted">Pay by {due}</span>}
                  </span>
                  {href ? (
                    <a
                      href={href}
                      target="_blank"
                      rel="noreferrer noopener"
                      className={primaryButton}
                    >
                      Pay now<span className="sr-only"> (opens in a new tab)</span>
                    </a>
                  ) : (
                    <span className="text-sm font-bold text-muted">
                      Payment link unavailable: contact the shop.
                    </span>
                  )}
                </li>
              );
            })}
          </ul>
        </section>
      )}

      <section aria-labelledby="items-h" className={`${panel} flex flex-col gap-4`}>
        <h2 id="items-h" className={h2}>
          Your {order.items.length === 1 ? "figure" : "figures"}
        </h2>
        <ul className="m-0 flex list-none flex-col gap-3 p-0">
          {order.items.map((item, i) => (
            <li key={i} className="flex items-center gap-3.5">
              <div
                aria-hidden="true"
                className="size-16 flex-none rounded-[14px] border-[3px] border-border bg-capybara"
              />
              <div className="flex min-w-0 flex-1 flex-col">
                <span className="font-extrabold break-words">{item.productName}</span>
                <span className="text-sm text-muted">
                  {item.variantLabel ? `${item.variantLabel} · ` : ""}Qty {item.quantity}
                </span>
              </div>
              <span className="text-right font-bold">
                {formatUsd(item.unitPriceCents)}
                <span className="block text-xs font-normal text-muted">each</span>
              </span>
            </li>
          ))}
        </ul>
        <dl className="m-0 flex flex-col gap-2 border-t-2 border-dashed border-line pt-3.5">
          <div className={row}>
            <dt>Subtotal</dt>
            <dd className="m-0 font-bold">{formatUsd(order.subtotalCents)}</dd>
          </div>
          {order.depositCents > 0 && (
            <div className={row}>
              <dt>Deposit (50%)</dt>
              <dd className="m-0 font-bold">{formatUsd(order.depositCents)}</dd>
            </div>
          )}
          <div className={row}>
            <dt>Shipping</dt>
            <dd className="m-0 font-bold">
              {order.shippingCents > 0 ? formatUsd(order.shippingCents) : "To be confirmed"}
            </dd>
          </div>
        </dl>
      </section>

      {(order.carrier || order.trackingNumber) && (
        <section aria-labelledby="ship-h" className={`${panel} flex flex-col gap-2`}>
          <h2 id="ship-h" className={h2}>
            Shipping
          </h2>
          <dl className="m-0 flex flex-col gap-2">
            {order.carrier && (
              <div className={row}>
                <dt>Carrier</dt>
                <dd className="m-0 font-bold break-words">{order.carrier}</dd>
              </div>
            )}
            {order.trackingNumber && (
              <div className={row}>
                <dt>Tracking number</dt>
                <dd className="m-0 font-mono font-bold break-all">{order.trackingNumber}</dd>
              </div>
            )}
          </dl>
        </section>
      )}

      <section aria-labelledby="hist-h" className={`${panel} flex flex-col gap-3`}>
        <h2 id="hist-h" className={h2}>
          History
        </h2>
        <ol className="m-0 flex list-none flex-col gap-2 p-0">
          <li className={row}>
            <span className="font-bold">Order placed</span>
            <span className="text-muted">{placed}</span>
          </li>
          {order.history.map((h, i) => (
            <li key={i} className={row}>
              <span className="font-bold">{statusLabel(h.status)}</span>
              <span className="text-muted">{formatDate(h.at)}</span>
            </li>
          ))}
        </ol>
      </section>

      {order.canCancel && (
        <section aria-labelledby="cancel-sec-h" className={`${panel} flex flex-col gap-3`}>
          <h2 id="cancel-sec-h" className={h2}>
            Need to cancel?
          </h2>
          <p className="m-0">
            {order.cancelWarning
              ? "You can still cancel, but your deposit will not be refunded."
              : `You can cancel for free until your payment is confirmed.${isMto ? " Once your deposit is confirmed, cancelling forfeits it." : ""}`}
          </p>
          <button
            type="button"
            onClick={() => dialog.current?.showModal()}
            className={`${secondaryButton} self-start border-danger text-danger`}
          >
            Cancel order…
          </button>
        </section>
      )}

      <CancelDialog
        ref={dialog}
        token={token}
        cancelWarning={order.cancelWarning}
        onCancelled={(updated) => {
          setLoad({ kind: "ready", order: updated });
          setNotice("Your order has been cancelled.");
          setProgressKey((k) => k + 1); // progress follows the status; the order is already fresh
          requestAnimationFrame(() => heading.current?.focus());
        }}
        onClosed={() =>
          // The opener may be gone (order moved on): do not leave focus on <body>.
          requestAnimationFrame(() => {
            if (document.activeElement === document.body) heading.current?.focus();
          })
        }
        onReload={reload}
      />

      <p className="m-0 text-sm text-muted">
        Questions? Contact the shop and quote your order code {order.publicCode}.
      </p>
    </div>
  );
}

function LoadError({
  load,
  heading,
  onRetry,
}: {
  load: Extract<Load, { kind: "error" }>;
  heading: React.RefObject<HTMLHeadingElement | null>;
  onRetry: () => void;
}) {
  const notFound = load.status === 404;
  const limited = load.status === 429;
  return (
    <div className="mx-auto flex w-full max-w-2xl flex-col gap-4">
      <h1
        ref={heading}
        tabIndex={-1}
        className="m-0 text-3xl font-extrabold outline-none md:text-4xl"
      >
        {notFound
          ? "We couldn't find this order"
          : limited
            ? "Please wait a moment"
            : "We couldn't load your order"}
      </h1>
      <div role="alert" className="flex flex-col gap-4">
        {notFound && (
          <>
            <p className="m-0">
              This link may be mistyped, or it may have been replaced: asking for a new link by
              email makes the old one stop working, so an older email will lead here.
            </p>
            <Link href="/orders/lookup" className={`${primaryButton} self-start`}>
              Request a new link
            </Link>
          </>
        )}
        {limited && (
          <>
            <p className="m-0">
              Too many requests from your network. Please try again in {waitText(load.retryAfter)}.
            </p>
            <button type="button" onClick={onRetry} className={`${secondaryButton} self-start`}>
              Try again
            </button>
          </>
        )}
        {!notFound && !limited && (
          <>
            <Notice tone="error">
              <p className="m-0 font-bold">
                {load.status === 0
                  ? "We could not reach the server."
                  : "Something went wrong on our side."}{" "}
                Please try again.
              </p>
              {load.requestId && <p className="m-0 text-sm">Reference: {load.requestId}</p>}
            </Notice>
            <button type="button" onClick={onRetry} className={`${primaryButton} self-start`}>
              Try again
            </button>
          </>
        )}
      </div>
      <Link href="/" className={`self-start font-bold ${focusRing}`}>
        ← Back to the shop
      </Link>
    </div>
  );
}
