"use client";

import { useEffect, useRef, useState, type ChangeEvent, type FormEvent } from "react";
import { ApiError, apiFetch } from "@/lib/api";
import {
  PROGRESS_MAX_BYTES,
  PROGRESS_MAX_FILES,
  PROGRESS_MIME,
  PROGRESS_STAGES,
  STALE_CODES,
  type AdminOrder,
} from "@/lib/adminOrders";
import { buttonClass, inputClass, secondaryButtonClass } from "../../../ui";
import { ErrorBanner } from "../../products/ErrorBanner";
import { Field } from "../../products/Field";

interface Picked {
  file: File;
  url: string;
}

/** Approve the next unapproved stage (RAW_PRINT -> PREP -> PAINT). Caller renders it only for MADE_TO_ORDER + IN_PRODUCTION. */
export function ProgressApproval({
  order,
  onDone,
  onStale,
}: {
  order: AdminOrder;
  onDone: (message: string) => void;
  onStale: (error: unknown) => void;
}) {
  const busyRef = useRef(false);
  const pickedRef = useRef<Picked[]>([]);
  const [picked, setPicked] = useState<Picked[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [note, setNote] = useState("");
  const [invalid, setInvalid] = useState<Record<string, string>>({});

  useEffect(() => {
    const ref = pickedRef;
    return () => ref.current.forEach((p) => URL.revokeObjectURL(p.url));
  }, []);

  const approved = new Set(order.progress.map((p) => p.stage));
  const next = PROGRESS_STAGES.find((s) => !approved.has(s));

  function setAll(list: Picked[]) {
    pickedRef.current = list;
    setPicked(list);
  }

  function onFiles(ev: ChangeEvent<HTMLInputElement>) {
    const chosen = Array.from(ev.target.files ?? []);
    ev.target.value = ""; // allow picking the same file again
    let problem: string | undefined;
    const fresh: Picked[] = [];
    for (const file of chosen) {
      if (!PROGRESS_MIME.includes(file.type)) problem = `${file.name}: use JPEG or PNG.`;
      else if (file.size === 0) problem = `${file.name}: file is empty.`;
      else if (file.size > PROGRESS_MAX_BYTES) problem = `${file.name}: larger than 10 MB.`;
      else if (pickedRef.current.length + fresh.length >= PROGRESS_MAX_FILES)
        problem = `At most ${PROGRESS_MAX_FILES} photos.`;
      else fresh.push({ file, url: URL.createObjectURL(file) });
    }
    setInvalid(problem ? { files: problem } : {});
    if (fresh.length) setAll([...pickedRef.current, ...fresh]);
  }

  function remove(p: Picked) {
    URL.revokeObjectURL(p.url);
    setAll(pickedRef.current.filter((x) => x !== p));
  }

  async function submit(ev: FormEvent<HTMLFormElement>) {
    ev.preventDefault();
    if (busyRef.current || !next) return;
    if (note.length > 500) {
      setInvalid({ note: "At most 500 characters." });
      return;
    }
    setInvalid({});
    busyRef.current = true;
    setBusy(true);
    setError(null);
    const body = new FormData();
    if (note.trim()) body.append("note", note.trim());
    for (const p of pickedRef.current) body.append("files", p.file);
    try {
      // No Content-Type: the browser sets the multipart boundary.
      const res = await apiFetch<{ idempotentReplay?: boolean }>(
        `/api/admin/orders/${order.id}/progress/${next}`,
        { method: "POST", body },
      );
      pickedRef.current.forEach((p) => URL.revokeObjectURL(p.url));
      setAll([]);
      setNote("");
      onDone(
        res?.idempotentReplay
          ? "This stage was already approved elsewhere; your photos were not saved."
          : `${next} approved.`,
      );
    } catch (e) {
      if (e instanceof ApiError && e.status === 409 && STALE_CODES.includes(e.code)) onStale(e);
      else setError(e);
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  }

  const serverFields =
    error instanceof ApiError
      ? Object.fromEntries(error.fieldErrors.map((f) => [f.field, f.message]))
      : {};
  const fieldError = (name: string) => invalid[name] ?? serverFields[name];

  return (
    <div className="flex flex-col gap-3">
      <ul className="m-0 flex list-none flex-wrap gap-2 p-0">
        {PROGRESS_STAGES.map((s) => (
          <li key={s} className="rounded-[14px] border-2 border-border px-3 py-1 font-bold">
            {s} - {approved.has(s) ? "approved" : s === next ? "next" : "waiting"}
          </li>
        ))}
      </ul>
      {next ? (
        <form onSubmit={(ev) => void submit(ev)} noValidate className="flex flex-col gap-3">
          <h3 className="m-0 text-lg font-extrabold">Approve {next}</h3>
          <Field label="Note (optional)" error={fieldError("note")}>
            <textarea
              name="note"
              rows={3}
              maxLength={500}
              value={note}
              onChange={(e) => setNote(e.target.value)}
              aria-invalid={Boolean(fieldError("note"))}
              className={`${inputClass} py-2`}
            />
          </Field>
          <Field
            label="Photos (optional)"
            error={fieldError("files")}
            hint={`Up to ${PROGRESS_MAX_FILES} JPEG or PNG files, 10 MB each. Do not photograph names, addresses or shipping labels. Photos are public.`}
          >
            <input
              type="file"
              accept={PROGRESS_MIME.join(",")}
              multiple
              disabled={busy}
              onChange={onFiles}
              aria-invalid={Boolean(fieldError("files"))}
              className={inputClass}
            />
          </Field>
          {picked.length > 0 && (
            <ul className="m-0 flex list-none flex-wrap gap-3 p-0">
              {picked.map((p) => (
                <li key={p.url} className="flex flex-col items-start gap-1">
                  {/* eslint-disable-next-line @next/next/no-img-element -- local blob preview */}
                  <img
                    src={p.url}
                    alt={`Preview of ${p.file.name}`}
                    width={96}
                    height={96}
                    className="size-24 rounded-[14px] border-2 border-border object-cover"
                  />
                  <button
                    type="button"
                    disabled={busy}
                    onClick={() => remove(p)}
                    className={secondaryButtonClass}
                  >
                    Remove
                  </button>
                </li>
              ))}
            </ul>
          )}
          <ErrorBanner error={error} />
          <div>
            <button type="submit" disabled={busy} className={buttonClass}>
              {busy ? "Uploading..." : `Approve ${next}`}
            </button>
          </div>
        </form>
      ) : (
        <p className="m-0 text-muted">All stages approved.</p>
      )}
    </div>
  );
}
