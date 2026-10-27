"use client";

import Link from "next/link";
import { linkButtonClass } from "../../../ui";
import { ProductForm } from "../ProductForm";

export default function NewProductPage() {
  return (
    <main className="mx-auto flex w-full max-w-3xl flex-col gap-4 px-4 py-8">
      <Link href="/admin/products" className={`${linkButtonClass} self-start py-2`}>
        Back to products
      </Link>
      <h1 className="text-2xl font-extrabold">New product</h1>
      <p>Create the product first; variants and images are added on the next screen.</p>
      <ProductForm />
    </main>
  );
}
