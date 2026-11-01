"use client";

import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useState, type FormEvent } from "react";
import { ApiError, apiFetch } from "@/lib/api";
import { formatDateTime } from "@/lib/adminOrders";
import {
  addMonths,
  capacityError,
  currentMonth,
  MONTH_RE,
  productionMonthsSchema,
  queueSchema,
} from "@/lib/adminQueue";
import { alertClass, buttonClass, inputClass, panelClass, secondaryButtonClass } from "../../ui";
import { ErrorBanner } from "../products/ErrorBanner";
import { useApiGet } from "../products/useApiGet";
import { useFlash } from "../products/useFlash";

export function QueueView() {
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const now = currentMonth();
  const raw = params.get("month") ?? "";
  const month = MONTH_RE.test(raw) ? raw : now;

  const months = useApiGet<unknown>(
    `/api/admin/production-months?from=${month}&to=${addMonths(month, 11)}`,
  );
  const queue = useApiGet<unknown>(`/api/admin/queue?month=${month}`);

  const [editing, setEditing] = useState<string | null>(null);
  const [value, setValue] = useState("");
  const [fieldError, setFieldError] = useState("");
  const [saveError, setSaveError] = useState<unknown>(null);
  const [saving, setSaving] = useState(false);
  const [flashMsg, flash] = useFlash();

  // Drop the draft and any open editor whenever the month changes (picker, buttons or browser back).
  const [shownMonth, setShownMonth] = useState(month);
  const [draft, setDraft] = useState(month);
  if (shownMonth !== month) {
    setShownMonth(month);
    setDraft(month);
    setEditing(null);
    setSaveError(null);
    setFieldError("");
  }

  function pick(next: string) {
    setDraft(next);
    if (MONTH_RE.test(next) && next !== month) router.push(`${pathname}?month=${next}`);
  }

  async function save(ev: FormEvent<HTMLFormElement>, m: string) {
    ev.preventDefault();
    const msg = capacityError(value);
    setFieldError(msg);
    if (msg) return;
    setSaving(true);
    setSaveError(null);
    try {
      const saved = await apiFetch<unknown>(`/api/admin/production-months/${m}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ capacity: Number(value.trim()) }),
      });
      const row = productionMonthsSchema.element.parse(saved);
      months.update((d) => {
        const p = productionMonthsSchema.safeParse(d);
        return p.success ? p.data.map((x) => (x.month === row.month ? row : x)) : d;
      });
      setEditing(null);
      flash("Capacity saved.");
    } catch (e) {
      setSaveError(e);
    } finally {
      setSaving(false);
    }
  }

  const pm = months.data === undefined ? null : productionMonthsSchema.safeParse(months.data);
  const qp = queue.data === undefined ? null : queueSchema.safeParse(queue.data);
  const monthsError = months.error ?? (pm && !pm.success ? new Error("Unexpected response") : null);
  const queueError = queue.error ?? (qp && !qp.success ? new Error("Unexpected response") : null);
  const rows = pm?.success ? pm.data : null;
  const entries = qp?.success ? qp.data.entries : null;

  const capacityConflict =
    saveError instanceof ApiError && saveError.code === "CAPACITY_BELOW_ALLOCATED";
  const allocatedOf = rows?.find((r) => r.month === editing)?.allocated;

  return (
    <main className="mx-auto flex w-full max-w-5xl flex-col gap-4 px-4 py-8">
      <h1 className="text-2xl font-extrabold">Production queue</h1>
      <div className="flex flex-wrap items-end gap-2">
        <label className="flex max-w-xs flex-col gap-1 font-medium">
          Month (YYYY-MM)
          <input
            type="month"
            value={draft}
            onChange={(e) => pick(e.target.value)}
            aria-invalid={!MONTH_RE.test(draft)}
            className={inputClass}
          />
        </label>
        <button
          type="button"
          className={secondaryButtonClass}
          onClick={() => pick(addMonths(month, -1))}
        >
          Previous month
        </button>
        <button
          type="button"
          className={secondaryButtonClass}
          onClick={() => pick(addMonths(month, 1))}
        >
          Next month
        </button>
      </div>
      <p role="status" className="min-h-6 font-medium">
        {flashMsg}
      </p>

      <section className={panelClass}>
        <h2 className="text-lg font-extrabold">Capacity</h2>
        {monthsError ? (
          <div className="mt-2 flex flex-col items-start gap-2">
            <ErrorBanner error={monthsError} />
            <button type="button" className={secondaryButtonClass} onClick={months.reload}>
              Retry
            </button>
          </div>
        ) : !rows ? (
          <p role="status">Loading...</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="mt-2 w-full text-left text-sm">
              <thead>
                <tr>
                  <th className="py-2 pr-3">Month</th>
                  <th className="py-2 pr-3">Capacity</th>
                  <th className="py-2 pr-3">Allocated</th>
                  <th className="py-2 pr-3">Free</th>
                  <th className="py-2" />
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.month} className="border-t-2 border-border">
                    <td className="py-2 pr-3 font-mono font-bold">{r.month}</td>
                    <td className="py-2 pr-3">{r.capacity}</td>
                    <td className="py-2 pr-3">{r.allocated}</td>
                    <td className="py-2 pr-3">{r.capacity - r.allocated}</td>
                    <td className="py-2">
                      {r.month >= now && (
                        <button
                          type="button"
                          className={secondaryButtonClass}
                          onClick={() => {
                            setEditing(r.month);
                            setValue(String(r.capacity));
                            setFieldError("");
                            setSaveError(null);
                          }}
                        >
                          Edit
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {editing && (
          <form
            onSubmit={(ev) => void save(ev, editing)}
            className="mt-4 flex flex-col gap-3"
            noValidate
          >
            <label className="flex max-w-xs flex-col gap-1 font-medium">
              Capacity for {editing} (0-1000)
              <input
                inputMode="numeric"
                value={value}
                onChange={(e) => setValue(e.target.value)}
                aria-invalid={fieldError !== ""}
                className={inputClass}
              />
            </label>
            {fieldError && (
              <p role="alert" className="text-sm text-danger">
                {fieldError}
              </p>
            )}
            {capacityConflict ? (
              <p role="alert" className={alertClass}>
                Capacity cannot go below the {allocatedOf ?? "already"} allocated slots.
              </p>
            ) : (
              <ErrorBanner error={saveError} />
            )}
            <div className="flex gap-3">
              <button type="submit" disabled={saving} className={buttonClass}>
                {saving ? "Saving..." : "Save"}
              </button>
              <button
                type="button"
                className={secondaryButtonClass}
                onClick={() => setEditing(null)}
              >
                Cancel
              </button>
            </div>
          </form>
        )}
      </section>

      <section className={panelClass}>
        <h2 className="text-lg font-extrabold">Queue for {month}</h2>
        {queueError ? (
          <div className="mt-2 flex flex-col items-start gap-2">
            <ErrorBanner error={queueError} />
            <button type="button" className={secondaryButtonClass} onClick={queue.reload}>
              Retry
            </button>
          </div>
        ) : !entries ? (
          <p role="status">Loading...</p>
        ) : entries.length === 0 ? (
          <p className="mt-2 text-sm">No queued units this month.</p>
        ) : (
          <ul className="mt-2 flex flex-col gap-2 text-sm">
            {entries.map((e) => (
              <li key={e.id} className="flex flex-wrap gap-x-3">
                <span className="font-mono font-bold">#{e.id}</span>
                <span>
                  Item {e.orderItemId} unit {e.unitIndex}
                </span>
                <span>{e.status}</span>
                <span>{formatDateTime(e.queuedAt)}</span>
              </li>
            ))}
          </ul>
        )}
      </section>
    </main>
  );
}
