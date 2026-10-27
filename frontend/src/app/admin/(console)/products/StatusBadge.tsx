import type { ProductStatus } from "./types";

const TINT: Record<ProductStatus, string> = {
  DRAFT: "bg-[#e4dfd6]",
  ACTIVE: "bg-[#bfe6c6]",
  UNLISTED: "bg-[#bde3f7]",
  ARCHIVED: "bg-[#f8c9be]",
};

export function StatusBadge({ status }: { status: ProductStatus }) {
  return (
    <span
      className={`inline-block rounded-full border-2 border-[#151515] px-2 py-0.5 text-xs font-bold ${TINT[status]}`}
    >
      {status}
    </span>
  );
}
