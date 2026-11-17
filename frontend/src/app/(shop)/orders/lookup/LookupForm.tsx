"use client";

import { useCallback, useRef, useState, useSyncExternalStore, type FormEvent } from "react";
import { z } from "zod";
import { Notice } from "@/components/shop/Notice";
import { panel, primaryButton } from "@/components/ui";
import { ApiError, apiFetch } from "@/lib/api";
import { CONTACT_EMAIL } from "@/lib/contact";
import { waitText } from "@/lib/orders";
import { checkoutSchema, zodErrors, type FieldErrors } from "../../checkout/schema";
import { TextField } from "../../checkout/TextField";
import {
  TURNSTILE_SITE_KEY,
  TurnstileWidget,
  type TurnstileHandle,
} from "../../checkout/TurnstileWidget";

const lookupSchema = z.object({
  code: z.string().trim().min(1, "Order code is required").max(20, "At most 20 characters"),
  email: checkoutSchema.shape.email, // same limits and pattern as checkout
});

// The backend answers 202 whether or not an email went out (no match, or rate-limited): no promise.
const NEUTRAL = `If these details match an order, we have emailed a new link, and the old link stops working. Check your spam folder too. If nothing arrives within 10 minutes, try again later or email ${CONTACT_EMAIL}.`;
const CHECK_NEEDED = "Please complete the check.";

type Outcome = { kind: "done" } | { kind: "retry"; text: string; reference?: string } | null;

export function LookupForm() {
  const [errors, setErrors] = useState<FieldErrors>({});
  const [outcome, setOutcome] = useState<Outcome>(null);
  const [sending, setSending] = useState(false);
  const sendingRef = useRef(false);
  const formRef = useRef<HTMLFormElement>(null);
  const [turnstileToken, setTurnstileToken] = useState<string | null>(null);
  const turnstileRef = useRef<TurnstileHandle>(null);
  // Stable identity: the widget re-renders itself when this callback changes.
  const onTurnstileToken = useCallback((token: string | null) => {
    setTurnstileToken(token);
    if (token) setOutcome((o) => (o?.kind === "retry" && o.text === CHECK_NEEDED ? null : o));
  }, []);
  // false on the server render, true once hydrated: no native POST before JS is ready.
  const hydrated = useSyncExternalStore(
    () => () => {},
    () => true,
    () => false,
  );

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (sendingRef.current) return;
    const data = new FormData(event.currentTarget);
    const parsed = lookupSchema.safeParse({
      code: String(data.get("code") ?? ""),
      email: String(data.get("email") ?? ""),
    });
    if (!parsed.success) {
      const found = zodErrors(parsed.error);
      setErrors(found);
      setOutcome(null);
      requestAnimationFrame(() =>
        formRef.current?.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus(),
      );
      return;
    }
    if (TURNSTILE_SITE_KEY && !turnstileToken) {
      setErrors({});
      setOutcome({ kind: "retry", text: CHECK_NEEDED });
      return;
    }

    sendingRef.current = true;
    setSending(true);
    setErrors({});
    setOutcome(null);
    try {
      await apiFetch<unknown>("/api/orders/lookup", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        // turnstileToken is omitted from the JSON when Turnstile is off (no site key).
        body: JSON.stringify({ ...parsed.data, turnstileToken: turnstileToken ?? undefined }),
      });
      setOutcome({ kind: "done" });
      formRef.current?.reset(); // the code and email must not linger on screen
    } catch (e) {
      if (e instanceof ApiError && e.status === 429) {
        setOutcome({
          kind: "retry",
          text: `Too many attempts. Please try again in ${waitText(e.retryAfter)}.`,
        });
      } else if (e instanceof ApiError && e.status === 0) {
        setOutcome({ kind: "retry", text: "We could not reach the server. Please try again." });
      } else if (e instanceof ApiError && e.code === "CAPTCHA_FAILED") {
        setOutcome({ kind: "retry", text: "The check failed. Please try again." });
      } else if (e instanceof ApiError && e.status === 503) {
        setOutcome({
          kind: "retry",
          text: `We could not verify the check right now. Please try again in a minute, or email ${CONTACT_EMAIL}.`,
          reference: e.requestId,
        });
      } else {
        // Typed values stay so the user can retry; only a 202 means "emailed".
        setOutcome({
          kind: "retry",
          text: "Something went wrong. Please try again.",
          reference: e instanceof ApiError ? e.requestId : undefined,
        });
      }
    } finally {
      turnstileRef.current?.reset(); // the token is single-use, after a success too
      sendingRef.current = false;
      setSending(false);
    }
  }

  return (
    <form
      ref={formRef}
      method="post"
      noValidate
      onSubmit={(e) => void onSubmit(e)}
      className={`${panel} flex flex-col gap-4`}
    >
      <TextField name="code" label="Order code" error={errors.code} hint="For example BK-7F3K9Q." />
      <TextField
        name="email"
        label="Email"
        type="email"
        autoComplete="email"
        error={errors.email}
      />
      {TURNSTILE_SITE_KEY && (
        <TurnstileWidget
          ref={turnstileRef}
          siteKey={TURNSTILE_SITE_KEY}
          onToken={onTurnstileToken}
        />
      )}
      <div role="status">
        {outcome?.kind === "done" && (
          <Notice tone="info">
            <p className="m-0 font-bold">{NEUTRAL}</p>
          </Notice>
        )}
        {outcome?.kind === "retry" && (
          <Notice tone="error">
            <p className="m-0 font-bold">{outcome.text}</p>
            {outcome.reference && <p className="m-0 text-sm">Reference: {outcome.reference}</p>}
          </Notice>
        )}
      </div>
      <button
        type="submit"
        disabled={sending || !hydrated}
        className={`${primaryButton} self-start`}
      >
        {sending ? "Sending…" : "Email me a new link"}
      </button>
    </form>
  );
}
