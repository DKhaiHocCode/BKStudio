import type { Metadata } from "next";
import { SocialLinks } from "@/components/shop/SocialLinks";

export const metadata: Metadata = {
  title: "Privacy",
  description: "What personal data BKStudio collects, why, and how long we keep it.",
};

// TODO(owner): have this text reviewed for SG/AU customers before launch.
const SECTIONS: { title: string; body: string }[] = [
  {
    title: "What we collect",
    body: "When you place an order we collect your name, email address, optional phone number, shipping address and the details of your order. At checkout we also record your IP address, the time and the version of the terms you agreed to, as proof of your agreement.",
  },
  {
    title: "Why we collect it",
    body: "We use this data only to process your order: to email you payment links and updates, to make and ship your figure, and to answer questions about it.",
  },
  {
    title: "No accounts, no card data",
    body: "There are no customer accounts on this website. We never see or store your card details: payments happen on Artistree or Shopee through the links we email you.",
  },
  {
    title: "Who handles your data",
    body: "Your data is handled by the services we use to run the shop: our hosting provider (a VPS), Resend for email, the payment platforms (Artistree or Shopee), the shipping carrier, and a CDN/proxy provider such as Cloudflare.",
  },
  {
    title: "How long we keep it",
    body: "Two years after your order ends, we remove your name, email address, phone number and shipping address from the order.",
  },
];

export default function PrivacyPage() {
  return (
    <main className="mx-auto flex w-full max-w-2xl flex-1 flex-col gap-6 px-4 pt-8 pb-16 md:px-6 md:pt-10">
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
        <p className="m-0">Contact us on any of these channels:</p>
        <SocialLinks />
      </section>
    </main>
  );
}
