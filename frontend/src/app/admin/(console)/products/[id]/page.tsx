"use client";

import Link from "next/link";
import { use } from "react";
import { ApiError } from "@/lib/api";
import { ErrorView } from "@/components/ErrorView";
import { linkButtonClass } from "../../../ui";
import { CopyLinkButton } from "../CopyLinkButton";
import { ImagesSection } from "../ImagesSection";
import { ProductForm } from "../ProductForm";
import { StatusBadge } from "../StatusBadge";
import type { ProductDetail } from "../types";
import { useApiGet } from "../useApiGet";
import { VariantsSection } from "../VariantsSection";

export default function EditProductPage({ params }: PageProps<"/admin/products/[id]">) {
  const { id } = use(params);
  const { data, error, loading, reload, update } = useApiGet<ProductDetail>(
    `/api/admin/products/${encodeURIComponent(id)}`,
  );

  if (error) {
    const missing = error instanceof ApiError && error.status === 404;
    return (
      <ErrorView error={error} retry={reload} title={missing ? "Product not found" : undefined} />
    );
  }
  if (loading || !data) {
    return (
      <p role="status" className="p-8 text-center font-medium">
        Loading product...
      </p>
    );
  }

  return (
    <main className="mx-auto flex w-full max-w-3xl flex-col gap-6 px-4 py-8">
      <Link href="/admin/products" className={`${linkButtonClass} self-start py-2`}>
        Back to products
      </Link>
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-2xl font-extrabold break-words">{data.name}</h1>
        <StatusBadge status={data.status} />
      </div>
      {data.status === "UNLISTED" && <CopyLinkButton slug={data.slug} />}
      <ProductForm
        initial={data}
        onSaved={(s) =>
          // Product fields only: variants/images may have changed since the PUT was sent.
          update((d) => ({
            ...d,
            name: s.name,
            slug: s.slug,
            series: s.series,
            description: s.description,
            status: s.status,
            version: s.version,
            updatedAt: s.updatedAt,
          }))
        }
        onReload={reload}
      />
      <VariantsSection
        productId={data.id}
        variants={data.variants}
        onChange={(fn) => update((d) => ({ ...d, variants: fn(d.variants) }))}
        onReload={reload}
      />
      <ImagesSection
        productId={data.id}
        images={data.images}
        onChange={(fn) => update((d) => ({ ...d, images: fn(d.images) }))}
      />
    </main>
  );
}
