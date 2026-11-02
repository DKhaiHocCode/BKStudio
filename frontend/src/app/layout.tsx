import type { Metadata } from "next";
import { Rubik } from "next/font/google";
import { SITE_URL } from "@/lib/site";
import { THEME_INIT } from "@/lib/theme";
import "./globals.css";

const rubik = Rubik({
  subsets: ["latin"],
  weight: ["400", "500", "700", "800"],
  variable: "--font-rubik",
});

export const metadata: Metadata = {
  metadataBase: new URL(SITE_URL),
  title: { default: "BKStudio", template: "%s | BKStudio" },
  description: "Hand-painted 3D printed anime figures, shipped worldwide.",
};

// suppressHydrationWarning on <html>: the script above adds data-theme before React hydrates.
export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="en" className={`${rubik.variable} h-full antialiased`} suppressHydrationWarning>
      <head>
        <script>{THEME_INIT}</script>
      </head>
      <body className="flex min-h-full flex-col">
        <a
          href="#main"
          className="sr-only rounded-button border-[3px] border-border bg-card font-bold text-fg focus:not-sr-only focus:fixed focus:px-4 focus:py-3 focus:top-2 focus:left-2 focus:z-50 focus-visible:outline-[3px] focus-visible:outline-offset-2 focus-visible:outline-focus"
        >
          Skip to content
        </a>
        {children}
      </body>
    </html>
  );
}
