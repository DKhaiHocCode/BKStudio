import Image from "next/image";

export function Logo({ size, alt = "" }: { size: number; alt?: string }) {
  return (
    <Image
      src="/logo.png"
      alt={alt}
      width={size}
      height={size}
      className="rounded-full border-[3px] border-border bg-surface object-cover"
      style={{ width: size, height: size }}
    />
  );
}
