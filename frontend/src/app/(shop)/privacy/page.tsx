import type { Metadata } from "next";
import { SocialLinks } from "@/components/shop/SocialLinks";
import { focusRing } from "@/components/ui";

export const metadata: Metadata = {
  title: "Privacy",
  description: "What personal data BKStudio collects, why, and how long we keep it.",
};

// TODO(owner): have this text reviewed for SG/AU customers before launch.
// Owner decisions still open (the public text below stays neutral until they are made):
// [CẦN DUYỆT] Trading name and city to publish in a "Who we are" section.
// [CẦN DUYỆT] What the servers log and for how long ("What we collect").
// [CẦN DUYỆT] Whether to name specific vendors (Cloudflare, R2, Resend) in "Who handles your data",
//   and whether to list a network/security (CDN/proxy) provider there at all.
// [CẦN DUYỆT] If the Shopee channel is chosen: add the proxy-buyer service to "Who handles your data".
// [CẦN DUYỆT] Whether tax or accounting rules need records for longer than 2 years.
// [CẦN DUYỆT] Reply time for data requests, breach notification and regulator contacts (SG/AU).
const SECTIONS: { title: string; body: string }[] = [
  {
    title: "What we collect",
    body: "When you place an order we collect your name, email address, shipping address, optional phone number and the details of your order. At checkout we also record your IP address, the time and the version of the terms you agreed to, so we can show what was agreed if there is ever a dispute. Our servers also keep standard technical logs for security and troubleshooting.",
  },
  {
    title: "Why we use it",
    body: "To email you payment links and updates, to make and ship your figure, to handle problems, and to keep a record of your agreement. We do not use your details for marketing and we do not sell them.",
  },
  {
    title: "Your agreement",
    body: "By placing an order you agree to us using your details for these purposes. If you want us to stop using them, tell us. We may then be unable to complete your order.",
  },
  {
    title: "No accounts, no card data",
    body: "There are no customer accounts on this website. We never see or store your card details: payments happen on Artistree or Shopee through the links we email you. Those platforms have their own privacy policies.",
  },
  {
    title: "Who handles your data",
    body: "We share data only with the services that help us run the shop: our hosting provider, our email delivery service, the payment platform (Artistree or Shopee), the shipping carrier (who needs your name and address to deliver), and a cloud storage provider (stores encrypted backups for a limited time). Some of these providers may store data outside your country.",
  },
  {
    title: "How long we keep it",
    body: "Two years after your order ends, we remove your name, email address, phone number and shipping address from the order. We keep the order amount and the product for our own records, with no personal details. Encrypted backups can keep removed data for up to about 60 days longer.",
  },
  {
    title: "Your choices",
    body: "You can ask us to show you the personal data we hold about you, to correct it, or to delete it earlier: email us and we will do it by hand. We may need to keep what the law requires or what is needed for an open dispute. This applies wherever you live.",
  },
  {
    title: "Keeping it safe",
    body: "Only the shop owner can sign in to the admin area, with a password and a one-time code. The website uses encrypted connections (HTTPS). Our database backups are encrypted.",
  },
  {
    title: "Changes",
    body: "We may update this page from time to time. Last updated: 16 November 2026.",
  },
];

export default function PrivacyPage() {
  return (
    <main
      id="main"
      className="mx-auto flex w-full max-w-2xl flex-1 flex-col gap-6 px-4 pt-8 pb-16 md:px-6 md:pt-10"
    >
      <h1 className="m-0 text-3xl font-extrabold md:text-[40px]">Privacy</h1>
      {SECTIONS.map(({ title, body }) => (
        <section key={title} className="flex flex-col gap-1">
          <h2 className="m-0 text-xl font-extrabold">{title}</h2>
          <p className="m-0">{body}</p>
        </section>
      ))}
      <section aria-labelledby="contact" className="flex flex-col gap-1">
        <h2 id="contact" className="m-0 text-xl font-extrabold">
          Questions or requests
        </h2>
        <p className="m-0">
          For privacy questions, or to see, correct or delete your data, email{" "}
          <a
            href="mailto:bk3dprinls@gmail.com"
            className={`font-bold underline hover:text-danger ${focusRing}`}
          >
            bk3dprinls@gmail.com
          </a>
          . You can also reach us on any of these channels:
        </p>
        <SocialLinks />
      </section>
    </main>
  );
}
