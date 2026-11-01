"use client";

import { useRef, useState, type FormEvent } from "react";
import { ApiError, apiFetch } from "@/lib/api";
import { STALE_CODES, cancelPaymentSchema, confirmPaymentSchema } from "@/lib/adminOrders";
import { buttonClass, dangerButtonClass, inputClass, secondaryButtonClass } from "../../../ui";
import { ErrorBanner } from "../../products/ErrorBanner";
import { Field } from "../../products/Field";

/** Confirm / cancel buttons for one PENDING payment request. No version is sent (API_SPEC). */
export function PaymentRequestActions({
  requestId,
  onDone,
  onStale,
}: {
  requestId: number;
  onDone: (message: string) => void;
  onStale: (error: unknown) => void;
}) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const busyRef = useRef(false);
  const [busy, setBusy] = useState(false);
  const [kind, setKind] = useState<"confirm" | "cancel" | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [invalid, setInvalid] = useState<string>();

  function open(k: "confirm" | "cancel") {
    setKind(k);
    setError(null);
    setInvalid(undefined);
    dialogRef.current?.showModal();
  }

  async function submit(ev: FormEvent<HTMLFormElement>) {
    ev.preventDefault();
    if (!kind || busyRef.current) return;
    const raw = String(new FormData(ev.currentTarget).get("value") ?? "");
    const parsed =
      kind === "confirm"
        ? confirmPaymentSchema.safeParse({ externalRef: raw })
        : cancelPaymentSchema.safeParse({ reason: raw });
    if (!parsed.success) {
      setInvalid(parsed.error.issues[0]?.message);
      return;
    }
    setInvalid(undefined);
    busyRef.current = true;
    setBusy(true);
    setError(null);
    try {
      await apiFetch(`/api/admin/payment-requests/${requestId}/${kind}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(parsed.data),
      });
      dialogRef.current?.close();
      onDone(kind === "confirm" ? "Payment confirmed." : "Payment request cancelled.");
    } catch (e) {
      if (e instanceof ApiError && e.status === 409 && STALE_CODES.includes(e.code)) {
        dialogRef.current?.close();
        onStale(e);
      } else {
        setError(e);
      }
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  }

  const serverField =
    error instanceof ApiError
      ? error.fieldErrors.find((f) => f.field === "externalRef" || f.field === "reason")?.message
      : undefined;
  const title = kind === "confirm" ? "Confirm payment" : "Cancel request";

  return (
    <>
      <div className="mt-2 flex flex-wrap gap-3">
        <button type="button" onClick={() => open("confirm")} className={buttonClass}>
          Confirm payment
        </button>
        <button type="button" onClick={() => open("cancel")} className={dangerButtonClass}>
          Cancel request
        </button>
      </div>
      <dialog
        ref={dialogRef}
        aria-labelledby={`pr-dialog-${requestId}`}
        onCancel={(e) => {
          if (busyRef.current) e.preventDefault();
        }}
        onClose={() => {
          setKind(null);
          setError(null);
          setInvalid(undefined);
        }}
        className="m-auto max-h-[calc(100dvh-32px)] w-[calc(100%-32px)] max-w-lg overflow-y-auto rounded-[22px] border-[3px] border-border bg-card p-0 text-fg shadow-sticker backdrop:bg-black/60"
      >
        {kind && (
          <form
            onSubmit={(ev) => void submit(ev)}
            noValidate
            className="flex flex-col gap-4 p-5 sm:p-6"
          >
            <h2 id={`pr-dialog-${requestId}`} className="m-0 text-2xl font-extrabold">
              {title}
            </h2>
            <Field
              label={kind === "confirm" ? "Payment reference" : "Reason"}
              error={invalid ?? serverField}
              hint={
                kind === "confirm"
                  ? "Transaction or order reference from Artistree / Shopee."
                  : "Stored in the audit log. Do not include customer details."
              }
            >
              {kind === "confirm" ? (
                <input
                  name="value"
                  maxLength={100}
                  aria-invalid={Boolean(invalid ?? serverField)}
                  className={inputClass}
                />
              ) : (
                <textarea
                  name="value"
                  rows={3}
                  maxLength={500}
                  aria-invalid={Boolean(invalid ?? serverField)}
                  className={`${inputClass} py-2`}
                />
              )}
            </Field>
            <ErrorBanner error={error} />
            <div className="flex flex-wrap gap-3">
              <button
                type="submit"
                disabled={busy}
                className={kind === "cancel" ? dangerButtonClass : buttonClass}
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
          </form>
        )}
      </dialog>
    </>
  );
}
