"use client";

import "./globals.css";
import { ErrorView } from "@/components/ErrorView";

/** Last resort: replaces the root layout, so it must render its own <html> and <body>. */
export default function GlobalError({ error, retry }: { error: unknown; retry: () => void }) {
  return (
    <html lang="en">
      <body className="flex min-h-full flex-col">
        <ErrorView error={error} retry={retry} />
      </body>
    </html>
  );
}
