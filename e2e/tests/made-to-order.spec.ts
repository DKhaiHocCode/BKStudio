import type { AdminApi } from "../helpers/admin";
import { expect, test } from "../helpers/fixtures";
import { createProduct } from "../helpers/seed";
import {
  adminOrder,
  confirmPayment,
  dueInAboutHours,
  expectCustomerStatus,
  findOrderId,
  issuePaymentLink,
  placeOrderViaUi,
  shipOrder,
} from "../helpers/shop";

/** yyyy-MM, `offset` months from now (UTC); the queue window is the current month + 23. */
const month = (offset: number): string => {
  const d = new Date();
  return new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + offset, 1))
    .toISOString()
    .slice(0, 7);
};

async function allocatedSlots(api: AdminApi): Promise<number> {
  const months = await api.get<{ allocated: number }[]>(
    `/api/admin/production-months?from=${month(0)}&to=${month(23)}`,
  );
  return months.reduce((sum, m) => sum + m.allocated, 0);
}

const DAY_MS = 24 * 60 * 60 * 1000;

test("madeToOrder_depositThenBalance_endsShipped", async ({ page, admin }) => {
  const product = await createProduct(admin, "ACTIVE", {
    priceCents: 30_001, // odd on purpose: deposit rounds up, balance is the rest
    stockQty: 0,
    madeToOrderEnabled: true,
  });

  // Customer orders; status is AWAITING_DEPOSIT.
  const order = await placeOrderViaUi(page, product, "mto");
  await expectCustomerStatus(page, order, "Awaiting deposit");
  const id = await findOrderId(admin, order.code);
  const created = await adminOrder(admin, id);
  expect(created.status).toBe("AWAITING_DEPOSIT");

  // Deposit link (50%, rounded up) -> confirm -> queued with a slot.
  const deposit = Math.floor((created.subtotalCents + 1) / 2);
  const slotsBefore = await allocatedSlots(admin);
  const depositReq = await issuePaymentLink(admin, id, "DEPOSIT", deposit);
  expect(dueInAboutHours(depositReq.dueAt, 48)).toBe(true);
  expect(await confirmPayment(admin, depositReq.id)).toBe("QUEUED");
  await expectCustomerStatus(page, order, "In the queue");

  // The deposit assigned exactly one production slot.
  expect((await allocatedSlots(admin)) - slotsBefore).toBe(1);

  // Production: start, approve the three stages.
  let o = await adminOrder(admin, id);
  await admin.post(`/api/admin/orders/${id}/start-production`, { version: o.version });
  for (const stage of ["RAW_PRINT", "PREP", "PAINT"]) {
    await admin.send("POST", `/api/admin/orders/${id}/progress/${stage}`, {
      multipart: { note: `E2E ${stage}` },
    });
  }
  const token = order.orderPath.split("/").pop();
  const progress = await page.request.get(`/api/orders/${token}/progress`);
  expect(progress.status()).toBe(200);
  const stages = ((await progress.json()) as { stages: { stage: string; state: string }[] }).stages;
  for (const s of ["RAW_PRINT", "PREP", "PAINT"]) {
    expect(stages.find((x) => x.stage === s)?.state).toBe("DONE");
  }
  await expectCustomerStatus(page, order, "In production");
  await expect(page.getByRole("heading", { name: "Production progress" })).toBeVisible();

  // Finishing: AWAITING_BALANCE, then the balance link (30 days) via ARTISTREE.
  o = await adminOrder(admin, id);
  await admin.post(`/api/admin/orders/${id}/mark-finished`, { version: o.version });
  await expectCustomerStatus(page, order, "Awaiting final payment");

  o = await adminOrder(admin, id);
  const balanceReq = await issuePaymentLink(admin, id, "BALANCE", o.subtotalCents - deposit);
  const days = (Date.parse(balanceReq.dueAt) - Date.now()) / DAY_MS;
  expect(days).toBeGreaterThan(29);
  expect(days).toBeLessThanOrEqual(30);
  await page.reload();
  await expect(page.getByRole("link", { name: /Pay now/ })).toBeVisible();

  // Admin confirms the balance and ships.
  expect(await confirmPayment(admin, balanceReq.id)).toBe("PAID_IN_FULL");
  const tracking = `E2E${Date.now()}`;
  await shipOrder(admin, id, tracking);
  await expectCustomerStatus(page, order, "Shipped");
  await expect(page.getByText(tracking)).toBeVisible();
});
