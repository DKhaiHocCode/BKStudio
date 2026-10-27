import type { Metadata } from "next";
import { Rubik } from "next/font/google";
import { SITE_URL } from "@/lib/site";
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

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="en" className={`${rubik.variable} h-full antialiased`}>
      <body className="flex min-h-full flex-col">{children}</body>
    </html>
  );
}
