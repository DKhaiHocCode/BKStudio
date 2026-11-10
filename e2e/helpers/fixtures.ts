import { test as base } from "@playwright/test";
import { AdminApi, newAdminApi } from "./admin";

export { expect } from "@playwright/test";

/** Worker-scoped admin API client that reuses the session saved by global-setup.ts. */
export const test = base.extend<object, { admin: AdminApi }>({
  admin: [
    // eslint-disable-next-line no-empty-pattern
    async ({}, use) => {
      const api = await newAdminApi();
      await use(api);
      await api.ctx.dispose();
    },
    { scope: "worker" },
  ],
});
