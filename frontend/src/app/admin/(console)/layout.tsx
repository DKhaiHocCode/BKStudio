"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState, type ReactNode } from "react";
import { ApiError, apiFetch } from "@/lib/api";
import { ErrorView } from "@/components/ErrorView";
import { AdminContext } from "../admin-context";
import { buttonClass } from "../ui";

// Client-side gate: the HttpOnly session cookie is unreadable here, and the backend enforces access anyway.
export default function ConsoleLayout({ children }: { children: ReactNode }) {
  const router = useRouter();
  const [email, setEmail] = useState<string | null>(null);
  const [failure, setFailure] = useState<unknown>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let cancelled = false;
    apiFetch<{ email: string }>("/api/admin/auth/me")
      .then((me) => {
        if (!cancelled) setEmail(me.email);
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        if (e instanceof ApiError && e.status === 401) router.replace("/admin/login");
        else setFailure(e);
      });
    return () => {
      cancelled = true;
    };
  }, [router, attempt]);

  async function logout() {
    try {
      await apiFetch("/api/admin/auth/logout", { method: "POST" });
      router.replace("/admin/login");
    } catch (e) {
      // Do not pretend to be signed out while the server session is still alive.
      setFailure(e);
    }
  }

  if (failure) {
    return (
      <ErrorView
        error={failure}
        retry={() => {
          setFailure(null);
          setAttempt((n) => n + 1);
        }}
      />
    );
  }
  if (email === null) {
    return (
      <p role="status" className="p-8 text-center font-medium">
        Checking your session...
      </p>
    );
  }

  return (
    <AdminContext value={{ email }}>
      <div className="flex items-center justify-between gap-3 border-b-[3px] border-[#151515] bg-[#fffdf8] px-4 py-2">
        <span className="font-extrabold">BKStudio Admin</span>
        <span className="flex items-center gap-3">
          <span className="hidden text-sm sm:inline">{email}</span>
          <button type="button" onClick={() => void logout()} className={buttonClass}>
            Log out
          </button>
        </span>
      </div>
      {children}
    </AdminContext>
  );
}
