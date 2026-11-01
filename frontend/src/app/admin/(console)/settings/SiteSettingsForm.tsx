"use client";

import { useState, type FormEvent } from "react";
import { ApiError, apiFetch } from "@/lib/api";
import {
  capacityMessage,
  fieldErrorMap,
  HOSTNAME_RE,
  MAX_DOMAINS,
  normalizeDomain,
  SOCIAL_KEYS,
  socialMessage,
  type SettingsData,
} from "@/lib/adminSettings";
import { buttonClass, inputClass, panelClass, secondaryButtonClass } from "../../ui";
import { ErrorBanner } from "../products/ErrorBanner";
import { Field } from "../products/Field";

export function SiteSettingsForm({
  initial,
  onSaved,
}: {
  initial: SettingsData;
  onSaved: (saved: SettingsData) => void;
}) {
  const [capacity, setCapacity] = useState(String(initial.defaultCapacity));
  const [domains, setDomains] = useState<string[]>(initial.paymentAllowedDomains);
  const [social, setSocial] = useState<Record<string, string>>(
    Object.fromEntries(SOCIAL_KEYS.map((k) => [k, initial.social[k] ?? ""])),
  );
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [saveError, setSaveError] = useState<unknown>(null);
  const [saving, setSaving] = useState(false);

  async function submit(ev: FormEvent<HTMLFormElement>) {
    ev.preventDefault();
    const found: Record<string, string> = {};
    const cap = capacityMessage(capacity);
    if (cap) found.defaultCapacity = cap;

    // Empty rows are dropped; `sent` remembers each value's row so server indexes map back to inputs.
    const sent: { row: number; value: string }[] = [];
    const seen = new Set<string>();
    domains.forEach((raw, row) => {
      const value = normalizeDomain(raw);
      if (value === "") return;
      if (!HOSTNAME_RE.test(value)) found[`d${row}`] = "Enter a hostname such as artistree.io.";
      else if (seen.has(value)) found[`d${row}`] = "Duplicate domain.";
      else sent.push({ row, value });
      seen.add(value);
    });
    for (const k of SOCIAL_KEYS) {
      const m = socialMessage(social[k]);
      if (m) found[`social.${k}`] = m;
    }
    setErrors(found);
    setSaveError(null);
    if (Object.keys(found).length > 0) return;

    setSaving(true);
    try {
      const body: SettingsData = {
        defaultCapacity: Number(capacity.trim()),
        paymentAllowedDomains: sent.map((s) => s.value),
        social: {
          reddit: social.reddit.trim() || null,
          facebook: social.facebook.trim() || null,
          instagram: social.instagram.trim() || null,
          x: social.x.trim() || null,
        },
      };
      const saved = await apiFetch<SettingsData>("/api/admin/settings", {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      onSaved(saved);
    } catch (e) {
      if (e instanceof ApiError && e.fieldErrors.length > 0) {
        const mapped: Record<string, string> = {};
        for (const [field, message] of Object.entries(fieldErrorMap(e.fieldErrors))) {
          const m = /^paymentAllowedDomains\[(\d+)\]$/.exec(field);
          const row = m ? sent[Number(m[1])]?.row : undefined;
          mapped[row === undefined ? field : `d${row}`] = message;
        }
        setErrors(mapped);
      }
      setSaveError(e);
    } finally {
      setSaving(false);
    }
  }

  return (
    <form
      onSubmit={(ev) => void submit(ev)}
      noValidate
      className={`${panelClass} flex flex-col gap-4`}
    >
      <h2 className="text-lg font-extrabold">Site settings</h2>

      <Field label="Default monthly capacity (1-50)" error={errors.defaultCapacity}>
        <input
          inputMode="numeric"
          value={capacity}
          onChange={(e) => setCapacity(e.target.value)}
          aria-invalid={Boolean(errors.defaultCapacity)}
          className={`${inputClass} max-w-xs`}
        />
      </Field>

      <fieldset className="flex flex-col gap-2">
        <legend className="font-medium">Allowed payment domains (max {MAX_DOMAINS})</legend>
        <p className="text-sm text-muted">
          Payment links you send to customers must belong to these domains. List only registrable
          domains (for example artistree.io), never a public suffix such as com.sg. Hostname only:
          no https://, path or port.
        </p>
        {domains.map((d, row) => (
          <div key={row} className="flex flex-col gap-1">
            <div className="flex gap-2">
              <input
                aria-label={`Payment domain ${row + 1}`}
                value={d}
                onChange={(e) =>
                  setDomains(domains.map((x, i) => (i === row ? e.target.value : x)))
                }
                aria-invalid={Boolean(errors[`d${row}`])}
                className={inputClass}
              />
              <button
                type="button"
                className={secondaryButtonClass}
                aria-label={`Remove payment domain ${row + 1}`}
                onClick={() => {
                  setDomains(domains.filter((_, i) => i !== row));
                  // Row indexes shift, so row errors would point at the wrong input.
                  setErrors((prev) =>
                    Object.fromEntries(Object.entries(prev).filter(([k]) => !/^d\d+$/.test(k))),
                  );
                }}
              >
                Remove
              </button>
            </div>
            {errors[`d${row}`] && (
              <span role="alert" className="text-sm font-bold text-danger">
                {errors[`d${row}`]}
              </span>
            )}
          </div>
        ))}
        <div>
          <button
            type="button"
            className={secondaryButtonClass}
            disabled={domains.length >= MAX_DOMAINS}
            onClick={() => setDomains([...domains, ""])}
          >
            Add domain
          </button>
        </div>
      </fieldset>

      <fieldset className="flex flex-col gap-3">
        <legend className="font-medium">Social links (https only, empty removes)</legend>
        {SOCIAL_KEYS.map((k) => (
          <Field key={k} label={k[0].toUpperCase() + k.slice(1)} error={errors[`social.${k}`]}>
            <input
              type="url"
              inputMode="url"
              value={social[k]}
              onChange={(e) => setSocial({ ...social, [k]: e.target.value })}
              aria-invalid={Boolean(errors[`social.${k}`])}
              className={inputClass}
            />
          </Field>
        ))}
      </fieldset>

      <ErrorBanner error={saveError} />
      <div>
        <button type="submit" disabled={saving} className={buttonClass}>
          {saving ? "Saving..." : "Save settings"}
        </button>
      </div>
    </form>
  );
}
