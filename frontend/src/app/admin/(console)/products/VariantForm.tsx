"use client";

import { useState, type FormEvent } from "react";
import { ApiError, apiFetch } from "@/lib/api";
import { centsToDollars } from "@/lib/money";
import { buttonClass, inputClass, secondaryButtonClass } from "../../ui";
import { ErrorBanner } from "./ErrorBanner";
import { Field } from "./Field";
import { apiFieldErrors, focusFirstInvalid, zodErrors, type FieldErrors } from "./forms";
import { variantCreateSchema, variantEditSchema } from "./schemas";
import type { Variant } from "./types";

/** Creates a variant of `productId`, or edits `variant` (scale is fixed after creation). */
export function VariantForm({
  productId,
  variant,
  onSaved,
  onCancel,
  onReload,
}: {
  productId: number;
  variant?: Variant;
  onSaved: (saved: Variant) => void;
  onCancel: () => void;
  onReload: () => void;
}) {
  const [errors, setErrors] = useState<FieldErrors>({});
  const [failure, setFailure] = useState<unknown>(null);
  const [submitting, setSubmitting] = useState(false);
  const [madeToOrder, setMadeToOrder] = useState(variant?.madeToOrderEnabled ?? false);

  async function onSubmit(ev: FormEvent<HTMLFormElement>) {
    ev.preventDefault();
    const form = ev.currentTarget;
    setFailure(null);
    const data = new FormData(form);
    const raw = {
      ...Object.fromEntries(data),
      quota: data.get("quota") ?? "", // the quota input is not rendered unless made-to-order is on
      madeToOrderEnabled: data.has("madeToOrderEnabled"),
      active: data.has("active"),
    };
    const parsed = (variant ? variantEditSchema : variantCreateSchema).safeParse(raw);
    if (!parsed.success) {
      setErrors(zodErrors(parsed.error));
      focusFirstInvalid(form);
      return;
    }
    setErrors({});
    const v = parsed.data;
    const body = {
      ...("scale" in v ? { scale: v.scale } : { version: variant?.version }),
      priceCents: v.price,
      stockQty: v.stockQty,
      madeToOrderEnabled: v.madeToOrderEnabled,
      madeToOrderQuota: v.madeToOrderEnabled && v.quota !== "" ? v.quota : null,
      active: v.active,
    };
    setSubmitting(true);
    try {
      const saved = await apiFetch<Variant>(
        variant ? `/api/admin/variants/${variant.id}` : `/api/admin/products/${productId}/variants`,
        {
          method: variant ? "PUT" : "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(body),
        },
      );
      onSaved(saved);
    } catch (e) {
      setFailure(e);
      if (e instanceof ApiError) {
        setErrors(apiFieldErrors(e));
        focusFirstInvalid(form);
      }
    } finally {
      setSubmitting(false);
    }
  }

  const invalid = (name: string) => (errors[name] ? true : undefined);

  return (
    <form
      onSubmit={(ev) => void onSubmit(ev)}
      noValidate
      className="flex flex-col gap-3 rounded-[14px] border-[3px] border-dashed border-border p-3"
    >
      <ErrorBanner error={failure} onReload={onReload} />
      {variant ? (
        <p className="font-bold">Scale {variant.scale}</p>
      ) : (
        <Field label="Scale" error={errors.scale} hint="For example 1/6 or 1/12.">
          <input
            name="scale"
            required
            autoFocus
            maxLength={5}
            aria-invalid={invalid("scale")}
            className={inputClass}
          />
        </Field>
      )}
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Price (USD)" error={errors.price}>
          <input
            name="price"
            required
            inputMode="decimal"
            defaultValue={variant ? centsToDollars(variant.priceCents) : ""}
            aria-invalid={invalid("price")}
            className={inputClass}
          />
        </Field>
        <Field label="Stock" error={errors.stockQty}>
          <input
            name="stockQty"
            required
            inputMode="numeric"
            defaultValue={variant?.stockQty ?? 0}
            aria-invalid={invalid("stockQty")}
            className={inputClass}
          />
        </Field>
      </div>
      <label className="flex min-h-11 items-center gap-2 font-medium">
        <input
          type="checkbox"
          name="madeToOrderEnabled"
          checked={madeToOrder}
          onChange={(e) => setMadeToOrder(e.target.checked)}
          className="size-6"
        />
        Made to order
      </label>
      {madeToOrder && (
        <Field label="Made-to-order quota" error={errors.quota}>
          <input
            name="quota"
            inputMode="numeric"
            defaultValue={variant?.madeToOrderQuota ?? ""}
            aria-invalid={invalid("quota")}
            className={inputClass}
          />
        </Field>
      )}
      <label className="flex min-h-11 items-center gap-2 font-medium">
        <input
          type="checkbox"
          name="active"
          defaultChecked={variant?.active ?? true}
          className="size-6"
        />
        Active (visible in the shop)
      </label>
      <div className="flex flex-wrap gap-3">
        <button type="submit" disabled={submitting} className={buttonClass}>
          {submitting ? "Saving..." : variant ? "Save variant" : "Add variant"}
        </button>
        <button type="button" onClick={onCancel} className={secondaryButtonClass}>
          Cancel
        </button>
      </div>
    </form>
  );
}
