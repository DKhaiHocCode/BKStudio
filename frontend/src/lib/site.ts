const configured = process.env.NEXT_PUBLIC_SITE_URL;

// Production must know its public origin (canonical URLs, OG tags, sitemap). A plain local
// `next build` is exempt so it keeps working; CI and `next start` are not.
const localBuild = process.env.NEXT_PHASE === "phase-production-build" && !process.env.CI;
if (!configured && process.env.NODE_ENV === "production" && !localBuild) {
  throw new Error(
    "NEXT_PUBLIC_SITE_URL must be set in production (e.g. https://bkstudio.example).",
  );
}

/** Public origin of the site, used for metadata, sitemap and robots. */
export const SITE_URL = configured ?? "http://localhost:3000";
