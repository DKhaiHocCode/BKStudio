import type { ImgHTMLAttributes } from "react";

/**
 * Plain <img>: the backend already serves resized WebP variants (400/1000/2000) from R2, so the
 * next/image optimiser would only re-encode them and need remotePatterns config.
 */
export function ProductImage({
  alt,
  ...props
}: ImgHTMLAttributes<HTMLImageElement> & { alt: string }) {
  // eslint-disable-next-line @next/next/no-img-element
  return <img alt={alt} {...props} />;
}

export function srcSetOf(srcset: Record<string, string>): string {
  return Object.entries(srcset)
    .map(([width, url]) => `${url} ${width}w`)
    .join(", ");
}
