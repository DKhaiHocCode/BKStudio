"use client";

import Link from "next/link";

/** Pull a reference id out of whatever was thrown: our requestId, or Next.js's server-log digest. */
function referenceOf(error: unknown): string | undefined {
  if (typeof error !== "object" || error === null) return undefined;
  const e = error as { requestId?: unknown; digest?: unknown };
  if (typeof e.requestId === "string") return e.requestId;
  if (typeof e.digest === "string") return e.digest;
  return undefined;
}

export function ErrorView({
  error,
  retry,
  title = "Something went wrong",
}: {
  error: unknown;
  retry?: () => void;
  title?: string;
}) {
  const reference = referenceOf(error);

  return (
    <main className="mx-auto flex max-w-xl flex-1 flex-col items-center justify-center gap-4 px-4 py-16 text-center">
      <h1 className="text-2xl font-semibold">{title}</h1>
      <p className="text-neutral-600 dark:text-neutral-400">
        Sorry about that. Please try again, and if it keeps happening, contact us and quote the
        reference below.
      </p>
      {reference && (
        <p className="rounded bg-neutral-100 px-3 py-1 font-mono text-sm dark:bg-neutral-800">
          Reference: {reference}
        </p>
      )}
      <div className="flex gap-3">
        {retry && (
          <button
            type="button"
            onClick={() => retry()}
            className="rounded bg-foreground px-4 py-2 text-background"
          >
            Try again
          </button>
        )}
        <Link href="/" className="rounded border border-current px-4 py-2">
          Back to home
        </Link>
      </div>
    </main>
  );
}
