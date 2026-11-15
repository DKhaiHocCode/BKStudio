import type { NextConfig } from "next";

// Where the Spring Boot API listens. Only used by the /api proxy below.
const backendUrl = process.env.BACKEND_URL ?? "http://localhost:8080";
// The /api proxy exists for `next dev` and for the CI E2E job (API_PROXY=1 at build, no Caddy there). The Docker
// image has no rewrite: Caddy sends /api to the backend. The destination is baked in at build time.
const proxyApi = process.env.NODE_ENV === "development" || process.env.API_PROXY === "1";

const nextConfig: NextConfig = {
  poweredByHeader: false,
  // Docker image and CI E2E run .next/standalone/server.js (T-042); `next start` warns but still serves.
  output: "standalone",
  // Dev only: lets the dev server serve /_next/* to a Cloudflare quick tunnel (otherwise the page never hydrates).
  allowedDevOrigins: ["*.trycloudflare.com"],
  cacheComponents: true,
  partialPrefetching: true,
  turbopack: {
    rules: {
      "*.css": {
        loaders: ["@tailwindcss/turbopack"],
        as: "*.css",
      },
    },
  },
  // Same-origin API (ADR-0001): the browser calls /api/..., Next forwards it to the backend.
  // In production Caddy does this routing instead, so the code never needs to know the backend URL.
  async rewrites() {
    return proxyApi ? [{ source: "/api/:path*", destination: `${backendUrl}/api/:path*` }] : [];
  },
};

export default nextConfig;
