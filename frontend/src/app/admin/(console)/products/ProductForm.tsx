"use client";

import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { ApiError, apiFetch } from "@/lib/api";
import { buttonClass, inputClass, panelClass } from "../../ui";
import { ErrorBanner } from "./ErrorBanner";
import { Field } from "./Field";
import { apiFieldErrors, focusFirstInvalid, zodErrors, type FieldErrors } from "./forms";
import { productSchema } from "./schemas";
import { PRODUCT_STATUSES, type ProductDetail } from "./types";
import { useFlash } from "./useFlash";

/** Create form when `initial` is absent, edit form otherwise (slug is read-only after creation). */
export function ProductForm({
  initial,
  onSaved,
  onReload,
}: {
  initial?: ProductDetail;
  onSaved?: (saved: ProductDetail) => void;
  onReload?: () => void;
}) {
  const router = useRouter();
  const [errors, setErrors] = useState<FieldErrors>({});
  const [failure, setFailure] = useState<unknown>(null);
  const [saved, flash] = useFlash();
  const [submitting, setSubmitting] = useState(false);

  async function onSubmit(ev: FormEvent<HTMLFormElement>) {
    ev.preventDefault();
    const form = ev.currentTarget;
    setFailure(null);
    // The slug input only exists on create; without a default the edit form failed validation on a hidden field.
    const parsed = productSchema.safeParse({ slug: "", ...Object.fromEntries(new FormData(form)) });
    if (!parsed.success) {
      setErrors(zodErrors(parsed.error));
      focusFirstInvalid(form);
      return;
    }
    setErrors({});
    const { slug, ...fields } = parsed.data;
    setSubmitting(true);
    try {
      if (initial) {
        const result = await apiFetch<ProductDetail>(`/api/admin/products/${initial.id}`, {
          method: "PUT",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ ...fields, version: initial.version }),
        });
        flash("Saved.");
        onSaved?.(result);
      } else {
        const created = await apiFetch<ProductDetail>("/api/admin/products", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ ...fields, slug: slug || undefined }),
        });
        router.push(`/admin/products/${created.id}`);
      }
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
      // New version after a save: remount so the inputs show what the server stored.
      key={initial?.version ?? "new"}
      onSubmit={(ev) => void onSubmit(ev)}
      noValidate
      className={`${panelClass} flex flex-col gap-4`}
    >
      <ErrorBanner error={failure} onReload={onReload} />
      <Field label="Name" error={errors.name}>
        <input
          name="name"
          required
          maxLength={200}
          autoFocus={!initial}
          defaultValue={initial?.name}
          aria-invalid={invalid("name")}
          className={inputClass}
        />
      </Field>
      {initial ? (
        <p>
          <span className="font-medium">Slug:</span>{" "}
          <code className="break-all">{initial.slug}</code>
        </p>
      ) : (
        <Field
          label="Slug (optional)"
          error={errors.slug}
          hint="Generated from the name if empty. UNLISTED products always get a random suffix appended."
        >
          <input name="slug" maxLength={80} aria-invalid={invalid("slug")} className={inputClass} />
        </Field>
      )}
      <Field label="Series" error={errors.series}>
        <input
          name="series"
          maxLength={200}
          defaultValue={initial?.series ?? ""}
          aria-invalid={invalid("series")}
          className={inputClass}
        />
      </Field>
      <Field label="Description" error={errors.description}>
        <textarea
          name="description"
          rows={6}
          maxLength={10_000}
          defaultValue={initial?.description ?? ""}
          aria-invalid={invalid("description")}
          className={`${inputClass} py-2`}
        />
      </Field>
      <Field
        label="Status"
        error={errors.status}
        hint="ACTIVE shows in the shop (needs an active variant). UNLISTED is a commission, reachable only by its link."
      >
        <select
          name="status"
          defaultValue={initial?.status ?? "DRAFT"}
          aria-invalid={invalid("status")}
          className={inputClass}
        >
          {PRODUCT_STATUSES.map((s) => (
            <option key={s} value={s}>
              {s}
            </option>
          ))}
        </select>
      </Field>
      <div className="flex flex-wrap items-center gap-3">
        <button type="submit" disabled={submitting} className={buttonClass}>
          {submitting ? "Saving..." : initial ? "Save changes" : "Create product"}
        </button>
        <span role="status" className="font-medium">
          {saved}
        </span>
      </div>
    </form>
  );
}
