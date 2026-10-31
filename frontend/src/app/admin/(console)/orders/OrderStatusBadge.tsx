function tintOf(status: string): string {
  if (status.startsWith("CANCELLED") || status === "FORFEITED") return "bg-tint-pink";
  if (status === "COMPLETED" || status === "PAID_IN_FULL" || status === "SHIPPED") {
    return "bg-tint-stock";
  }
  if (status.startsWith("AWAITING")) return "bg-tint-commission";
  return "bg-tint-neutral";
}

export function OrderStatusBadge({ status }: { status: string }) {
  return (
    <span
      className={`inline-block rounded-full border-2 border-border px-2 py-0.5 text-xs font-bold text-ink ${tintOf(status)}`}
    >
      {status}
    </span>
  );
}
