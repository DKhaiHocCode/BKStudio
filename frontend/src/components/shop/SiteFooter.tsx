import Link from "next/link";
import { focusRing } from "@/components/ui";
import { Logo } from "./Logo";
import { SocialLinks } from "./SocialLinks";

const FOOTER_LINKS = [
  { href: "/orders/lookup", label: "Track my order" },
  { href: "/policy", label: "Terms & policy" },
  { href: "/faq", label: "FAQ" },
  { href: "/privacy", label: "Privacy" },
];

export function SiteFooter() {
  return (
    <footer className="mt-auto border-t-[3px] border-border bg-card">
      <div className="mx-auto flex max-w-[1200px] flex-wrap items-center justify-between gap-6 px-4 py-8 md:px-6">
        <div className="flex items-center gap-3">
          <Logo size={44} />
          <span className="font-bold">BKStudio · hand-painted in Vietnam</span>
        </div>
        <nav aria-label="Footer" className="flex flex-wrap gap-x-5 font-bold">
          {FOOTER_LINKS.map((link) => (
            <Link
              key={link.label}
              href={link.href}
              className={`inline-flex min-h-11 items-center text-fg hover:text-danger ${focusRing}`}
            >
              {link.label}
            </Link>
          ))}
        </nav>
        <nav aria-label="Social" className="basis-full">
          <SocialLinks />
        </nav>
      </div>
    </footer>
  );
}
