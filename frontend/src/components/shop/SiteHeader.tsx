import Link from "next/link";
import { focusRing } from "@/components/ui";
import { CartLink } from "./CartLink";
import { Logo } from "./Logo";
import { MobileMenu } from "./MobileMenu";
import { ThemeToggle } from "@/components/ThemeToggle";
import { NAV_LINKS } from "./nav";

export function SiteHeader() {
  return (
    <header className="border-b-[3px] border-border bg-card">
      <div className="mx-auto flex max-w-[1200px] items-center justify-between gap-4 px-4 py-3 md:px-6">
        <Link href="/" className={`flex items-center gap-3 no-underline ${focusRing}`}>
          <Logo size={48} alt="BKStudio home" />
          <span className="text-xl font-extrabold text-fg md:text-[26px]">BKStudio</span>
        </Link>
        <nav aria-label="Main" className="hidden items-center gap-1.5 md:flex">
          {NAV_LINKS.map((link) => (
            <Link
              key={link.href}
              href={link.href}
              className={`inline-flex min-h-11 items-center rounded-full px-3.5 font-bold text-fg no-underline hover:bg-citrus hover:text-ink ${focusRing}`}
            >
              {link.label}
            </Link>
          ))}
          <CartLink variant="pill" />
          <ThemeToggle />
        </nav>
        <div className="flex gap-2 md:hidden">
          <CartLink variant="icon" />
          <ThemeToggle />
          <MobileMenu />
        </div>
      </div>
    </header>
  );
}
