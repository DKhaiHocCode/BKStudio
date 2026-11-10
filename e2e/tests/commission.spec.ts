import { randomUUID } from "node:crypto";
import { expect, test } from "../helpers/fixtures";
import { createProduct } from "../helpers/seed";
import { placeOrderViaUi } from "../helpers/shop";

const slugify = (name: string): string =>
  name
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-|-$/g, "");

test("commissionQuotaOne_secondOrderIsRejected", async ({ page, request, admin }) => {
  const product = await createProduct(admin, "UNLISTED", {
    priceCents: 50_000,
    stockQty: 0,
    madeToOrderEnabled: true,
    madeToOrderQuota: 1,
  });
  // UNLISTED slugs always get a random suffix, and the product never appears in the public list.
  expect(product.slug).toMatch(/-[a-z0-9]{4,}$/);
  expect(product.slug).not.toBe(slugify(product.name));
  const list = await request.get("/api/products?size=50");
  expect(JSON.stringify(await list.json())).not.toContain(product.slug);

  // First customer takes the only slot.
  await placeOrderViaUi(page, product, "mto");

  // Public catalog now shows no made-to-order availability.
  const detail = await request.get(`/api/products/${product.slug}`);
  const body = (await detail.json()) as {
    unlisted: boolean;
    variants: { madeToOrder: { available: boolean } }[];
  };
  expect(body.unlisted).toBe(true);
  expect(body.variants[0].madeToOrder.available).toBe(false);

  // A second order is refused by the API (409 OUT_OF_STOCK), not only hidden in the UI.
  const terms = (await (await request.get("/api/terms/current")).json()) as { version: string };
  const xsrf = (await request.storageState()).cookies.find((c) => c.name === "XSRF-TOKEN")?.value;
  const second = await request.post("/api/orders", {
    headers: { "Idempotency-Key": randomUUID(), "X-XSRF-TOKEN": decodeURIComponent(xsrf ?? "") },
    data: {
      fulfillmentType: "MADE_TO_ORDER",
      items: [{ variantId: product.variantId, quantity: 1 }],
      customer: {
        name: "E2E Second",
        email: `e2e-second-${randomUUID()}@example.test`,
        phone: null,
      },
      shippingAddress: {
        line1: "2 Test Road",
        line2: null,
        city: "Singapore",
        region: null,
        postalCode: "238823",
        country: "SG",
      },
      acceptedTermsVersion: terms.version,
    },
  });
  expect(second.status()).toBe(409);
  expect(((await second.json()) as { code: string }).code).toBe("OUT_OF_STOCK");

  // The page is noindex (UNLISTED).
  await page.goto(`/products/${product.slug}`);
  await expect(page.locator(`meta[name="robots"]`)).toHaveAttribute("content", /noindex/);

  // The product page offers no way to add it again.
  await expect(async () => {
    await page.goto(`/products/${product.slug}`);
    await expect(page.getByRole("button", { name: "Unavailable" })).toBeDisabled({
      timeout: 2_000,
    });
  }).toPass();
});
