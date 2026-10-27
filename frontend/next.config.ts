import type { NextConfig } from "next";

// Where the Spring Boot API listens. Only used by the dev/server-side proxy below.
const backendUrl = process.env.BACKEND_URL ?? "http://localhost:8080";

const nextConfig: NextConfig = {
  poweredByHeader: false,
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
    return [{ source: "/api/:path*", destination: `${backendUrl}/api/:path*` }];
  },
};

export default nextConfig;
