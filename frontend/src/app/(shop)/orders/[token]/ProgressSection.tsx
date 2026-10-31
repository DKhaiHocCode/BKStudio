"use client";

import { useEffect, useState } from "react";
import { Notice } from "@/components/shop/Notice";
import { focusRing, panel } from "@/components/ui";
import { apiFetch } from "@/lib/api";
import { formatDate, imageUrl, progressSchema, type StageView } from "@/lib/orders";

type Progress = { kind: "loading" } | { kind: "error" } | { kind: "ready"; stages: StageView[] };

const STATE_TEXT = { DONE: "Done", CURRENT: "In progress", PENDING: "Upcoming" } as const;
const STATE_MARK = { DONE: "✓", CURRENT: "●", PENDING: "○" } as const;
const STATE_BG = {
  DONE: "bg-tint-stock text-ink",
  CURRENT: "bg-citrus text-ink",
  PENDING: "bg-card text-fg",
} as const;

/** Made-to-order only (the parent never mounts this for in-stock orders). Fetched on mount and when `reloadKey` changes. */
export function ProgressSection({ token, reloadKey }: { token: string; reloadKey: number }) {
  const [progress, setProgress] = useState<Progress>({ kind: "loading" });
  const [selected, setSelected] = useState<string | null>(null);

  useEffect(() => {
    let stale = false;
    apiFetch<unknown>(`/api/orders/${token}/progress`, { cache: "no-store" })
      .then((body): Progress => {
        const parsed = progressSchema.safeParse(body);
        return parsed.success ? { kind: "ready", stages: parsed.data.stages } : { kind: "error" };
      })
      .catch((): Progress => ({ kind: "error" }))
      .then((result) => {
        if (!stale) setProgress(result);
      });
    return () => {
      stale = true;
    };
  }, [token, reloadKey]);

  if (progress.kind === "loading") {
    return (
      <section aria-labelledby="progress-h" className={panel}>
        <h2 id="progress-h" className="m-0 text-xl font-extrabold">
          Production progress
        </h2>
        <p role="status" className="m-0 mt-3 min-h-24 font-bold text-muted">
          Loading progress…
        </p>
      </section>
    );
  }
  if (progress.kind === "error") {
    return (
      <section aria-labelledby="progress-h" className="flex flex-col gap-3">
        <h2 id="progress-h" className="m-0 text-xl font-extrabold">
          Production progress
        </h2>
        <Notice tone="info">
          <p className="m-0">Production progress is not available right now. Try Refresh later.</p>
        </Notice>
      </section>
    );
  }

  const current = progress.stages.find((s) => s.stage === selected);
  const photos = (current?.images ?? []).flatMap((img) => {
    const src = imageUrl(img.url);
    return src ? [{ ...img, src }] : [];
  });

  return (
    <section aria-labelledby="progress-h" className={`${panel} flex flex-col gap-4`}>
      <h2 id="progress-h" className="m-0 text-xl font-extrabold">
        Production progress
      </h2>
      <ol className="m-0 grid list-none grid-cols-2 p-0 pr-[3px] pb-[3px] sm:grid-cols-3 lg:grid-cols-6">
        {progress.stages.map((s, i) => (
          <li key={s.stage} className="-mr-[3px] -mb-[3px] flex">
            <button
              type="button"
              aria-expanded={selected === s.stage}
              aria-controls="stage-panel"
              onClick={() => setSelected(selected === s.stage ? null : s.stage)}
              className={`flex min-h-24 w-full min-w-0 cursor-pointer flex-col items-start gap-1 border-[3px] border-border p-3 text-left font-[inherit] focus-visible:z-10 ${STATE_BG[s.state]} ${selected === s.stage ? "shadow-[inset_0_-6px_0_var(--border)]" : ""} ${focusRing}`}
            >
              <span className="text-xs font-bold">Step {i + 1}</span>
              <span className="font-extrabold break-words">{s.label}</span>
              <span className="text-sm font-bold">
                <span aria-hidden="true">{STATE_MARK[s.state]} </span>
                {STATE_TEXT[s.state]}
              </span>
            </button>
          </li>
        ))}
      </ol>
      <div id="stage-panel" aria-live="polite" className="flex min-w-0 flex-col gap-3">
        {current ? (
          <>
            <h3 className="m-0 text-lg font-extrabold">
              {current.label} · {STATE_TEXT[current.state]}
              {current.approvedAt && formatDate(current.approvedAt)
                ? ` · ${formatDate(current.approvedAt)}`
                : ""}
            </h3>
            <p className="m-0 break-words">{current.explanation}</p>
            {current.note && (
              <p className="m-0 break-words">
                <span className="font-bold">Note from the shop: </span>
                {current.note}
              </p>
            )}
            {photos.length > 0 && (
              <ul className="m-0 grid list-none grid-cols-1 gap-3 p-0 sm:grid-cols-2">
                {photos.map((p, i) => (
                  <li key={p.src} className="min-w-0">
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    <img
                      src={p.src}
                      alt={`${current.label} progress photo ${i + 1} of ${photos.length}`}
                      width={p.width}
                      height={p.height}
                      loading="lazy"
                      referrerPolicy="no-referrer"
                      className="block h-auto w-full max-w-full rounded-[14px] border-[3px] border-border"
                    />
                  </li>
                ))}
              </ul>
            )}
          </>
        ) : (
          <p className="m-0 text-muted">Select a step to see what it involves.</p>
        )}
      </div>
    </section>
  );
}
