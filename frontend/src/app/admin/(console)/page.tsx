"use client";

import { useAdmin } from "../admin-context";

// Placeholder; the real dashboard is T-034.
export default function AdminHomePage() {
  const { email } = useAdmin();
  return (
    <main className="mx-auto w-full max-w-5xl px-4 py-8">
      <h1 className="text-2xl font-extrabold">Admin</h1>
      <p className="mt-2">Signed in as {email}</p>
    </main>
  );
}
