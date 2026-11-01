"use client";

import { useState } from "react";
import { ErrorView } from "@/components/ErrorView";
import { ApiError } from "@/lib/api";
import { currentTermsSchema, settingsSchema } from "@/lib/adminSettings";
import { useApiGet } from "../products/useApiGet";
import { useFlash } from "../products/useFlash";
import { SiteSettingsForm } from "./SiteSettingsForm";
import { TermsSection } from "./TermsSection";

export function SettingsView() {
  const settings = useApiGet<unknown>("/api/admin/settings");
  const terms = useApiGet<unknown>("/api/terms/current");
  const [flashMsg, flash] = useFlash();
  // Set from the POST response so the form stays mounted and no stale cached GET is refetched.
  const [published, setPublished] = useState<{ version: string; contentMd: string } | null>(null);

  if (settings.error) return <ErrorView error={settings.error} retry={settings.reload} />;
  const noTerms = terms.error instanceof ApiError && terms.error.status === 404;
  if (terms.error && !noTerms) return <ErrorView error={terms.error} retry={terms.reload} />;

  const parsed = settings.data === undefined ? null : settingsSchema.safeParse(settings.data);
  if (parsed && !parsed.success) {
    return <ErrorView error={new Error("Unexpected settings response")} retry={settings.reload} />;
  }
  const t = terms.data === undefined ? null : currentTermsSchema.safeParse(terms.data);
  if (t && !t.success) {
    return <ErrorView error={new Error("Unexpected terms response")} retry={terms.reload} />;
  }

  const currentTerms = published ?? (t?.success ? t.data : null);

  return (
    <main className="mx-auto flex w-full max-w-3xl flex-col gap-4 px-4 py-8">
      <h1 className="text-2xl font-extrabold">Settings</h1>
      <p role="status" className="min-h-6 font-medium">
        {flashMsg}
      </p>
      {!parsed?.success || (!noTerms && !currentTerms) ? (
        <p role="status">Loading...</p>
      ) : (
        <>
          <SiteSettingsForm
            key={JSON.stringify(parsed.data)}
            initial={parsed.data}
            onSaved={(saved) => {
              settings.update(() => saved);
              flash("Settings saved.");
            }}
          />
          <TermsSection
            key={currentTerms?.version ?? "none"}
            current={currentTerms}
            onPublished={(p) => {
              setPublished(p);
              flash("Terms published.");
            }}
          />
        </>
      )}
    </main>
  );
}
