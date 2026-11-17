"use client";

import { useEffect, useImperativeHandle, useRef, useState, type Ref } from "react";
import { secondaryButton } from "@/components/ui";
import { CONTACT_EMAIL } from "@/lib/contact";

/**
 * Cloudflare Turnstile (bot check), explicit rendering.
 * https://developers.cloudflare.com/turnstile/get-started/client-side-rendering/
 *
 * The site key is public (NEXT_PUBLIC_TURNSTILE_SITE_KEY); the secret key lives only in the backend.
 * A token is valid for 300 seconds and can be verified once, so checkout resets the widget after
 * every failed submit and the order lookup form after every submit. Never log the token.
 *
 * Cloudflare's dummy site keys for dev/tests (work on any host, never use in production):
 *   1x00000000000000000000AA  always passes (visible)
 *   2x00000000000000000000AB  always fails (visible)
 *   3x00000000000000000000FF  forces an interactive challenge
 * They produce the token XXXX.DUMMY.TOKEN.XXXX, which only the dummy secret keys accept.
 */
export const TURNSTILE_SITE_KEY = process.env.NEXT_PUBLIC_TURNSTILE_SITE_KEY?.trim() ?? "";

const SCRIPT_URL = "https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit";

interface Turnstile {
  render(
    container: HTMLElement,
    options: {
      sitekey: string;
      theme: "auto" | "light" | "dark";
      size: "flexible" | "compact";
      callback: (token: string) => void;
      "error-callback": (code: string) => boolean;
      "expired-callback": () => void;
    },
  ): string | null | undefined;
  reset(widgetId: string): void;
  remove(widgetId: string): void;
}

declare global {
  interface Window {
    turnstile?: Turnstile;
  }
}

let script: Promise<void> | undefined;

/** Adds api.js once per page; a failed load can be retried. */
function loadScript(): Promise<void> {
  script ??= new Promise<void>((resolve, reject) => {
    const el = document.createElement("script");
    el.src = SCRIPT_URL;
    el.async = true;
    el.onload = () => resolve();
    el.onerror = () => {
      el.remove();
      script = undefined;
      reject(new Error("Turnstile script failed to load"));
    };
    document.head.append(el);
  });
  return script;
}

const MESSAGE = {
  // Also shown when the visitor's browser or network blocks challenges.cloudflare.com.
  error: `The check could not run. Check your connection, then try again. If it keeps failing, email ${CONTACT_EMAIL}.`,
  expired: "The check expired. Please complete it again.",
} as const;

export interface TurnstileHandle {
  /** Drops the current token and runs a new challenge. */
  reset: () => void;
}

export function TurnstileWidget({
  siteKey,
  onToken,
  ref,
}: {
  siteKey: string;
  /** Receives the token, or null when there is none (expired, error, reset). Must be stable. */
  onToken: (token: string | null) => void;
  ref?: Ref<TurnstileHandle>;
}) {
  const box = useRef<HTMLDivElement>(null);
  const widgetId = useRef<string | null>(null);
  const [issue, setIssue] = useState<keyof typeof MESSAGE | null>(null);
  const [attempt, setAttempt] = useState(0); // bump = load and render again

  useEffect(() => {
    let cancelled = false;
    const fail = () => {
      if (cancelled) return;
      onToken(null);
      setIssue("error");
    };
    const show = () => {
      const el = box.current;
      if (cancelled || !el || !window.turnstile) return fail();
      // The site theme toggle (data-theme) wins over the system preference that "auto" follows.
      const theme = document.documentElement.dataset.theme;
      widgetId.current =
        window.turnstile.render(el, {
          sitekey: siteKey,
          theme: theme === "light" || theme === "dark" ? theme : "auto",
          // "flexible" needs 300px; the summary panel is a little narrower on a 375px phone.
          size: el.clientWidth < 300 ? "compact" : "flexible",
          callback: (token) => {
            setIssue(null);
            onToken(token);
          },
          "error-callback": () => {
            fail();
            return true; // handled here: Turnstile skips its console warning and keeps retrying
          },
          "expired-callback": () => {
            onToken(null);
            setIssue("expired");
          },
        }) ?? null;
      if (!widgetId.current) fail();
    };
    // catch after then: turnstile.render throws on a bad site key or bad parameters.
    loadScript().then(show).catch(fail);
    return () => {
      cancelled = true;
      if (widgetId.current) window.turnstile?.remove(widgetId.current);
      widgetId.current = null;
    };
  }, [siteKey, onToken, attempt]);

  function reset() {
    onToken(null);
    setIssue(null);
    if (widgetId.current && window.turnstile) window.turnstile.reset(widgetId.current);
    else setAttempt((n) => n + 1);
  }
  useImperativeHandle(ref, () => ({ reset }));

  return (
    <section aria-labelledby="turnstile-title" className="flex min-w-0 flex-col gap-2">
      {/* A paragraph, not a heading: the lookup page has no h2 above this. */}
      <p id="turnstile-title" className="m-0 text-[15px] font-bold">
        Security check
      </p>
      <div ref={box} className="min-h-[65px]" />
      <div role="alert">
        {issue && (
          <div className="flex flex-wrap items-center gap-3">
            <p className="m-0 text-sm font-bold text-danger">{MESSAGE[issue]}</p>
            <button type="button" onClick={reset} className={secondaryButton}>
              Try again
            </button>
          </div>
        )}
      </div>
    </section>
  );
}
