"use client";

import Link from "next/link";
import { use, useMemo, useState, type FormEvent, type ReactNode } from "react";
import { ErrorView } from "@/components/ErrorView";
import { ApiError, apiFetch } from "@/lib/api";
import { adminOrderSchema, formatDateTime, noteSchema, type AdminOrder } from "@/lib/adminOrders";
import { formatUsd } from "@/lib/money";
import { linkButtonClass, panelClass, secondaryButtonClass } from "../../../ui";
import { ErrorBanner } from "../../products/ErrorBanner";
import { Field } from "../../products/Field";
import { useApiGet } from "../../products/useApiGet";
import { useFlash } from "../../products/useFlash";
import { OrderStatusBadge } from "../OrderStatusBadge";
import { OrderActions } from "./OrderActions";
import { PaymentRequestActions } from "./PaymentRequestActions";
import { PaymentRequestForm } from "./PaymentRequestForm";
import { ProgressApproval } from "./ProgressApproval";

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className={`${panelClass} flex flex-col gap-3`}>
      <h2 className="m-0 text-xl font-extrabold">{title}</h2>
      {children}
    </section>
  );
}

function Row({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="flex flex-wrap gap-x-3">
      <dt className="min-w-32 font-bold">{label}</dt>
      <dd className="m-0 break-words">{value || "-"}</dd>
    </div>
  );
}

function NoteEditor({
  order,
  onSaved,
}: {
  order: AdminOrder;
  onSaved: (adminNote: string) => void;
}) {
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [invalid, setInvalid] = useState<string>();

  async function save(ev: FormEvent<HTMLFormElement>) {
    ev.preventDefault();
    const parsed = noteSchema.safeParse({
      adminNote: String(new FormData(ev.currentTarget).get("adminNote") ?? ""),
    });
    if (!parsed.success) {
      setInvalid(parsed.error.issues[0]?.message);
      return;
    }
    setInvalid(undefined);
    setSaving(true);
    setError(null);
    try {
      await apiFetch(`/api/admin/orders/${order.id}/note`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(parsed.data),
      });
      onSaved(parsed.data.adminNote);
    } catch (e) {
      setError(e);
    } finally {
      setSaving(false);
    }
  }

  return (
    <form onSubmit={(ev) => void save(ev)} noValidate className="flex flex-col gap-3">
      <Field label="Admin note" error={invalid} hint="Only admins see this. Empty clears it.">
        <textarea
          name="adminNote"
          rows={4}
          maxLength={2000}
          defaultValue={order.adminNote ?? ""}
          aria-invalid={Boolean(invalid)}
          className="w-full rounded-[14px] border-[3px] border-border bg-card px-3 py-2 text-base text-fg"
        />
      </Field>
      <ErrorBanner error={error} />
      <div>
        <button type="submit" disabled={saving} className={secondaryButtonClass}>
          {saving ? "Saving..." : "Save note"}
        </button>
      </div>
    </form>
  );
}

function OrderDetail({
  order,
  reload,
  onNoteSaved,
}: {
  order: AdminOrder;
  reload: () => void;
  onNoteSaved: (adminNote: string) => void;
}) {
  const [flashMessage, flash] = useFlash();
  const [staleError, setStaleError] = useState<unknown>(null);
  const c = order.customer;
  const done = (message: string) => {
    setStaleError(null);
    flash(message);
    reload();
  };
  const stale = (e: unknown) => {
    setStaleError(e);
    reload();
  };

  return (
    <main id="main" className="mx-auto flex w-full max-w-4xl flex-col gap-4 px-4 py-8">
      <Link
        href="/admin/orders"
        className={`${linkButtonClass} inline-flex items-center self-start`}
      >
        Back to orders
      </Link>
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-2xl font-extrabold">{order.publicCode}</h1>
        <OrderStatusBadge status={order.status} />
        <span className="text-sm text-muted">{order.fulfillmentType}</span>
      </div>
      <p role="status" className="m-0 min-h-6 font-bold">
        {flashMessage}
      </p>
      <ErrorBanner error={staleError} onReload={reload} />

      <Section title="Actions">
        <OrderActions order={order} onDone={done} onStale={stale} />
        <PaymentRequestForm order={order} onDone={done} onStale={stale} />
      </Section>

      {order.fulfillmentType === "MADE_TO_ORDER" && order.status === "IN_PRODUCTION" && (
        <Section title="Approve production stage">
          <ProgressApproval order={order} onDone={done} onStale={stale} />
        </Section>
      )}

      <Section title="Customer">
        <dl className="m-0 flex flex-col gap-1">
          <Row label="Name" value={c.name} />
          <Row label="Email" value={c.email} />
          <Row label="Phone" value={c.phone} />
          <Row
            label="Address"
            value={[c.line1, c.line2, c.city, c.region, c.postalCode, c.country]
              .filter(Boolean)
              .join(", ")}
          />
        </dl>
      </Section>

      <Section title="Items">
        <ul className="m-0 flex list-none flex-col gap-1 p-0">
          {order.items.map((i) => (
            <li key={i.id} className="flex flex-wrap justify-between gap-2">
              <span>
                {i.productName} ({i.scale}) x {i.quantity}
              </span>
              <span>{formatUsd(i.unitPriceCents * i.quantity)}</span>
            </li>
          ))}
        </ul>
        <dl className="m-0 flex flex-col gap-1 border-t-2 border-line pt-3">
          <Row label="Subtotal" value={formatUsd(order.subtotalCents)} />
          <Row label="Shipping" value={formatUsd(order.shippingCents)} />
          <Row
            label="Total"
            value={`${formatUsd(order.subtotalCents + order.shippingCents)} ${order.currency}`}
          />
        </dl>
      </Section>

      <Section title="Payment requests">
        {order.paymentRequests.length === 0 ? (
          <p className="m-0 text-muted">None yet.</p>
        ) : (
          <ul className="m-0 flex list-none flex-col gap-2 p-0">
            {order.paymentRequests.map((p) => (
              <li key={p.id} className="rounded-[14px] border-2 border-border p-3">
                <div className="font-bold">
                  {p.type} - {formatUsd(p.amountCents)} - {p.status}
                </div>
                <div className="text-sm text-muted">
                  {p.channel}
                  {p.dueAt && ` - due ${formatDateTime(p.dueAt)}`}
                </div>
                {/* Plain text on purpose: the URL is admin-entered and must not become a clickable href. */}
                <div className="font-mono text-sm break-all">{p.paymentUrl}</div>
                {p.status === "PENDING" && (
                  <PaymentRequestActions requestId={p.id} onDone={done} onStale={stale} />
                )}
              </li>
            ))}
          </ul>
        )}
      </Section>

      <Section title="Shipping and status">
        <dl className="m-0 flex flex-col gap-1">
          <Row label="Carrier" value={order.carrier} />
          <Row label="Tracking" value={order.trackingNumber} />
          <Row label="Shipped" value={order.shippedAt && formatDateTime(order.shippedAt)} />
          <Row label="Completed" value={order.completedAt && formatDateTime(order.completedAt)} />
          <Row label="Cancel reason" value={order.cancelReason} />
          <Row
            label="Returned to stock"
            value={order.restockedAt && formatDateTime(order.restockedAt)}
          />
          <Row label="Created" value={formatDateTime(order.createdAt)} />
          <Row label="Updated" value={formatDateTime(order.updatedAt)} />
        </dl>
      </Section>

      <Section title="Note">
        <NoteEditor
          order={order}
          onSaved={(n) => {
            onNoteSaved(n);
            flash("Note saved.");
          }}
        />
      </Section>

      <Section title="History">
        <ol className="m-0 flex list-none flex-col gap-2 p-0">
          <li>
            <span className="text-sm text-muted">{formatDateTime(order.createdAt)}</span>
            <div className="font-bold">Order created</div>
          </li>
          {order.history.map((h, i) => (
            <li key={`${h.at}-${i}`}>
              <span className="text-sm text-muted">
                {formatDateTime(h.at)} - {h.actorType}
              </span>
              <div className="font-bold">
                {h.transition ?? h.action}
                {h.fromStatus && (
                  <span className="font-normal">
                    {" "}
                    ({h.fromStatus}
                    {h.toStatus ? ` -> ${h.toStatus}` : ""})
                  </span>
                )}
              </div>
            </li>
          ))}
        </ol>
      </Section>

      {order.progress.length > 0 && (
        <Section title="Production progress">
          <ul className="m-0 flex list-none flex-col gap-3 p-0">
            {order.progress.map((p) => (
              <li key={p.stage} className="rounded-[14px] border-2 border-border p-3">
                <div className="font-bold">
                  {p.stage} - {formatDateTime(p.approvedAt)}
                </div>
                {p.note && <p className="m-0 whitespace-pre-wrap">{p.note}</p>}
                {p.images.length > 0 && (
                  <div className="mt-2 flex flex-wrap gap-2">
                    {p.images.map((img, n) => (
                      // eslint-disable-next-line @next/next/no-img-element -- storage URLs are not configured for next/image
                      <img
                        key={img.url}
                        src={img.url}
                        alt={`${p.stage} progress photo ${n + 1}`}
                        width={96}
                        height={96}
                        className="size-24 rounded-[14px] border-2 border-border object-cover"
                      />
                    ))}
                  </div>
                )}
              </li>
            ))}
          </ul>
        </Section>
      )}
    </main>
  );
}

export default function AdminOrderPage({ params }: PageProps<"/admin/orders/[id]">) {
  const { id } = use(params);
  const { data, error, reload, update } = useApiGet<unknown>(
    `/api/admin/orders/${encodeURIComponent(id)}`,
  );
  const parsed = useMemo(
    () => (data === undefined ? null : adminOrderSchema.safeParse(data)),
    [data],
  );
  const [last, setLast] = useState<AdminOrder | null>(null);
  if (parsed?.success && last !== parsed.data) setLast(parsed.data);

  if (error) {
    const missing = error instanceof ApiError && error.status === 404;
    return (
      <ErrorView error={error} retry={reload} title={missing ? "Order not found" : undefined} />
    );
  }
  if (parsed && !parsed.success) {
    return <ErrorView error={new Error("Unexpected order response")} retry={reload} />;
  }
  // While reloading, keep showing the last order so OrderDetail (flash, stale error) stays mounted.
  const order = parsed?.success ? parsed.data : last?.id === Number(id) ? last : null;
  if (!order) {
    return (
      <p role="status" className="p-8 text-center font-medium">
        Loading order...
      </p>
    );
  }
  return (
    <OrderDetail
      order={order}
      reload={reload}
      onNoteSaved={(adminNote) =>
        update((d) => ({ ...(d as Record<string, unknown>), adminNote: adminNote || null }))
      }
    />
  );
}
