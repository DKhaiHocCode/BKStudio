import Link from "next/link";
import { secondaryButton } from "@/components/ui";

export default function NotFound() {
  return (
    <main className="mx-auto flex max-w-xl flex-1 flex-col items-center justify-center gap-4 px-4 py-16 text-center">
      <h1 className="text-3xl font-extrabold">Page not found</h1>
      <p className="text-muted">The page you are looking for does not exist or has moved.</p>
      <Link href="/" className={secondaryButton}>
        Back to home
      </Link>
    </main>
  );
}
