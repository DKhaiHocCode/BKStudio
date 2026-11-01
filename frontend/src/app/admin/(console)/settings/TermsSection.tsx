"use client";

import { useRef, useState, type FormEvent } from "react";
import { ApiError, apiFetch } from "@/lib/api";
import { TERMS_MAX, TERMS_VERSION_RE } from "@/lib/adminSettings";
import { alertClass, buttonClass, inputClass, panelClass, secondaryButtonClass } from "../../ui";
import { ErrorBanner } from "../products/ErrorBanner";
import { Field } from "../products/Field";

export function TermsSection({
  current,
  onPublished,
}: {
  current: { version: string; contentMd: string } | null;
  onPublished: (published: { version: string; contentMd: string }) => void;
}) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const cancelRef = useRef<HTMLButtonElement>(null);
  const [content, setContent] = useState(current?.contentMd ?? "");
  const [version, setVersion] = useState("");
  const [invalid, setInvalid] = useState<{ version?: string; content?: string }>({});
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  function review(ev: FormEvent<HTMLFormElement>) {
    ev.preventDefault();
    const v = version.trim();
    const found: { version?: string; content?: string } = {};
    if (v !== "" && !TERMS_VERSION_RE.test(v)) found.version = "Use YYYY-MM-DD or YYYY-MM-DD-N.";
    if (content.trim() === "") found.content = "Terms cannot be empty.";
    else if (content.length > TERMS_MAX) found.content = `At most ${TERMS_MAX} characters.`;
    else if (content.includes("\u0000")) found.content = "Contains an invalid character.";
    setInvalid(found);
    setError(null);
    if (Object.keys(found).length === 0) {
      dialogRef.current?.showModal();
      // React never writes the HTML autofocus attribute on client renders, so focus Cancel by hand.
      cancelRef.current?.focus();
    }
  }

  async function publish() {
    setBusy(true);
    setError(null);
    try {
      const v = version.trim();
      const created = await apiFetch<{ version: string; contentMd: string }>("/api/admin/terms", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(
          v === "" ? { contentMd: content } : { version: v, contentMd: content },
        ),
      });
      dialogRef.current?.close();
      onPublished({ version: created.version, contentMd: created.contentMd });
    } catch (e) {
      dialogRef.current?.close();
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  const conflict = error instanceof ApiError && error.status === 409;

  return (
    <section className={`${panelClass} flex flex-col gap-4`}>
      <h2 className="text-lg font-extrabold">Terms and conditions</h2>
      {current ? (
        <p>
          Current version: <span className="font-mono font-bold">{current.version}</span>
        </p>
      ) : (
        <p className={alertClass}>
          No terms published yet. Shop checkout stays closed until one exists.
        </p>
      )}

      <form onSubmit={review} noValidate className="flex flex-col gap-4">
        <Field
          label="Version (optional)"
          error={invalid.version}
          hint="YYYY-MM-DD or YYYY-MM-DD-N. Empty uses today's date (UTC)."
        >
          <input
            value={version}
            onChange={(e) => setVersion(e.target.value)}
            aria-invalid={Boolean(invalid.version)}
            className={`${inputClass} max-w-xs`}
          />
        </Field>
        <Field
          label="Terms (markdown source)"
          error={invalid.content}
          hint={`${content.length.toLocaleString("en-US")} / ${TERMS_MAX.toLocaleString("en-US")} characters`}
        >
          <textarea
            rows={14}
            value={content}
            onChange={(e) => setContent(e.target.value)}
            aria-invalid={Boolean(invalid.content)}
            className={`${inputClass} py-2 font-mono text-sm`}
          />
        </Field>

        <div>
          <h3 className="font-bold">Plain-text preview</h3>
          <pre className="mt-1 max-h-64 overflow-auto rounded-[14px] border-[3px] border-border p-3 text-sm break-words whitespace-pre-wrap">
            {content}
          </pre>
        </div>

        {conflict ? (
          <p role="alert" className={alertClass}>
            This version already exists. Published terms cannot be changed: choose another version
            (for example add a -2 suffix).
          </p>
        ) : (
          <ErrorBanner error={error} />
        )}
        <div>
          <button type="submit" className={buttonClass}>
            Publish new version
          </button>
        </div>
      </form>

      <dialog
        ref={dialogRef}
        aria-labelledby="terms-dialog-title"
        onCancel={(e) => {
          if (busy) e.preventDefault();
        }}
        className="m-auto max-h-[calc(100dvh-32px)] w-[calc(100%-32px)] max-w-lg overflow-y-auto rounded-[22px] border-[3px] border-border bg-card p-0 text-fg shadow-sticker backdrop:bg-black/60"
      >
        <div className="flex flex-col gap-4 p-5 sm:p-6">
          <h2 id="terms-dialog-title" className="m-0 text-2xl font-extrabold">
            Publish these terms?
          </h2>
          <p className={alertClass}>
            Published terms are <strong>immutable</strong>: they cannot be edited or deleted. Every
            customer who checks out afterwards is bound to this version. To change them later you
            must publish a new version.
          </p>
          <div className="flex flex-wrap gap-3">
            <button
              type="button"
              disabled={busy}
              onClick={() => void publish()}
              className={buttonClass}
            >
              {busy ? "Publishing..." : "Publish permanently"}
            </button>
            <button
              type="button"
              ref={cancelRef}
              disabled={busy}
              onClick={() => dialogRef.current?.close()}
              className={secondaryButtonClass}
            >
              Cancel
            </button>
          </div>
        </div>
      </dialog>
    </section>
  );
}
