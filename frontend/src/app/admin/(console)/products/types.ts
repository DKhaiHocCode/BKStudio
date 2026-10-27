export const PRODUCT_STATUSES = ["DRAFT", "ACTIVE", "UNLISTED", "ARCHIVED"] as const;
export type ProductStatus = (typeof PRODUCT_STATUSES)[number];

export interface Variant {
  id: number;
  scale: string;
  priceCents: number;
  stockQty: number;
  madeToOrderEnabled: boolean;
  madeToOrderQuota: number | null;
  active: boolean;
  version: number;
}

export interface ProductImage {
  id: number;
  sortOrder: number;
  altText: string | null;
  urls: Record<string, string>;
  width: number;
  height: number;
}

export interface ProductDetail {
  id: number;
  slug: string;
  name: string;
  series: string | null;
  description: string | null;
  status: ProductStatus;
  version: number;
  createdAt: string;
  updatedAt: string;
  variants: Variant[];
  images: ProductImage[];
}

export interface ProductListItem {
  id: number;
  slug: string;
  name: string;
  series: string | null;
  status: ProductStatus;
  version: number;
  updatedAt: string;
  variantCount: number;
  imageCount: number;
  coverImage: { url: string; alt: string | null; width: number; height: number } | null;
}

export interface ProductPage {
  items: ProductListItem[];
  page: number;
  size: number;
  total: number;
}
