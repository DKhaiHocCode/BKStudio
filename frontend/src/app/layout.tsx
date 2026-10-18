import type { Metadata } from "next";
import Link from "next/link";
import { Geist, Geist_Mono } from "next/font/google";
import "./globals.css";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: { default: "BKStudio", template: "%s | BKStudio" },
  description: "Hand-painted 3D printed anime figures, shipped worldwide.",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="en" className={`${geistSans.variable} ${geistMono.variable} h-full antialiased`}>
      <body className="flex min-h-full flex-col">
        <header className="border-b border-neutral-200 dark:border-neutral-800">
          <div className="mx-auto flex max-w-5xl items-center px-4 py-3">
            <Link href="/" className="text-lg font-semibold">
              BKStudio
            </Link>
          </div>
        </header>
        {children}
        <footer className="border-t border-neutral-200 py-4 text-center text-sm text-neutral-500 dark:border-neutral-800">
          © BKStudio
        </footer>
      </body>
    </html>
  );
}
