import type { ProductStatus } from "./types";

const TINT: Record<ProductStatus, string> = {
  DRAFT: "bg-tint-neutral",
  ACTIVE: "bg-tint-stock",
  UNLISTED: "bg-tint-commission",
  ARCHIVED: "bg-tint-pink",
};

export function StatusBadge({ status }: { status: ProductStatus }) {
  return (
    <span
      className={`inline-block rounded-full border-2 border-border px-2 py-0.5 text-xs font-bold text-ink ${TINT[status]}`}
    >
      {status}
    </span>
  );
}
