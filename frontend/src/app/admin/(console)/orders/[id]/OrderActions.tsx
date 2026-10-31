"use client";

import { useRef, useState, type FormEvent } from "react";
import { ApiError, apiFetch } from "@/lib/api";
import { STALE_CODES, cancelSchema, shipSchema, type AdminOrder } from "@/lib/adminOrders";
import {
  alertClass,
  buttonClass,
  dangerButtonClass,
  inputClass,
  secondaryButtonClass,
} from "../../../ui";
import { ErrorBanner } from "../../products/ErrorBanner";
import { Field } from "../../products/Field";

type DialogKind = "complete" | "forfeited-to-stock" | "ship" | "cancel-by-shop";

const LABEL: Record<string, string> = {
  "start-production": "Start production",
  "mark-finished": "Mark finished",
  complete: "Complete order",
  ship: "Ship order",
  "cancel-by-shop": "Cancel order",
  "forfeited-to-stock": "Return to stock",
};
const DONE: Record<string, string> = {
  "start-production": "Production started.",
  "mark-finished": "Marked as finished.",
  complete: "Order completed.",
  ship: "Order shipped.",
  "cancel-by-shop": "Order cancelled.",
  "forfeited-to-stock": "Returned to stock.",
};
const DIALOG_KINDS: readonly string[] = [
  "complete",
  "forfeited-to-stock",
  "ship",
  "cancel-by-shop",
];

/** Buttons come ONLY from `allowedActions`; every POST carries the version we last read. */
export function OrderActions({
  order,
  onDone,
  onStale,
}: {
  order: AdminOrder;
  onDone: (message: string) => void;
  onStale: (error: unknown) => void;
}) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const busyRef = useRef(false); // blocks a second click before React re-renders
  const [busy, setBusy] = useState(false);
  const [kind, setKind] = useState<DialogKind | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  /** Returns true when the request succeeded. Stale conflicts are handed to the parent. */
  async function run(action: string, extra: Record<string, string> = {}): Promise<boolean> {
    if (busyRef.current) return false;
    busyRef.current = true;
    setBusy(true);
    setError(null);
    try {
      await apiFetch(`/api/admin/orders/${order.id}/${action}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ version: order.version, ...extra }),
      });
      onDone(DONE[action] ?? "Done.");
      return true;
    } catch (e) {
      if (e instanceof ApiError && e.status === 409 && STALE_CODES.includes(e.code)) {
        dialogRef.current?.close();
        onStale(e);
      } else {
        setError(e);
      }
      return false;
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  }

  function open(k: DialogKind) {
    setKind(k);
    setError(null);
    setFieldErrors({});
    dialogRef.current?.showModal();
  }

  async function submit(ev: FormEvent<HTMLFormElement>) {
    ev.preventDefault();
    if (!kind) return;
    const form = new FormData(ev.currentTarget);
    const text = (name: string) => String(form.get(name) ?? "");
    let extra: Record<string, string> = {};
    if (kind === "ship" || kind === "cancel-by-shop") {
      const parsed =
        kind === "ship"
          ? shipSchema.safeParse({
              carrier: text("carrier"),
              trackingNumber: text("trackingNumber"),
            })
          : cancelSchema.safeParse({ reason: text("reason"), refundRef: text("refundRef") });
      if (!parsed.success) {
        const next: Record<string, string> = {};
        for (const issue of parsed.error.issues) next[String(issue.path[0])] ??= issue.message;
        setFieldErrors(next);
        return;
      }
      extra = parsed.data;
      if (kind === "cancel-by-shop" && !extra.refundRef) delete extra.refundRef;
    }
    setFieldErrors({});
    if (await run(kind, extra)) dialogRef.current?.close();
  }

  const direct = order.allowedActions.filter((a) => a in LABEL && !DIALOG_KINDS.includes(a));
  const dialogs = order.allowedActions.filter((a): a is DialogKind => DIALOG_KINDS.includes(a));
  // Server-side field errors (400) show under the matching input.
  const serverFields =
    error instanceof ApiError
      ? Object.fromEntries(error.fieldErrors.map((f) => [f.field, f.message]))
      : {};
  const fieldError = (name: string) => fieldErrors[name] ?? serverFields[name];

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap gap-3">
        {direct.map((a) => (
          <button
            key={a}
            type="button"
            disabled={busy}
            onClick={() => void run(a)}
            className={buttonClass}
          >
            {LABEL[a]}
          </button>
        ))}
        {dialogs.map((a) => (
          <button
            key={a}
            type="button"
            disabled={busy}
            onClick={() => open(a)}
            className={a === "cancel-by-shop" ? dangerButtonClass : secondaryButtonClass}
          >
            {LABEL[a]}
          </button>
        ))}
        {/* "create-payment-request" is rendered by PaymentRequestForm, not here. */}
        {order.allowedActions.length === 0 && (
          <p className="text-sm text-muted">No actions available for this order.</p>
        )}
      </div>
      {/* Errors of direct actions; dialog errors show inside the dialog. */}
      {!kind && <ErrorBanner error={error} />}

      <dialog
        ref={dialogRef}
        aria-labelledby="order-action-h"
        onCancel={(e) => {
          if (busyRef.current) e.preventDefault();
        }}
        onClose={() => {
          setKind(null);
          setError(null);
          setFieldErrors({});
        }}
        className="m-auto max-h-[calc(100dvh-32px)] w-[calc(100%-32px)] max-w-lg overflow-y-auto rounded-[22px] border-[3px] border-border bg-card p-0 text-fg shadow-sticker backdrop:bg-black/60"
      >
        {kind && (
          <form
            onSubmit={(ev) => void submit(ev)}
            noValidate
            className="flex flex-col gap-4 p-5 sm:p-6"
          >
            <h2 id="order-action-h" className="m-0 text-2xl font-extrabold">
              {LABEL[kind]}
            </h2>
            {kind === "complete" && <p className="m-0">Mark this order as completed?</p>}
            {kind === "forfeited-to-stock" && (
              <p className="m-0">
                Add the items of this forfeited order back to stock? This can only be done once.
              </p>
            )}
            {kind === "ship" && (
              <>
                <Field label="Carrier" error={fieldError("carrier")}>
                  <input
                    name="carrier"
                    maxLength={50}
                    aria-invalid={Boolean(fieldError("carrier"))}
                    className={inputClass}
                  />
                </Field>
                <Field label="Tracking number" error={fieldError("trackingNumber")}>
                  <input
                    name="trackingNumber"
                    maxLength={100}
                    aria-invalid={Boolean(fieldError("trackingNumber"))}
                    className={inputClass}
                  />
                </Field>
              </>
            )}
            {kind === "cancel-by-shop" && (
              <>
                <Field
                  label="Reason"
                  error={fieldError("reason")}
                  hint="Stored in the audit log. Do not include customer details."
                >
                  <textarea
                    name="reason"
                    rows={3}
                    maxLength={500}
                    aria-invalid={Boolean(fieldError("reason"))}
                    className={`${inputClass} py-2`}
                  />
                </Field>
                <Field
                  label="Refund reference (optional)"
                  error={fieldError("refundRef")}
                  hint="Required by the server when the customer has already paid."
                >
                  <input
                    name="refundRef"
                    maxLength={100}
                    aria-invalid={Boolean(fieldError("refundRef"))}
                    className={inputClass}
                  />
                </Field>
              </>
            )}
            {error ? <ErrorBanner error={error} /> : null}
            <div className="flex flex-wrap gap-3">
              <button
                type="submit"
                disabled={busy}
                className={kind === "cancel-by-shop" ? dangerButtonClass : buttonClass}
              >
                {busy ? "Working..." : "Confirm"}
              </button>
              <button
                type="button"
                autoFocus
                disabled={busy}
                onClick={() => dialogRef.current?.close()}
                className={secondaryButtonClass}
              >
                Close
              </button>
            </div>
            {kind === "cancel-by-shop" && (
              <p className={`${alertClass} m-0`}>Cancelling cannot be undone.</p>
            )}
          </form>
        )}
      </dialog>
    </div>
  );
}
