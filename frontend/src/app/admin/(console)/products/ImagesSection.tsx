"use client";

import { useEffect, useRef, useState, type DragEvent, type FormEvent } from "react";
import { apiFetch } from "@/lib/api";
import { dangerButtonClass, inputClass, panelClass, secondaryButtonClass } from "../../ui";
import { useFlash } from "./useFlash";
import { ErrorBanner } from "./ErrorBanner";
import { altTextSchema } from "./schemas";
import type { ProductImage } from "./types";

const MAX_IMAGES = 20;
const MAX_BYTES = 10 * 1024 * 1024;
const ACCEPTED = ["image/jpeg", "image/png"];

type Setter = (fn: (images: ProductImage[]) => ProductImage[]) => void;

function AltForm({ image, onChange }: { image: ProductImage; onChange: Setter }) {
  const [error, setError] = useState<unknown>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [done, flash] = useFlash();

  async function save(ev: FormEvent<HTMLFormElement>) {
    ev.preventDefault();
    const parsed = altTextSchema.safeParse(new FormData(ev.currentTarget).get("altText") ?? "");
    if (!parsed.success) return setProblem(parsed.error.issues[0].message);
    setProblem(null);
    setError(null);
    setSaving(true);
    try {
      const updated = await apiFetch<ProductImage>(`/api/admin/images/${image.id}`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ altText: parsed.data }),
      });
      onChange((list) => list.map((x) => (x.id === updated.id ? updated : x)));
      flash("Saved.");
    } catch (e) {
      setError(e);
    } finally {
      setSaving(false);
    }
  }

  return (
    <form onSubmit={(ev) => void save(ev)} noValidate className="flex flex-col gap-2">
      <ErrorBanner error={error} />
      <label className="flex flex-col gap-1 text-sm font-medium">
        Alt text
        <input
          name="altText"
          maxLength={200}
          defaultValue={image.altText ?? ""}
          aria-invalid={problem ? true : undefined}
          className={inputClass}
        />
        {problem && (
          <span role="alert" className="font-bold text-[#a82a10]">
            {problem}
          </span>
        )}
      </label>
      <div className="flex flex-wrap items-center gap-3">
        <button type="submit" disabled={saving} className={secondaryButtonClass}>
          {saving ? "Saving..." : "Save alt text"}
        </button>
        <span role="status" className="text-sm font-medium">
          {done}
        </span>
      </div>
    </form>
  );
}

export function ImagesSection({
  productId,
  images,
  onChange,
}: {
  productId: number;
  images: ProductImage[];
  onChange: Setter;
}) {
  const base = `/api/admin/products/${productId}/images`;
  const [busy, setBusy] = useState<string | null>(null);
  const [done, flash] = useFlash();
  const [error, setError] = useState<unknown>(null);
  const [rejected, setRejected] = useState<string[]>([]);
  const [dragIndex, setDragIndex] = useState<number | null>(null);
  const [dropActive, setDropActive] = useState(false);
  // Set by move(), consumed once by the effect below, so later image changes never steal focus.
  const focusMove = useRef<string | null>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const altRef = useRef<HTMLInputElement>(null);

  // A moved <li> may lose focus when React reorders the DOM; put it back on the same button,
  // or on the opposite one / the heading when that button is now disabled (top or bottom).
  useEffect(() => {
    const target = focusMove.current;
    if (!target) return;
    focusMove.current = null;
    const opposite = target.endsWith("-up")
      ? target.replace(/-up$/, "-down")
      : target.replace(/-down$/, "-up");
    const usable = [target, opposite]
      .map((id) => document.getElementById(id))
      .find((el): el is HTMLButtonElement => el instanceof HTMLButtonElement && !el.disabled);
    (usable ?? headingRef.current)?.focus();
  }, [images]);

  async function upload(files: File[]) {
    setError(null);
    const problems: string[] = [];
    const accepted = files.filter((f) => {
      if (!ACCEPTED.includes(f.type)) problems.push(`${f.name}: only JPEG or PNG images`);
      else if (f.size > MAX_BYTES) problems.push(`${f.name}: larger than 10 MB`);
      else return true;
      return false;
    });
    const room = MAX_IMAGES - images.length;
    if (accepted.length > room) {
      problems.push(`Only ${Math.max(room, 0)} more image(s) fit (maximum ${MAX_IMAGES}).`);
      accepted.splice(Math.max(room, 0));
    }
    setRejected(problems);
    const alt = altTextSchema.safeParse(altRef.current?.value ?? "");
    if (!alt.success) return setRejected([`Alt text: ${alt.error.issues[0].message}`]);

    try {
      for (const [i, file] of accepted.entries()) {
        setBusy(`Uploading ${i + 1} of ${accepted.length}...`);
        const body = new FormData();
        body.append("file", file);
        if (alt.data) body.append("altText", alt.data);
        // No Content-Type header: the browser must add the multipart boundary itself.
        const created = await apiFetch<ProductImage>(base, { method: "POST", body });
        onChange((list) => [...list, created]);
      }
      if (accepted.length > 0) flash("Upload complete.");
    } catch (e) {
      setError(e);
    } finally {
      setBusy(null);
    }
  }

  async function move(from: number, to: number, focusId?: string) {
    if (to < 0 || to >= images.length || from === to) return;
    const ids = images.map((i) => i.id);
    ids.splice(to, 0, ids.splice(from, 1)[0]);
    setError(null);
    setBusy("Saving order...");
    try {
      const ordered = await apiFetch<ProductImage[]>(`${base}/order`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ imageIds: ids }),
      });
      onChange(() => ordered);
      flash("Order saved.");
      focusMove.current = focusId ?? null;
    } catch (e) {
      setError(e);
    } finally {
      setBusy(null);
    }
  }

  async function remove(image: ProductImage) {
    if (!window.confirm("Delete this image? This cannot be undone.")) return;
    setError(null);
    setBusy("Deleting...");
    try {
      await apiFetch(`/api/admin/images/${image.id}`, { method: "DELETE" });
      onChange((list) => list.filter((x) => x.id !== image.id));
      headingRef.current?.focus();
      flash("Image deleted.");
    } catch (e) {
      setError(e);
    } finally {
      setBusy(null);
    }
  }

  // The whole section accepts file drops (so a miss never makes the browser open the file);
  // internal reorder drags carry no "Files" type and are handled by the <li> handlers.
  const hasFiles = (ev: DragEvent<HTMLElement>) => ev.dataTransfer.types.includes("Files");

  function onDropFiles(ev: DragEvent<HTMLElement>) {
    if (!hasFiles(ev)) return;
    ev.preventDefault();
    setDropActive(false);
    if (!busy) void upload([...ev.dataTransfer.files]);
  }

  return (
    <section
      aria-labelledby="images-heading"
      onDragOver={(ev) => {
        if (!hasFiles(ev)) return;
        ev.preventDefault();
        setDropActive(true);
      }}
      onDragLeave={(ev) => {
        if (!ev.currentTarget.contains(ev.relatedTarget as Node | null)) setDropActive(false);
      }}
      onDrop={onDropFiles}
      className={`${panelClass} flex flex-col gap-4`}
    >
      <h2 id="images-heading" ref={headingRef} tabIndex={-1} className="text-xl font-extrabold">
        Images ({images.length}/{MAX_IMAGES})
      </h2>
      <ErrorBanner error={error} />
      {rejected.length > 0 && (
        <ul
          role="alert"
          className="list-disc rounded-[14px] border-[3px] border-[#151515] bg-[#f8c9be] p-3 pl-8 text-sm"
        >
          {rejected.map((r) => (
            <li key={r}>{r}</li>
          ))}
        </ul>
      )}
      <div
        className={`flex flex-col gap-3 rounded-[14px] border-[3px] border-dashed border-[#151515] p-4 ${dropActive ? "bg-[#ffe3a3]" : ""}`}
      >
        <p className="font-medium">Drag JPEG or PNG files here (max 10 MB each), or choose them.</p>
        <label className="flex flex-col gap-1 text-sm font-medium">
          Alt text for new uploads (optional)
          <input ref={altRef} maxLength={200} className={inputClass} />
        </label>
        <label className="flex flex-col gap-1 text-sm font-medium">
          Choose images
          <input
            type="file"
            multiple
            accept={ACCEPTED.join(",")}
            disabled={busy !== null || images.length >= MAX_IMAGES}
            onChange={(ev) => {
              void upload([...(ev.target.files ?? [])]);
              ev.target.value = "";
            }}
            className="min-h-11 py-2"
          />
        </label>
      </div>
      <p role="status" className="font-medium">
        {busy ?? done}
      </p>
      <ul className="flex flex-col gap-4">
        {images.map((img, i) => (
          <li
            key={img.id}
            onDragOver={(ev) => dragIndex !== null && ev.preventDefault()}
            onDrop={(ev) => {
              ev.preventDefault();
              if (dragIndex !== null) void move(dragIndex, i);
              setDragIndex(null);
            }}
            className="flex flex-col gap-3 rounded-[14px] border-[3px] border-[#151515] p-3 sm:flex-row"
          >
            <div
              draggable={!busy}
              onDragStart={(ev) => {
                ev.dataTransfer.setData("text/plain", String(img.id));
                setDragIndex(i);
              }}
              onDragEnd={() => setDragIndex(null)}
              title="Drag to reorder"
              className="cursor-grab self-start"
            >
              {/* eslint-disable-next-line @next/next/no-img-element -- storage URLs are not configured for next/image */}
              <img
                src={img.urls["400"]}
                alt={img.altText ?? ""}
                width={160}
                height={Math.round((160 * img.height) / img.width)}
                draggable={false}
                className="h-auto w-40 rounded-[14px] border-2 border-[#151515]"
              />
            </div>
            <div className="flex flex-1 flex-col gap-3">
              <p className="font-medium">
                Image {i + 1}
                {i === 0 && " (cover)"}
              </p>
              <div className="flex flex-wrap gap-2">
                <button
                  type="button"
                  id={`move-${img.id}-up`}
                  disabled={busy !== null || i === 0}
                  onClick={() => void move(i, i - 1, `move-${img.id}-up`)}
                  aria-label={`Move image ${i + 1} up`}
                  className={secondaryButtonClass}
                >
                  Up
                </button>
                <button
                  type="button"
                  id={`move-${img.id}-down`}
                  disabled={busy !== null || i === images.length - 1}
                  onClick={() => void move(i, i + 1, `move-${img.id}-down`)}
                  aria-label={`Move image ${i + 1} down`}
                  className={secondaryButtonClass}
                >
                  Down
                </button>
                <button
                  type="button"
                  disabled={busy !== null}
                  onClick={() => void remove(img)}
                  aria-label={`Delete image ${i + 1}`}
                  className={dangerButtonClass}
                >
                  Delete
                </button>
              </div>
              <AltForm image={img} onChange={onChange} />
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}
