import { z } from "zod";

// Null fields are serialized as null (EMAIL_FAILED items carry no publicCode/dueAt).
export const dashboardSchema = z.object({
  generatedAt: z.string(),
  groups: z.array(
    z.object({
      key: z.string(),
      count: z.number(),
      items: z.array(
        z.object({
          id: z.number(),
          publicCode: z.string().nullable(),
          status: z.string(),
          fulfillmentType: z.string().nullable(),
          dueAt: z.string().nullable(),
          createdAt: z.string(),
        }),
      ),
    }),
  ),
});
export type Dashboard = z.infer<typeof dashboardSchema>;

export const GROUP_INFO: Record<string, { title: string; hint: string }> = {
  AWAITING_LINK: { title: "Send payment link", hint: "Orders waiting for a payment link." },
  PAYMENT_TO_CONFIRM: { title: "Confirm payment", hint: "A link was sent; confirm when paid." },
  PAYMENT_EXPIRING: { title: "Expiring within 24h", hint: "Payment due soon or overdue." },
  READY_TO_PRODUCE: { title: "Start production", hint: "Deposit paid, queued." },
  IN_PRODUCTION: { title: "In production", hint: "Being printed and painted." },
  AWAITING_BALANCE_LINK: { title: "Send balance link", hint: "Finished; balance link needed." },
  READY_TO_SHIP: { title: "Ready to ship", hint: "Paid in full; add tracking." },
  SHIPPED_TO_COMPLETE: { title: "Complete shipped orders", hint: "Mark delivered orders done." },
  FORFEITED_TO_RESTOCK: { title: "Return to stock", hint: "Forfeited orders not yet restocked." },
  EMAIL_FAILED: { title: "Failed emails", hint: "Outbox rows that gave up sending." },
};
