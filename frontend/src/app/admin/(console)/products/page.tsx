"use client";

import Link from "next/link";
import { useState, type FormEvent } from "react";
import { ErrorView } from "@/components/ErrorView";
import { buttonClass, inputClass, secondaryButtonClass } from "../../ui";
import { StatusBadge } from "./StatusBadge";
import { PRODUCT_STATUSES, type ProductPage } from "./types";
import { useApiGet } from "./useApiGet";

const PAGE_SIZE = 20;

export default function ProductsPage() {
  const [filters, setFilters] = useState({ status: "", q: "", page: 0 });
  const query = new URLSearchParams({ page: String(filters.page), size: String(PAGE_SIZE) });
  if (filters.status) query.set("status", filters.status);
  if (filters.q) query.set("q", filters.q);
  const { data, error, loading, reload } = useApiGet<ProductPage>(`/api/admin/products?${query}`);

  function onFilter(ev: FormEvent<HTMLFormElement>) {
    ev.preventDefault();
    const form = new FormData(ev.currentTarget);
    setFilters({
      status: String(form.get("status") ?? ""),
      q: String(form.get("q") ?? "").trim(),
      page: 0,
    });
  }

  if (error) return <ErrorView error={error} retry={reload} />;

  const lastPage = data ? Math.max(Math.ceil(data.total / data.size) - 1, 0) : 0;

  return (
    <main className="mx-auto flex w-full max-w-5xl flex-col gap-4 px-4 py-8">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-extrabold">Products</h1>
        <Link href="/admin/products/new" className={`${buttonClass} inline-flex items-center`}>
          New product
        </Link>
      </div>
      <form onSubmit={onFilter} className="flex flex-wrap items-end gap-3">
        <label className="flex flex-col gap-1 font-medium">
          Status
          <select name="status" defaultValue={filters.status} className={inputClass}>
            <option value="">All</option>
            {PRODUCT_STATUSES.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </label>
        <label className="flex min-w-48 flex-1 flex-col gap-1 font-medium">
          Search name or slug
          <input name="q" maxLength={200} defaultValue={filters.q} className={inputClass} />
        </label>
        <button type="submit" disabled={loading} className={secondaryButtonClass}>
          {loading ? "Loading..." : "Apply"}
        </button>
      </form>

      {loading || !data ? (
        <p role="status" className="py-8 text-center font-medium">
          Loading products...
        </p>
      ) : data.items.length === 0 ? (
        <p role="status" className="py-8 text-center font-medium">
          No products match.
        </p>
      ) : (
        <div className="overflow-x-auto rounded-[22px] border-[3px] border-border bg-card">
          <table className="w-full min-w-[40rem] text-left">
            <thead>
              <tr className="border-b-[3px] border-border">
                <th scope="col" className="p-3">
                  Cover
                </th>
                <th scope="col" className="p-3">
                  Name
                </th>
                <th scope="col" className="p-3">
                  Status
                </th>
                <th scope="col" className="p-3">
                  Variants
                </th>
                <th scope="col" className="p-3">
                  Images
                </th>
                <th scope="col" className="p-3">
                  Updated
                </th>
              </tr>
            </thead>
            <tbody>
              {data.items.map((p) => (
                <tr key={p.id} className="border-b border-line last:border-b-0">
                  <td className="p-3">
                    {p.coverImage ? (
                      // eslint-disable-next-line @next/next/no-img-element -- storage URLs are not configured for next/image
                      <img
                        src={p.coverImage.url}
                        alt={p.coverImage.alt ?? ""}
                        width={56}
                        height={56}
                        className="size-14 rounded-[14px] border-2 border-border object-cover"
                      />
                    ) : (
                      <span className="text-sm text-muted">None</span>
                    )}
                  </td>
                  <td className="p-3">
                    <Link
                      href={`/admin/products/${p.id}`}
                      className="inline-flex min-h-11 items-center font-bold underline"
                    >
                      {p.name}
                    </Link>
                    {p.series && <div className="text-sm text-muted">{p.series}</div>}
                  </td>
                  <td className="p-3">
                    <StatusBadge status={p.status} />
                  </td>
                  <td className="p-3">{p.variantCount}</td>
                  <td className="p-3">{p.imageCount}</td>
                  <td className="p-3 whitespace-nowrap">
                    {new Date(p.updatedAt).toLocaleString("en-GB", {
                      dateStyle: "medium",
                      timeStyle: "short",
                    })}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {data && data.total > data.size && (
        <nav aria-label="Pagination" className="flex items-center justify-between gap-3">
          <button
            type="button"
            disabled={filters.page === 0}
            onClick={() => setFilters((f) => ({ ...f, page: f.page - 1 }))}
            className={secondaryButtonClass}
          >
            Previous
          </button>
          <span className="font-medium">
            Page {filters.page + 1} of {lastPage + 1}
          </span>
          <button
            type="button"
            disabled={filters.page >= lastPage}
            onClick={() => setFilters((f) => ({ ...f, page: f.page + 1 }))}
            className={secondaryButtonClass}
          >
            Next
          </button>
        </nav>
      )}
    </main>
  );
}
