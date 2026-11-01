"use client";

import Link from "next/link";
import { ErrorView } from "@/components/ErrorView";
import { dashboardSchema, GROUP_INFO } from "@/lib/adminDashboard";
import { formatDateTime } from "@/lib/adminOrders";
import { panelClass, secondaryButtonClass } from "../ui";
import { OrderStatusBadge } from "./orders/OrderStatusBadge";
import { useApiGet } from "./products/useApiGet";

export default function AdminHomePage() {
  const { data, error, loading, reload } = useApiGet<unknown>("/api/admin/dashboard");

  if (error) return <ErrorView error={error} retry={reload} />;
  if (loading) {
    return (
      <p role="status" className="p-8 text-center font-medium">
        Loading dashboard...
      </p>
    );
  }
  const parsed = dashboardSchema.safeParse(data);
  if (!parsed.success) {
    return <ErrorView error={new Error("Unexpected dashboard response")} retry={reload} />;
  }
  const { generatedAt, groups } = parsed.data;
  const generatedMs = new Date(generatedAt).getTime();

  return (
    <main className="mx-auto flex w-full max-w-5xl flex-col gap-4 px-4 py-8">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-extrabold">Dashboard</h1>
        <span className="flex items-center gap-3 text-sm">
          Updated {formatDateTime(generatedAt)}
          <button type="button" onClick={reload} className={secondaryButtonClass}>
            Refresh
          </button>
        </span>
      </div>
      <div className="grid gap-4 md:grid-cols-2">
        {groups.map((g) => {
          const info = GROUP_INFO[g.key] ?? { title: g.key, hint: "" };
          const isEmail = g.key === "EMAIL_FAILED";
          return (
            <section
              key={g.key}
              className={`${panelClass} ${g.count === 0 ? "opacity-60" : ""}`}
              aria-labelledby={`grp-${g.key}`}
            >
              <div className="flex items-baseline justify-between gap-3">
                <h2 id={`grp-${g.key}`} className="text-lg font-extrabold">
                  {info.title}
                </h2>
                <span className="text-2xl font-extrabold">{g.count}</span>
              </div>
              <p className="text-sm text-muted">{info.hint}</p>
              {g.count > 0 && (
                <>
                  <ul className="mt-3 flex flex-col gap-2">
                    {g.items.map((it) => {
                      const overdue =
                        (g.key === "PAYMENT_EXPIRING" || g.key === "PAYMENT_TO_CONFIRM") &&
                        it.dueAt !== null &&
                        new Date(it.dueAt).getTime() < generatedMs;
                      return (
                        <li key={it.id} className="flex flex-wrap items-center gap-2 text-sm">
                          {isEmail ? (
                            <>
                              <span className="font-mono font-bold">{it.status}</span>
                              <span>{formatDateTime(it.createdAt)}</span>
                            </>
                          ) : (
                            <>
                              <Link
                                href={`/admin/orders/${it.id}`}
                                className="inline-flex min-h-11 items-center font-mono font-bold underline"
                              >
                                {it.publicCode}
                              </Link>
                              <OrderStatusBadge status={it.status} />
                              {it.dueAt && (
                                <span className={overdue ? "font-bold text-danger" : ""}>
                                  {overdue ? "Overdue: " : "Due "}
                                  {formatDateTime(it.dueAt)}
                                </span>
                              )}
                            </>
                          )}
                        </li>
                      );
                    })}
                  </ul>
                  {g.count > g.items.length && (
                    <p className="mt-2 text-sm font-medium">
                      Showing {g.items.length} of {g.count}
                    </p>
                  )}
                </>
              )}
            </section>
          );
        })}
      </div>
    </main>
  );
}
