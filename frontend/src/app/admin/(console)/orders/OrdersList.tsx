"use client";

import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import type { FormEvent } from "react";
import { ErrorView } from "@/components/ErrorView";
import {
  formatDateTime,
  ORDER_PAGE_SIZE,
  ORDER_STATUSES,
  orderListSchema,
  type OrderList,
} from "@/lib/adminOrders";
import { formatUsd } from "@/lib/money";
import { inputClass, secondaryButtonClass } from "../../ui";
import { useApiGet } from "../products/useApiGet";
import { OrderStatusBadge } from "./OrderStatusBadge";

export function OrdersList() {
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const status = params.get("status") ?? "";
  const q = params.get("q") ?? "";
  const page = Math.min(Math.max(Number.parseInt(params.get("page") ?? "0", 10) || 0, 0), 100000);

  const query = new URLSearchParams({ page: String(page) });
  if (status) query.set("status", status);
  if (q) query.set("q", q);
  const { data, error, loading, reload } = useApiGet<OrderList>(`/api/admin/orders?${query}`);

  function go(next: { status: string; q: string; page: number }) {
    const sp = new URLSearchParams();
    if (next.status) sp.set("status", next.status);
    if (next.q) sp.set("q", next.q);
    if (next.page > 0) sp.set("page", String(next.page));
    const qs = sp.toString();
    router.push(qs ? `${pathname}?${qs}` : pathname);
  }

  function onFilter(ev: FormEvent<HTMLFormElement>) {
    ev.preventDefault();
    const form = new FormData(ev.currentTarget);
    go({
      status: String(form.get("status") ?? ""),
      q: String(form.get("q") ?? "").trim(),
      page: 0,
    });
  }

  if (error) return <ErrorView error={error} retry={reload} />;

  // A shape mismatch means the API and the UI drifted apart.
  const parsed = data ? orderListSchema.safeParse(data) : null;
  if (parsed && !parsed.success) {
    return <ErrorView error={new Error("Unexpected order list response")} retry={reload} />;
  }
  const list = parsed?.success ? parsed.data : null;
  const lastPage = list ? Math.max(Math.ceil(list.total / ORDER_PAGE_SIZE) - 1, 0) : 0;

  return (
    <main className="mx-auto flex w-full max-w-5xl flex-col gap-4 px-4 py-8">
      <h1 className="text-2xl font-extrabold">Orders</h1>
      {/* key remounts the form so its defaults follow the URL (back/forward) */}
      <form key={`${status}|${q}`} onSubmit={onFilter} className="flex flex-wrap items-end gap-3">
        <label className="flex flex-col gap-1 font-medium">
          Status
          <select name="status" defaultValue={status} className={inputClass}>
            <option value="">All</option>
            {ORDER_STATUSES.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </label>
        <label className="flex min-w-48 flex-1 flex-col gap-1 font-medium">
          Order code (exact)
          <input name="q" maxLength={40} defaultValue={q} className={inputClass} />
        </label>
        <button type="submit" disabled={loading} className={secondaryButtonClass}>
          {loading ? "Loading..." : "Apply"}
        </button>
      </form>

      {loading || !list ? (
        <p role="status" className="py-8 text-center font-medium">
          Loading orders...
        </p>
      ) : list.items.length === 0 ? (
        <div
          role="status"
          className="flex flex-col items-center gap-3 py-8 text-center font-medium"
        >
          {page > lastPage ? "This page is past the last page." : "No orders match."}
          {page > 0 && (
            <button
              type="button"
              onClick={() => go({ status, q, page: 0 })}
              className={secondaryButtonClass}
            >
              Back to page 1
            </button>
          )}
        </div>
      ) : (
        <>
          <p className="text-sm text-muted">{list.total} orders</p>
          <div className="overflow-x-auto rounded-[22px] border-[3px] border-border bg-card">
            <table className="w-full min-w-[40rem] text-left">
              <thead>
                <tr className="border-b-[3px] border-border">
                  <th scope="col" className="p-3">
                    Code
                  </th>
                  <th scope="col" className="p-3">
                    Status
                  </th>
                  <th scope="col" className="p-3">
                    Type
                  </th>
                  <th scope="col" className="p-3">
                    Total
                  </th>
                  <th scope="col" className="p-3">
                    Created
                  </th>
                </tr>
              </thead>
              <tbody>
                {list.items.map((o) => (
                  <tr key={o.id} className="border-b border-line last:border-b-0">
                    <td className="p-3">
                      <Link
                        href={`/admin/orders/${o.id}`}
                        className="inline-flex min-h-11 items-center font-bold underline"
                      >
                        {o.publicCode}
                      </Link>
                    </td>
                    <td className="p-3">
                      <OrderStatusBadge status={o.status} />
                    </td>
                    <td className="p-3">{o.fulfillmentType}</td>
                    <td className="p-3 whitespace-nowrap">
                      {formatUsd(o.subtotalCents + o.shippingCents)}
                    </td>
                    <td className="p-3 whitespace-nowrap">{formatDateTime(o.createdAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}

      {list && list.total > ORDER_PAGE_SIZE && (
        <nav aria-label="Pagination" className="flex items-center justify-between gap-3">
          <button
            type="button"
            disabled={page === 0}
            onClick={() => go({ status, q, page: page - 1 })}
            className={secondaryButtonClass}
          >
            Previous
          </button>
          <span className="font-medium">
            Page {page + 1} of {lastPage + 1}
          </span>
          <button
            type="button"
            disabled={page >= lastPage}
            onClick={() => go({ status, q, page: page + 1 })}
            className={secondaryButtonClass}
          >
            Next
          </button>
        </nav>
      )}
    </main>
  );
}
