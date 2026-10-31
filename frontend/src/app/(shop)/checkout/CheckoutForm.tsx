"use client";

import Link from "next/link";
import { useRef, useState, type FormEvent } from "react";
import { Notice } from "@/components/shop/Notice";
import { ProductImage } from "@/components/shop/ProductImage";
import { focusRing, panel, primaryButton, secondaryButton } from "@/components/ui";
import { ApiError, apiFetch } from "@/lib/api";
import { depositOf, subtotalOf, type CartLine, type CartMode } from "@/lib/cart";
import { formatUsd } from "@/lib/money";
import { clearIdempotencyKey, getIdempotencyKey, signatureOf } from "./idempotency";
import {
  checkoutSchema,
  formFieldOf,
  toRequest,
  zodErrors,
  type FieldErrors,
  type OrderCreated,
} from "./schema";
import { TextField } from "./TextField";
import { useTerms } from "./useTerms";

interface Problem {
  text: string;
  cartLink?: boolean;
  offerNewOrder?: boolean;
  reference?: string;
}

interface Failure {
  problem: Problem;
  fields: FieldErrors;
  /** Side effects for the caller (this mapper stays pure). */
  refreshTerms?: boolean;
}

function explain(e: unknown): Failure {
  if (!(e instanceof ApiError)) return { problem: { text: GENERIC }, fields: {} };
  const reference = e.requestId;
  if (e.status === 0) {
    return {
      problem: {
        text: "We could not reach the server. Your details are kept: press Place order again to retry. If you change nothing, retrying will not create a second order.",
      },
      fields: {},
    };
  }
  if (e.status === 400) {
    const fields: FieldErrors = {};
    for (const f of e.fieldErrors) {
      const name = formFieldOf(f.field);
      if (name) fields[name] ??= f.message;
    }
    const known = Object.keys(fields).length > 0;
    return {
      problem: {
        text: known
          ? "Please fix the highlighted fields."
          : "The shop could not accept these details. Please review them and try again.",
        reference,
      },
      fields,
    };
  }
  if (e.status === 404 || e.code === "OUT_OF_STOCK") {
    return {
      problem: {
        text: "Sorry, something in this order is no longer available. Please review your cart.",
        cartLink: true,
      },
      fields: {},
    };
  }
  if (e.code === "TERMS_OUTDATED") {
    return {
      problem: {
        text: "Our terms were just updated. Please read the new version and agree again.",
      },
      fields: { terms: "Please agree to the updated terms." },
      refreshTerms: true,
    };
  }
  if (e.code === "CONFLICT") {
    return {
      problem: {
        text: "An order may already have been placed with your earlier details. Check your inbox for a confirmation email before trying again.",
        offerNewOrder: true,
      },
      fields: {},
    };
  }
  if (e.status === 429) {
    return {
      problem: {
        text: `Too many orders from your network. Please try again in ${waitText(e.retryAfter)}.`,
      },
      fields: {},
    };
  }
  return { problem: { text: GENERIC, reference }, fields: {} };
}

const GENERIC = "Something went wrong on our side. Please try again in a moment.";

function waitText(seconds?: number): string {
  if (!seconds) return "a little while";
  return seconds >= 120 ? `${Math.ceil(seconds / 60)} minutes` : `${seconds} seconds`;
}

const fieldset = `${panel} m-0 flex min-w-0 flex-col gap-4`;
const heading = "m-0 text-xl font-extrabold";
const legend = "sr-only";

export function CheckoutForm({
  mode,
  lines,
  onPlaced,
}: {
  mode: CartMode;
  lines: readonly CartLine[];
  onPlaced: (order: OrderCreated) => void;
}) {
  const { terms, reload } = useTerms();
  const [agreed, setAgreed] = useState(false);
  const [errors, setErrors] = useState<FieldErrors>({});
  const [problem, setProblem] = useState<Problem | null>(null);
  const [sending, setSending] = useState(false);
  const sendingRef = useRef(false); // blocks a second submit before React re-renders
  const formRef = useRef<HTMLFormElement>(null);
  const alertRef = useRef<HTMLDivElement>(null);

  const subtotal = subtotalOf(lines);
  const isMto = mode === "mto";

  /** Focus the first invalid field once React has rendered the errors, else the summary box. */
  function focusProblem() {
    requestAnimationFrame(() =>
      (
        formRef.current?.querySelector<HTMLElement>('[aria-invalid="true"]') ?? alertRef.current
      )?.focus(),
    );
  }

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (sendingRef.current || terms.status !== "ready") return;

    const data = new FormData(event.currentTarget);
    const raw = Object.fromEntries(
      [...data.entries()].map(([k, v]) => [k, typeof v === "string" ? v : ""]),
    );
    const parsed = checkoutSchema.safeParse(raw);
    const found = parsed.success ? {} : zodErrors(parsed.error);
    if (!agreed) found.terms = "Please tick the box to continue.";
    if (!parsed.success || !agreed) {
      setErrors(found);
      setProblem({ text: "Please fix the highlighted fields." });
      focusProblem();
      return;
    }

    sendingRef.current = true;
    setSending(true);
    setErrors({});
    setProblem(null);
    try {
      const key = getIdempotencyKey(mode, signatureOf(lines));
      const created = await apiFetch<OrderCreated>("/api/orders", {
        method: "POST",
        headers: { "Content-Type": "application/json", "Idempotency-Key": key },
        body: JSON.stringify(toRequest(mode, lines, parsed.data, terms.version)),
      });
      onPlaced(created);
    } catch (e) {
      const result = explain(e);
      if (result.refreshTerms) {
        setAgreed(false);
        reload();
      }
      setErrors(result.fields);
      setProblem(result.problem);
      focusProblem();
    } finally {
      sendingRef.current = false;
      setSending(false);
    }
  }

  const cantSubmit = sending || terms.status !== "ready";

  return (
    <form
      ref={formRef}
      method="post"
      noValidate
      onSubmit={(e) => void onSubmit(e)}
      className="flex flex-wrap items-start gap-8"
    >
      <div className="flex min-w-0 flex-[999_1_480px] flex-col gap-6">
        <fieldset className={fieldset}>
          <legend className={legend}>Contact</legend>
          <h2 className={heading} aria-hidden="true">
            1 · Contact
          </h2>
          <div className="flex flex-wrap gap-4">
            <TextField
              name="name"
              label="Full name"
              autoComplete="name"
              error={errors.name}
              className="flex-[1_1_220px]"
            />
            <TextField
              name="email"
              label="Email"
              type="email"
              autoComplete="email"
              error={errors.email}
              className="flex-[1_1_220px]"
            />
          </div>
          <TextField
            name="phone"
            label="Phone"
            type="tel"
            autoComplete="tel"
            optional
            error={errors.phone}
            hint="For the courier."
          />
          <p className="m-0 text-sm text-muted">
            We send your payment link and order updates to this email. Check it carefully.
          </p>
        </fieldset>

        <fieldset className={fieldset}>
          <legend className={legend}>Shipping address</legend>
          <h2 className={heading} aria-hidden="true">
            2 · Shipping address
          </h2>
          <TextField name="country" label="Country" autoComplete="country" error={errors.country}>
            <option value="" disabled>
              Select a country
            </option>
            <option value="SG">Singapore</option>
            <option value="AU">Australia</option>
          </TextField>
          <TextField
            name="line1"
            label="Address line 1"
            autoComplete="address-line1"
            error={errors.line1}
          />
          <TextField
            name="line2"
            label="Address line 2"
            autoComplete="address-line2"
            optional
            error={errors.line2}
          />
          <div className="flex flex-wrap gap-4">
            <TextField
              name="city"
              label="City"
              autoComplete="address-level2"
              error={errors.city}
              className="flex-[2_1_200px]"
            />
            <TextField
              name="region"
              label="State"
              autoComplete="address-level1"
              optional
              error={errors.region}
              className="flex-[1_1_140px]"
            />
            <TextField
              name="postalCode"
              label="Postcode"
              autoComplete="postal-code"
              optional
              error={errors.postalCode}
              className="flex-[1_1_140px]"
            />
          </div>
        </fieldset>

        <TermsBox state={terms} onRetry={reload} />

        <div className={`${panel} flex flex-col gap-3`}>
          <h2 className="m-0 text-xl font-extrabold">4 · How payment works</h2>
          <p className="m-0">
            Nothing is charged on this page and there are no card details here. After you place the
            order, the shop emails you a secure payment link (Artistree or Shopee).
            {isMto
              ? " Once the 50% deposit is in, we confirm your production month by email."
              : " Your figure is held for you while you pay."}
          </p>
        </div>
      </div>

      <aside className={`${panel} flex min-w-0 flex-[1_1_320px] flex-col gap-4`}>
        <h2 className="m-0 text-[22px] font-extrabold">
          Order summary · {isMto ? "made to order" : "in stock"}
        </h2>
        <ul className="m-0 flex list-none flex-col gap-3 p-0">
          {lines.map((line) => (
            <li key={line.variantId} className="flex items-center gap-3.5">
              <div className="flex size-16 flex-none items-center justify-center overflow-hidden rounded-[14px] border-[3px] border-border bg-capybara">
                {line.imageUrl && (
                  <ProductImage src={line.imageUrl} alt="" className="size-full object-cover" />
                )}
              </div>
              <div className="flex min-w-0 flex-1 flex-col">
                <span className="font-extrabold break-words">{line.productName}</span>
                <span className="text-sm text-muted">
                  {line.scale} · Qty {line.quantity}
                </span>
              </div>
              <span className="font-bold">{formatUsd(line.priceCents * line.quantity)}</span>
            </li>
          ))}
        </ul>
        <dl className="m-0 flex flex-col gap-2 border-t-2 border-dashed border-line pt-3.5">
          <div className="flex justify-between gap-3">
            <dt>Subtotal (estimate)</dt>
            <dd className="m-0 font-bold">{formatUsd(subtotal)}</dd>
          </div>
          {isMto && (
            <div className="flex items-baseline justify-between gap-3">
              <dt className="font-bold">Deposit due (50%)</dt>
              <dd className="m-0 text-2xl font-extrabold">{formatUsd(depositOf(subtotal))}</dd>
            </div>
          )}
          <div className="flex justify-between gap-3 text-muted">
            <dt>Shipping</dt>
            <dd className="m-0">Quoted in your payment link</dd>
          </div>
        </dl>
        <p className="m-0 text-sm text-muted">
          Prices are indicative. The shop confirms the real amount in your payment link.
        </p>

        {isMto && (
          <Notice tone="warn">
            <p className="m-0 text-[15px] font-bold">
              The 50% deposit is non-refundable once your figure is queued for production.
            </p>
          </Notice>
        )}

        <label className="flex cursor-pointer items-start gap-3 rounded-2xl border-[3px] border-border bg-tint-mto p-3.5 text-[15px] leading-normal text-ink has-[:focus-visible]:outline-[3px] has-[:focus-visible]:outline-offset-2 has-[:focus-visible]:outline-focus">
          <input
            type="checkbox"
            checked={agreed}
            onChange={(e) => setAgreed(e.target.checked)}
            aria-invalid={errors.terms ? true : undefined}
            aria-describedby={errors.terms ? "terms-err" : undefined}
            className="mt-0.5 size-[22px] flex-none accent-red"
          />
          <span>
            I have read and agree to the Terms
            {terms.status === "ready" && ` (version ${terms.version})`}
            {isMto &&
              ", including that the 50% deposit is non-refundable once my slot is confirmed"}
            .
          </span>
        </label>
        {errors.terms && (
          <span id="terms-err" className="-mt-2 text-sm font-bold text-danger">
            {errors.terms}
          </span>
        )}

        <div
          ref={alertRef}
          tabIndex={-1}
          role="alert"
          className={`${problem ? "" : "hidden"} outline-offset-2 ${focusRing}`}
        >
          {problem && (
            <Notice tone="error">
              <p className="m-0 font-bold">{problem.text}</p>
              {problem.offerNewOrder && (
                <button
                  type="button"
                  onClick={() => {
                    clearIdempotencyKey(mode);
                    setProblem({ text: "Okay. Press Place order to submit this as a new order." });
                  }}
                  className={`${secondaryButton} mt-2`}
                >
                  Place this as a new order
                </button>
              )}
              {problem.cartLink && (
                <Link href="/cart" className={`mt-1 inline-block font-bold underline ${focusRing}`}>
                  Back to cart
                </Link>
              )}
              {problem.reference && (
                <p className="m-0 mt-1 font-mono text-sm">Reference: {problem.reference}</p>
              )}
            </Notice>
          )}
        </div>

        <button type="submit" disabled={cantSubmit} className={`${primaryButton} text-lg`}>
          {sending ? "Placing order…" : "Place order"}
        </button>
        {terms.status === "none" && (
          <p className="m-0 text-sm font-bold text-danger">
            Orders are closed for now: the shop has not published its terms yet. Please check back
            soon.
          </p>
        )}
        <p className="m-0 text-center text-sm text-muted">
          You won&apos;t pay on this page. We email you a secure payment link.
        </p>
        <Link href="/cart" className={`text-center font-bold ${focusRing}`}>
          ← Back to cart
        </Link>
      </aside>
    </form>
  );
}

/** Terms as PLAIN TEXT (React text node, pre-wrap). Never rendered as HTML or Markdown. */
function TermsBox({
  state,
  onRetry,
}: {
  state: ReturnType<typeof useTerms>["terms"];
  onRetry: () => void;
}) {
  return (
    <section aria-labelledby="terms-title" className={`${panel} flex flex-col gap-3`}>
      <h2 id="terms-title" className="m-0 text-xl font-extrabold">
        3 · Terms
      </h2>
      {state.status === "loading" && <p className="m-0 text-muted">Loading the terms…</p>}
      {state.status === "none" && (
        <p className="m-0 font-bold">The terms are not published yet, so orders are closed.</p>
      )}
      {state.status === "error" && (
        <div className="flex flex-wrap items-center gap-3">
          <p className="m-0 font-bold">We could not load the terms.</p>
          <button type="button" onClick={onRetry} className={secondaryButton}>
            Try again
          </button>
        </div>
      )}
      {state.status === "ready" && (
        <>
          <p className="m-0 text-sm text-muted">Version {state.version}</p>
          <div
            tabIndex={0}
            role="region"
            aria-label="Terms and conditions"
            className={`max-h-72 overflow-y-auto rounded-xl border-2 border-border bg-surface p-3.5 text-[15px] leading-relaxed break-words whitespace-pre-wrap ${focusRing}`}
          >
            {state.contentMd}
          </div>
        </>
      )}
    </section>
  );
}
