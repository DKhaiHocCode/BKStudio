import { expect, type Page } from "@playwright/test";
import type { AdminApi } from "./admin";
import { PAY_DOMAINS, uid, type SeededProduct } from "./seed";

export type Mode = "stock" | "mto";

export interface PlacedOrder {
  /** Secret path /orders/{token}: never log it. */
  orderPath: string;
  code: string;
}

/** Product page -> cart -> checkout form -> success page. Everything the customer does in the browser. */
export async function placeOrderViaUi(
  page: Page,
  product: SeededProduct,
  mode: Mode,
): Promise<PlacedOrder> {
  await page.goto(`/products/${product.slug}`);
  await page
    .getByRole("radio", { name: mode === "stock" ? /Ready to ship/ : /Made for you/ })
    .check();
  await page.getByRole("button", { name: "Add to cart" }).click();
  await page.getByRole("link", { name: "View cart" }).click();
  await page.getByRole("link", { name: "Checkout" }).click();

  const placeOrder = page.getByRole("button", { name: "Place order" });
  // Enabled only after the terms loaded client-side, so the page is hydrated by then.
  await expect(placeOrder).toBeEnabled();

  await page.getByLabel("Full name").fill("E2E Customer");
  await page.getByLabel("Email", { exact: true }).fill(`e2e-${uid()}@example.test`);
  await page.getByLabel("Country").selectOption("SG");
  await page.getByLabel("Address line 1").fill("1 Test Road");
  await page.getByLabel("City").fill("Singapore");
  await page.getByLabel("Postcode").fill("238823");
  await page.getByRole("checkbox", { name: /I have read and agree/ }).check();
  await placeOrder.click();

  await expect(
    page.getByRole("heading", { name: "Thank you, your order is placed" }),
  ).toBeVisible();
  const url = await page.locator("#order-url").inputValue();
  const code = (await page.locator("dt:text-is('Order code') + dd").textContent())?.trim();
  if (!code) throw new Error("Order code not shown on the success page");
  return { orderPath: new URL(url).pathname, code };
}

/** Reloads the secret order page and waits until the status badge shows the label. */
export async function expectCustomerStatus(page: Page, order: PlacedOrder, label: string) {
  await page.goto(order.orderPath);
  // The badge is the direct <span> child of the header row that holds the <h1> (not the History list).
  await expect(page.locator("div:has(> div > h1) > span")).toHaveText(label);
}

interface AdminOrder {
  id: number;
  status: string;
  version: number;
  subtotalCents: number;
  paymentRequests: { id: number; type: string; status: string; dueAt: string }[];
}

export const adminOrder = async (api: AdminApi, id: number) =>
  api.get<AdminOrder>(`/api/admin/orders/${id}`);

/** Admin order id from the public code (the admin list matches publicCode exactly). */
export async function findOrderId(api: AdminApi, code: string): Promise<number> {
  const list = await api.get<{ items: { id: number }[] }>(
    `/api/admin/orders?q=${encodeURIComponent(code)}`,
  );
  if (list.items.length !== 1)
    throw new Error(`Expected 1 order for code, got ${list.items.length}`);
  return list.items[0].id;
}

export interface CreatedRequest {
  id: number;
  dueAt: string;
}

/** Issues an ARTISTREE payment link; amountCents must equal the server-computed amount. */
export async function issuePaymentLink(
  api: AdminApi,
  orderId: number,
  type: "FULL" | "DEPOSIT" | "BALANCE",
  amountCents: number,
): Promise<CreatedRequest> {
  return api.post<CreatedRequest>(`/api/admin/orders/${orderId}/payment-requests`, {
    type,
    amountCents,
    channel: "ARTISTREE",
    paymentUrl: `https://${PAY_DOMAINS[0]}/pay/${uid()}`,
  });
}

export async function confirmPayment(api: AdminApi, requestId: number): Promise<string> {
  const res = await api.post<{ orderStatus: string }>(
    `/api/admin/payment-requests/${requestId}/confirm`,
    { externalRef: `E2E-${uid()}` },
  );
  return res.orderStatus;
}

export async function shipOrder(api: AdminApi, orderId: number, trackingNumber: string) {
  const o = await adminOrder(api, orderId);
  await api.post(`/api/admin/orders/${orderId}/ship`, {
    version: o.version,
    carrier: "E2E Courier",
    trackingNumber,
  });
}

/** True when dueAt lies about `hours` ahead of now (a minute of slack for test duration and clock skew). */
export function dueInAboutHours(dueAt: string, hours: number): boolean {
  const diffH = (Date.parse(dueAt) - Date.now()) / 3_600_000;
  return diffH > hours - 0.1 && diffH <= hours + 0.01;
}
