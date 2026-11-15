# Frontend image (T-042), Next.js standalone output. Build from frontend/:
#   docker build --build-arg NEXT_PUBLIC_SITE_URL=https://shop.example -t bkstudio-frontend .
# NEXT_PUBLIC_SITE_URL is inlined at build time, so the image is built per public origin.

# Node 22.23.5 LTS on Debian bookworm (glibc, same major as CI). Digest = multi-arch index, pinned 2026-11-15.
FROM node:22-bookworm-slim@sha256:e9f565d32859f0cbfb52125eafe8be39390f24dac302dc3cdf566e8ba3ab21ee AS base
ENV NEXT_TELEMETRY_DISABLED=1

FROM base AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY . .
# No /api rewrite in this image (Caddy routes /api); BACKEND_URL for src/lib/catalog.ts is a runtime env (compose).
ARG NEXT_PUBLIC_SITE_URL
# CI=1 turns on the guard in src/lib/site.ts: the build fails without NEXT_PUBLIC_SITE_URL.
ENV NEXT_PUBLIC_SITE_URL=$NEXT_PUBLIC_SITE_URL CI=1
RUN npm run build

FROM base
WORKDIR /app
ENV NODE_ENV=production PORT=3000 HOSTNAME=0.0.0.0
# Files stay root-owned (read-only for the app); only the ISR/data cache dir is writable (tmpfs in compose).
COPY --from=build /app/.next/standalone ./
COPY --from=build /app/.next/static ./.next/static
COPY --from=build /app/public ./public
RUN mkdir -p .next/cache && chown node:node .next/cache
USER node
EXPOSE 3000
# robots.txt does not call the backend, so a backend outage does not mark the frontend unhealthy.
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
    CMD ["node", "-e", "fetch('http://127.0.0.1:3000/robots.txt').then(r=>process.exit(r.ok?0:1),()=>process.exit(1))"]
CMD ["node", "server.js"]
