"use client";

import "./globals.css";
import { ErrorView } from "@/components/ErrorView";
import { THEME_INIT } from "@/lib/theme";

/** Last resort: replaces the root layout, so it must render its own <html> and <body>. */
export default function GlobalError({ error, retry }: { error: unknown; retry: () => void }) {
  return (
    <html lang="en" suppressHydrationWarning>
      <head>
        <script>{THEME_INIT}</script>
      </head>
      <body className="flex min-h-full flex-col">
        <ErrorView error={error} retry={retry} />
      </body>
    </html>
  );
}
