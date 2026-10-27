"use client";

import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { ApiError, apiFetch } from "@/lib/api";
import { ThemeToggle } from "@/components/ThemeToggle";
import { buttonClass, cardClass, inputClass, linkButtonClass } from "../ui";

/** Same text for every auth failure, so the form never reveals whether an email exists. */
function messageOf(e: unknown): string {
  if (!(e instanceof ApiError)) return "Something went wrong. Please try again.";
  if (e.code === "RATE_LIMITED") {
    const wait = e.retryAfter ? ` Try again in ${Math.ceil(e.retryAfter / 60)} min.` : "";
    return `Too many attempts.${wait}`;
  }
  if (e.status === 401 || e.status === 400)
    return "Sign-in failed. Check your details and try again.";
  return e.message;
}

export default function AdminLoginPage() {
  const router = useRouter();
  const [step, setStep] = useState<"credentials" | "totp">("credentials");
  const [useRecovery, setUseRecovery] = useState(false);
  const [error, setError] = useState<{ text: string; requestId?: string } | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function run(action: () => Promise<void>) {
    setError(null);
    setSubmitting(true);
    try {
      await action();
    } catch (e) {
      setError({ text: messageOf(e), requestId: e instanceof ApiError ? e.requestId : undefined });
    } finally {
      setSubmitting(false);
    }
  }

  async function post(path: string, body: object) {
    await apiFetch(path, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
  }

  function onCredentials(ev: FormEvent<HTMLFormElement>) {
    ev.preventDefault();
    const form = new FormData(ev.currentTarget);
    const email = String(form.get("email") ?? "").trim();
    const password = String(form.get("password") ?? "");
    if (!email || !password) return setError({ text: "Enter your email and password." });
    void run(async () => {
      await post("/api/admin/auth/login", { email, password });
      setStep("totp");
    });
  }

  function onTotp(ev: FormEvent<HTMLFormElement>) {
    ev.preventDefault();
    const value = String(new FormData(ev.currentTarget).get("secondFactor") ?? "").trim();
    if (!useRecovery && !/^\d{6}$/.test(value))
      return setError({ text: "Enter the 6-digit code." });
    if (useRecovery && !value) return setError({ text: "Enter a recovery code." });
    // Exactly one of code / recoveryCode is sent.
    const body = useRecovery ? { recoveryCode: value } : { code: value };
    void run(async () => {
      try {
        await post("/api/admin/auth/totp", body);
      } catch (e) {
        // The pending session (5 min) is gone: retrying the code can never work, so start over.
        if (e instanceof ApiError && e.status === 401) setStep("credentials");
        throw e;
      }
      router.replace("/admin");
    });
  }

  return (
    <main className="relative flex flex-1 items-center justify-center px-4 py-12">
      <ThemeToggle className="absolute top-3 right-3" />
      <div className={cardClass}>
        <h1 className="mb-4 text-2xl font-extrabold">Admin sign in</h1>
        {error && (
          <p
            role="alert"
            className="mb-4 rounded-[14px] border-[3px] border-border bg-tint-pink p-3 text-sm text-ink"
          >
            {error.text}
            {error.requestId && (
              <span className="block font-mono">Reference: {error.requestId}</span>
            )}
          </p>
        )}
        {step === "credentials" ? (
          <form onSubmit={onCredentials} className="flex flex-col gap-4" noValidate>
            <label className="flex flex-col gap-1 font-medium">
              Email
              <input
                name="email"
                type="email"
                autoComplete="username"
                required
                className={inputClass}
              />
            </label>
            <label className="flex flex-col gap-1 font-medium">
              Password
              <input
                name="password"
                type="password"
                autoComplete="current-password"
                required
                className={inputClass}
              />
            </label>
            <button type="submit" disabled={submitting} className={buttonClass}>
              {submitting ? "Signing in..." : "Continue"}
            </button>
          </form>
        ) : (
          // key resets the input when switching between code and recovery code
          <form
            key={String(useRecovery)}
            onSubmit={onTotp}
            className="flex flex-col gap-4"
            noValidate
          >
            <label className="flex flex-col gap-1 font-medium">
              {useRecovery ? "Recovery code" : "6-digit authenticator code"}
              <input
                name="secondFactor"
                type="text"
                required
                autoFocus
                inputMode={useRecovery ? "text" : "numeric"}
                autoComplete={useRecovery ? "off" : "one-time-code"}
                maxLength={useRecovery ? 64 : 6}
                className={inputClass}
              />
            </label>
            <button type="submit" disabled={submitting} className={buttonClass}>
              {submitting ? "Verifying..." : "Verify"}
            </button>
            <button
              type="button"
              className={linkButtonClass}
              onClick={() => {
                setError(null);
                setUseRecovery(!useRecovery);
              }}
            >
              {useRecovery ? "Use authenticator code" : "Use a recovery code"}
            </button>
          </form>
        )}
      </div>
    </main>
  );
}
