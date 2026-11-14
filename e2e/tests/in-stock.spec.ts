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

test("inStockPurchase_fullPayment_endsShippedWithTracking", async ({ page, admin }) => {
  const product = await createProduct(admin, "ACTIVE", {
    priceCents: 12_000,
    stockQty: 2,
    madeToOrderEnabled: false,
  });

  // Customer places the order.
  const order = await placeOrderViaUi(page, product, "stock");
  await expectCustomerStatus(page, order, "Awaiting payment");

  // Ordering reserved one unit (2 -> 1).
  const detail = await admin.get<{ variants: { stockQty: number }[] }>(
    `/api/admin/products/${product.productId}`,
  );
  expect(detail.variants[0].stockQty).toBe(1);

  // Admin issues the FULL payment link (due in 48 h); the customer sees it.
  const id = await findOrderId(admin, order.code);
  const before = await adminOrder(admin, id);
  expect(before.status).toBe("AWAITING_PAYMENT");
  const link = await issuePaymentLink(admin, id, "FULL", before.subtotalCents);
  expect(dueInAboutHours(link.dueAt, 48)).toBe(true);
  await page.reload();
  await expect(page.getByRole("link", { name: /Pay now/ })).toBeVisible();

  // Admin confirms payment, then ships.
  expect(await confirmPayment(admin, link.id)).toBe("PAID_IN_FULL");
  await expectCustomerStatus(page, order, "Paid in full");
  const tracking = `E2E${Date.now()}`;
  await shipOrder(admin, id, tracking);

  // Customer sees SHIPPED with the tracking number.
  await expectCustomerStatus(page, order, "Shipped");
  await expect(page.getByText(tracking)).toBeVisible();
});
