"use client";

import { useRef, useState, type FormEvent } from "react";
import { ApiError, apiFetch } from "@/lib/api";
import {
  PAYMENT_CHANNELS,
  STALE_CODES,
  estimatePaymentCents,
  expectedPaymentType,
  paymentUrlSchema,
  usdToCents,
  type AdminOrder,
} from "@/lib/adminOrders";
import { formatUsd } from "@/lib/money";
import { buttonClass, inputClass } from "../../../ui";
import { ErrorBanner } from "../../products/ErrorBanner";
import { Field } from "../../products/Field";

/** Shown only when `allowedActions` has `create-payment-request`; the type is fixed by the order state. */
export function PaymentRequestForm({
  order,
  onDone,
  onStale,
}: {
  order: AdminOrder;
  onDone: (message: string) => void;
  onStale: (error: unknown) => void;
}) {
  const busyRef = useRef(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [channel, setChannel] = useState<string>(PAYMENT_CHANNELS[0]);
  const [shipping, setShipping] = useState(String(order.shippingCents / 100));

  const type = expectedPaymentType(order);
  if (!order.allowedActions.includes("create-payment-request") || !type) return null;
  if (order.paymentRequests.some((p) => p.status === "PENDING" && p.type === type)) {
    return (
      <p className="m-0 border-t-2 border-line pt-3 text-muted">
        A pending {type} payment request already exists. Confirm or cancel it first.
      </p>
    );
  }

  // Server allows shipping > 0 only for ARTISTREE with FULL/BALANCE.
  const shippingAllowed = type !== "DEPOSIT" && channel === "ARTISTREE";
  const shippingCents = shippingAllowed ? usdToCents(shipping) : 0;
  const estimate =
    shippingCents === null ? null : estimatePaymentCents(type, order.subtotalCents, shippingCents);

  const serverFields =
    error instanceof ApiError
      ? Object.fromEntries(error.fieldErrors.map((f) => [f.field, f.message]))
      : {};
  const fieldError = (name: string) => fieldErrors[name] ?? serverFields[name];

  async function submit(ev: FormEvent<HTMLFormElement>) {
    ev.preventDefault();
    if (busyRef.current || !type) return;
    const url = paymentUrlSchema.safeParse(
      String(new FormData(ev.currentTarget).get("paymentUrl") ?? ""),
    );
    const next: Record<string, string> = {};
    if (!url.success) next.paymentUrl = url.error.issues[0]?.message ?? "Invalid link.";
    if (shippingCents === null) next.shipping = "Enter an amount like 12.50.";
    if (!url.success || shippingCents === null || estimate === null) {
      setFieldErrors(next);
      return;
    }
    setFieldErrors({});
    busyRef.current = true;
    setBusy(true);
    setError(null);
    try {
      await apiFetch(`/api/admin/orders/${order.id}/payment-requests`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          type,
          amountCents: estimate,
          shippingCents,
          channel,
          paymentUrl: url.data,
        }),
      });
      onDone("Payment request created.");
    } catch (e) {
      if (e instanceof ApiError && e.status === 409 && STALE_CODES.includes(e.code)) {
        onStale(e);
      } else {
        setError(e);
      }
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  }

  return (
    <form
      onSubmit={(ev) => void submit(ev)}
      noValidate
      className="flex flex-col gap-3 border-t-2 border-line pt-3"
    >
      <h3 className="m-0 text-lg font-extrabold">Create payment request ({type})</h3>
      <Field label="Channel">
        <select
          name="channel"
          value={channel}
          onChange={(e) => setChannel(e.target.value)}
          className={inputClass}
        >
          {PAYMENT_CHANNELS.map((c) => (
            <option key={c} value={c}>
              {c}
            </option>
          ))}
        </select>
      </Field>
      <Field label="Payment link" error={fieldError("paymentUrl")} hint="https:// link only.">
        <input
          name="paymentUrl"
          type="url"
          maxLength={500}
          aria-invalid={Boolean(fieldError("paymentUrl"))}
          className={inputClass}
        />
      </Field>
      {shippingAllowed && (
        <Field label="Shipping (USD)" error={fieldError("shipping") ?? fieldError("shippingCents")}>
          <input
            name="shipping"
            inputMode="decimal"
            value={shipping}
            onChange={(e) => setShipping(e.target.value)}
            aria-invalid={Boolean(fieldError("shipping") ?? fieldError("shippingCents"))}
            className={inputClass}
          />
        </Field>
      )}
      <p className="m-0">
        Estimated amount: <strong>{estimate === null ? "-" : formatUsd(estimate)}</strong>{" "}
        <span className="text-sm text-muted">(the server verifies the amount and wins)</span>
      </p>
      {fieldError("amountCents") && (
        <p role="alert" className="m-0 text-sm font-bold text-danger">
          {fieldError("amountCents")}
        </p>
      )}
      <ErrorBanner error={error} />
      <div>
        <button type="submit" disabled={busy} className={buttonClass}>
          {busy ? "Creating..." : "Create payment request"}
        </button>
      </div>
    </form>
  );
}
