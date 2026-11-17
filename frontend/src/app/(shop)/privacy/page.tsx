import type { Metadata } from "next";
import { SocialLinks } from "@/components/shop/SocialLinks";
import { focusRing } from "@/components/ui";
import { CONTACT_EMAIL } from "@/lib/contact";

export const metadata: Metadata = {
  title: "Privacy",
  description: "What personal data BKStudio collects, why, and how long we keep it.",
};

// TODO(owner): have this text reviewed for SG/AU customers before launch.
// Server logs ("What we collect") match infra/Caddyfile (redacted_log) and the json-file rotation in
// infra/docker-compose.yml (max-size 10m, max-file 5): no fixed number of days.
// Owner decision still open (the public text below stays neutral until it is made):
// TODO(owner): "Bot protection" wording: how long Cloudflare keeps Turnstile data, on what legal
//   basis, and whether its cookies/storage need consent for SG/AU visitors. Read 2026-11-17: the
//   Turnstile Privacy Addendum (cloudflare.com/turnstile-privacy-policy, updated 2025-06-18) lists
//   client IP, TLS fingerprint, User-Agent, sitekey and origin, gives no retention period and does
//   not say which cookies or storage the widget uses; so the text says "may use", not "sets".
const SECTIONS: { title: string; body: string }[] = [
  {
    title: "Who we are",
    body: "BKStudio is a small studio based in Hanoi, Vietnam. We are responsible for the personal data you give us on this website. You can reach us using the contact details at the bottom of this page.",
  },
  {
    title: "What we collect",
    body: "When you place an order we collect your name, email address, shipping address, optional phone number and the details of your order. At checkout we also record your IP address, the time and the version of the terms you agreed to, so we can show what was agreed if there is ever a dispute. Our web server and our app also keep technical logs of requests to the site (for example the time, your IP address and the page asked for) for security and troubleshooting. Private order links and private product links are removed from these logs. The logs are kept for a short time and then overwritten automatically.",
  },
  {
    title: "Bot protection",
    body: "On the checkout and order lookup pages we use Cloudflare Turnstile to block automated abuse. To tell people from bots, it processes your IP address and technical signals from your browser and connection. Cloudflare may use cookies or similar technologies for this check; its Turnstile Privacy Addendum and Cookie Policy describe what it does.",
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
    body: "There are no customer accounts on this website. We never see or store your card details: payments happen on the payment platform in the links we email you. The deposit on a made-to-order figure is paid on Artistree. In-stock figures and the balance on a made-to-order figure are paid on Shopee through a proxy buyer service. Those platforms and services have their own privacy policies.",
  },
  {
    title: "Who handles your data",
    body: "We share data only with the services that help us run the shop: our hosting provider, our email delivery service, the payment platform in your payment link (Artistree for a made-to-order deposit; Shopee for an in-stock figure or a balance), the proxy buyer service that helps you pay on Shopee, the shipping carrier (who needs your name and address to deliver), Cloudflare (bot protection on the checkout and order lookup pages), and a cloud storage provider (stores encrypted backups for a limited time). Some of these providers may store data outside your country.",
  },
  {
    title: "How long we keep it",
    body: "Two years after your order ends, we remove your name, email address, phone number and shipping address from the order. We keep the order amount and the product for our own records, with no personal details. Encrypted backups can keep removed data for up to about 60 days longer.",
  },
  {
    title: "Your choices",
    body: "You can ask us to show you the personal data we hold about you, to correct it, or to delete it earlier: email us and we will do it by hand within 30 days. We may need to keep what the law requires or what is needed for an open dispute. This applies wherever you live.",
  },
  {
    title: "Keeping it safe",
    body: "Only the shop owner can sign in to the admin area, with a password and a one-time code. The website uses encrypted connections (HTTPS). Our database backups are encrypted.",
  },
  {
    title: "Changes",
    body: "We may update this page from time to time. Last updated: 17 November 2026.",
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
            href={`mailto:${CONTACT_EMAIL}`}
            className={`font-bold underline hover:text-danger ${focusRing}`}
          >
            {CONTACT_EMAIL}
          </a>
          . You can also reach us on any of these channels:
        </p>
        <SocialLinks />
      </section>
    </main>
  );
}
