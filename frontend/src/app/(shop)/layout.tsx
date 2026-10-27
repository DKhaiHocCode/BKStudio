import { SiteFooter } from "@/components/shop/SiteFooter";
import { SiteHeader } from "@/components/shop/SiteHeader";

// The header is `relative` so the mobile menu panel can hang under it.
export default function ShopLayout({ children }: LayoutProps<"/">) {
  return (
    <>
      <div className="relative">
        <SiteHeader />
      </div>
      {children}
      <SiteFooter />
    </>
  );
}
