"use client";

import { useState } from "react";
import { focusRing } from "@/components/ui";
import type { ProductImage as Img } from "@/lib/catalog";
import { ProductImage, srcSetOf } from "./ProductImage";

export function Gallery({ images, name }: { images: Img[]; name: string }) {
  const [index, setIndex] = useState(0);
  const main = images[index];

  return (
    <div className="flex min-w-0 flex-col gap-4">
      <div className="aspect-[4/5] overflow-hidden rounded-panel border-[3px] border-border bg-capybara shadow-sticker-lg">
        {main ? (
          <ProductImage
            src={main.url}
            srcSet={srcSetOf(main.srcset)}
            sizes="(min-width: 1024px) 560px, 100vw"
            alt={main.alt || name}
            width={main.width}
            height={main.height}
            className="size-full object-cover"
          />
        ) : (
          <div className="flex size-full items-center justify-center font-bold text-bark">
            Photo coming soon
          </div>
        )}
      </div>
      {images.length > 1 && (
        <ul className="m-0 grid list-none grid-cols-4 gap-3 p-0">
          {images.map((img, i) => (
            <li key={img.url}>
              <button
                type="button"
                aria-label={`Show photo ${i + 1}${img.alt ? `: ${img.alt}` : ""}`}
                aria-pressed={i === index}
                onClick={() => setIndex(i)}
                className={`aspect-square w-full overflow-hidden rounded-2xl border-[3px] border-border bg-capybara p-0 ${focusRing} ${
                  i === index ? "shadow-sticker-sm" : "opacity-80 hover:opacity-100"
                }`}
              >
                <ProductImage
                  src={img.srcset["400"] ?? img.url}
                  alt=""
                  width={400}
                  height={Math.round((400 * img.height) / img.width)}
                  sizes="120px"
                  loading="lazy"
                  className="size-full object-cover"
                />
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
