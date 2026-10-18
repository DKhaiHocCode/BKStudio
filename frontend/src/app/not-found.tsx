import Link from "next/link";

export default function NotFound() {
  return (
    <main className="mx-auto flex max-w-xl flex-1 flex-col items-center justify-center gap-4 px-4 py-16 text-center">
      <h1 className="text-2xl font-semibold">Page not found</h1>
      <p className="text-neutral-600 dark:text-neutral-400">
        The page you are looking for does not exist or has moved.
      </p>
      <Link href="/" className="rounded border border-current px-4 py-2">
        Back to home
      </Link>
    </main>
  );
}
