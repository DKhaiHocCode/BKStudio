"use client";

import Link from "next/link";
import { useRef, useState, type RefObject } from "react";
import { Notice } from "@/components/shop/Notice";
import { primaryButton, secondaryButton } from "@/components/ui";
import { ApiError, apiFetch } from "@/lib/api";
import { orderSchema, waitText, type OrderView } from "@/lib/orders";

interface Problem {
  text: string;
  reference?: string;
  /** The order changed under us: offer a reload instead of a retry. */
  stale?: boolean;
  /** Cancelling online is no longer possible: only Close is left. */
  final?: boolean;
  /** Show a link to the lookup page. */
  lookup?: boolean;
}

function explain(e: unknown): Problem {
  if (!(e instanceof ApiError)) return { text: "Something went wrong. Please try again." };
  if (e.status === 0) {
    return { text: "We could not reach the server. Nothing was cancelled: please try again." };
  }
  if (e.code === "INVALID_TRANSITION") {
    return {
      text: "This order has moved on and can no longer be cancelled here. Please contact the shop.",
      final: true,
    };
  }
  if (e.status === 404) {
    return { text: "This link is no longer valid.", final: true, lookup: true };
  }
  if (e.code === "CONFLICT" || e.code === "VERSION_CONFLICT") {
    return { text: "This order was just updated. Reload it to see the latest.", stale: true };
  }
  if (e.status === 429) {
    return { text: `Too many attempts. Please try again in ${waitText(e.retryAfter)}.` };
  }
  if (e.status === 400) {
    return {
      text: "The order changed, so we could not cancel it. Reload it and check the details.",
      stale: true,
    };
  }
  return { text: "Something went wrong on our side. Please try again.", reference: e.requestId };
}

/**
 * Native <dialog>: showModal() gives the focus trap, Escape and focus return to the opener.
 * The parent opens it with `ref.current?.showModal()`.
 */
export function CancelDialog({
  ref,
  token,
  cancelWarning,
  onCancelled,
  onReload,
  onClosed,
}: {
  ref: RefObject<HTMLDialogElement | null>;
  token: string;
  cancelWarning: boolean;
  onCancelled: (order: OrderView) => void;
  onReload: () => void;
  onClosed: () => void;
}) {
  const [acknowledged, setAcknowledged] = useState(false);
  const [problem, setProblem] = useState<Problem | null>(null);
  const [sending, setSending] = useState(false);
  const sendingRef = useRef(false); // blocks a second submit before React re-renders

  function close() {
    ref.current?.close();
  }

  async function submit() {
    if (sendingRef.current || (cancelWarning && !acknowledged)) return;
    sendingRef.current = true;
    setSending(true);
    setProblem(null);
    try {
      const body = await apiFetch<unknown>(`/api/orders/${token}/cancel`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        // Only ever send true after the explicit tick; otherwise the flag is absent.
        body: JSON.stringify(cancelWarning ? { acknowledgeDepositLost: true } : {}),
      });
      const parsed = orderSchema.safeParse(body);
      if (parsed.success) onCancelled(parsed.data);
      else onReload(); // cancelled but the reply is unreadable: fetch the order again
      close();
    } catch (e) {
      const next = explain(e);
      setProblem(next);
      if (next.final) onReload();
    } finally {
      sendingRef.current = false;
      setSending(false);
    }
  }

  return (
    <dialog
      ref={ref}
      aria-labelledby="cancel-h"
      onCancel={(e) => {
        if (sendingRef.current) e.preventDefault(); // no closing mid-request
      }}
      onClose={() => {
        setAcknowledged(false);
        setProblem(null);
        onClosed();
      }}
      className="m-auto max-h-[calc(100dvh-32px)] w-[calc(100%-32px)] max-w-lg overflow-y-auto rounded-[22px] border-[3px] border-border bg-card p-0 text-fg shadow-sticker backdrop:bg-black/60"
    >
      <div className="flex flex-col gap-4 p-5 sm:p-6">
        <h2 id="cancel-h" className="m-0 text-2xl font-extrabold">
          Cancel this order?
        </h2>

        {cancelWarning ? (
          <Notice tone="warn">
            <p className="m-0 font-bold">Your deposit will NOT be refunded.</p>
            <p className="m-0">
              Your slot is confirmed, so the deposit is non-refundable once you cancel (you agreed
              to this at checkout).
            </p>
          </Notice>
        ) : (
          <p className="m-0">
            If you&apos;ve already paid and we haven&apos;t confirmed it yet, contact the shop
            instead of cancelling.
          </p>
        )}

        {cancelWarning && (
          <label className="flex cursor-pointer items-start gap-3 rounded-2xl border-[3px] border-border bg-tint-mto p-3.5 text-[15px] leading-normal text-ink has-[:focus-visible]:outline-[3px] has-[:focus-visible]:outline-offset-2 has-[:focus-visible]:outline-focus">
            <input
              type="checkbox"
              checked={acknowledged}
              onChange={(e) => setAcknowledged(e.target.checked)}
              className="mt-0.5 size-[22px] flex-none accent-red"
            />
            <span>I understand that my deposit will not be refunded.</span>
          </label>
        )}

        <div role="alert">
          {problem && (
            <Notice tone="error">
              <p className="m-0 font-bold">{problem.text}</p>
              {problem.reference && <p className="m-0 text-sm">Reference: {problem.reference}</p>}
              {problem.lookup && (
                <Link href="/orders/lookup" className="font-bold underline">
                  Request a new link
                </Link>
              )}
            </Notice>
          )}
        </div>

        <div className="flex flex-wrap gap-3">
          {problem?.stale ? (
            <button
              type="button"
              onClick={() => {
                onReload();
                close();
              }}
              className={primaryButton}
            >
              Reload order
            </button>
          ) : (
            !problem?.final && (
              <button
                type="button"
                onClick={() => void submit()}
                disabled={sending || (cancelWarning && !acknowledged)}
                className={primaryButton}
              >
                {sending ? "Cancelling…" : "Yes, cancel order"}
              </button>
            )
          )}
          <button
            type="button"
            autoFocus
            onClick={close}
            disabled={sending}
            className={secondaryButton}
          >
            {problem?.final ? "Close" : "Keep my order"}
          </button>
        </div>
      </div>
    </dialog>
  );
}
