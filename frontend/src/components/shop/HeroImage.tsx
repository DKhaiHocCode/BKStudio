import Image from "next/image";
import { fetchProducts } from "@/lib/catalog";
import { ProductImage } from "./ProductImage";

const panel =
  "aspect-[4/5] overflow-hidden rounded-panel border-[3px] border-border bg-capybara shadow-sticker-lg";

export function HeroImageFallback() {
  return (
    <div className={`${panel} flex items-center justify-center`}>
      <Image
        src="/logo.png"
        alt="BKStudio capybara logo"
        width={256}
        height={256}
        className="size-1/2"
      />
    </div>
  );
}

/** Newest product's cover (the API sorts newest first); falls back to the logo panel. */
export async function HeroImage() {
  const { items } = await fetchProducts({ size: 1 });
  const product = items[0];
  if (!product?.coverImage) return <HeroImageFallback />;
  const cover = product.coverImage;
  return (
    <>
      <div className={panel}>
        <ProductImage
          src={cover.url}
          alt={cover.alt || `Hand-painted ${product.name} figure`}
          width={cover.width}
          height={cover.height}
          sizes="(min-width: 1024px) 500px, 100vw"
          className="size-full object-cover"
        />
      </div>
      <span className="absolute -top-3 right-2 rotate-[8deg] rounded-full border-[3px] border-border bg-citrus px-3.5 py-2 text-sm font-extrabold text-ink shadow-sticker-sm sm:-top-[18px] sm:-right-2.5 sm:px-[18px] sm:py-3 sm:text-base">
        New: {product.name}
      </span>
    </>
  );
}
