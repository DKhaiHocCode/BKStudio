// EXPERIMENT ONLY (frontend-test drafts). Turns figureImage/<Name>/*.jpg into optimised WebP
// copies and a typed product list. Run from frontend/: `npm run demo:photos`.
//
//   figureImage/<Name>/            one folder = one product; photos sorted by file name
//   figureImage/<Name>/info.json   optional overrides, every field optional:
//     { "name": "Hiyuki", "status": "IN_STOCK" | "MADE_TO_ORDER" | "COMMISSION", "priceUsd": 169,
//       "descriptor": "30 cm, flowing red hakama", "heightCm": 30, "isNew": true,
//       "description": "Longer text for the detail page.",
//       "order": 1,                                      shelf position (default: after, by name)
//       "cover": 3, "hover": 2,                          photo numbers (1 = first by file name)
//       "objectPosition": { "1": "50% 30%" },            crop anchor per photo number
//       "alt": { "1": "Front view" } }                   alt text per photo number
//
// Originals are only read. Output: public/frontend-test/real/<slug>/NN.webp + NN-700.webp
// (gitignored) and src/app/(shop)/frontend-test/_components/products.generated.ts (committed).
import assert from "node:assert/strict";
import { existsSync } from "node:fs";
import { mkdir, readdir, readFile, rm, stat, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const frontend = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const SOURCE = path.resolve(frontend, "..", "figureImage");
const OUT = path.join(frontend, "public", "frontend-test", "real");
const GENERATED = path.join(
  frontend,
  "src/app/(shop)/frontend-test/_components/products.generated.ts",
);
// No .json extension: `prettier --check .` would otherwise flag this gitignored file.
const MANIFEST = ".sync-manifest";
const STATUSES = ["IN_STOCK", "MADE_TO_ORDER", "COMMISSION"];
const PHOTO = /\.(jpe?g|png|webp|avif|heic)$/i;

let sharp;
try {
  sharp = (await import("sharp")).default;
} catch {
  console.error("sharp is not installed. It ships with Next.js: run `npm install` in frontend/.");
  process.exit(1);
}

const slugify = (name) =>
  name
    .normalize("NFKD")
    .replace(/[^\w\s-]/g, "")
    .trim()
    .toLowerCase()
    .replace(/[\s_-]+/g, "-");
assert.equal(slugify("  ZZ Test_2 "), "zz-test-2");

const fail = (folder, message) => {
  throw new Error(`figureImage/${folder}/info.json: ${message}`);
};

async function readInfo(folder) {
  const file = path.join(SOURCE, folder, "info.json");
  if (!existsSync(file)) return {};
  let info;
  try {
    info = JSON.parse(await readFile(file, "utf8"));
  } catch (e) {
    fail(folder, `not valid JSON (${e.message})`);
  }
  if (info.status !== undefined && !STATUSES.includes(info.status))
    fail(folder, `status must be one of ${STATUSES.join(", ")}`);
  if (info.priceUsd !== undefined && !(Number.isFinite(info.priceUsd) && info.priceUsd > 0))
    fail(folder, "priceUsd must be a positive number");
  if (info.heightCm !== undefined && !(Number.isFinite(info.heightCm) && info.heightCm > 0))
    fail(folder, "heightCm must be a positive number");
  return info;
}

async function readManifest(dir) {
  try {
    return JSON.parse(await readFile(path.join(dir, MANIFEST), "utf8"));
  } catch {
    return {};
  }
}

const folders = (await readdir(SOURCE, { withFileTypes: true }))
  .filter((d) => d.isDirectory())
  .map((d) => d.name)
  .sort((a, b) => a.localeCompare(b));

const products = [];
let written = 0;
let skipped = 0;

for (const [index, folder] of folders.entries()) {
  const files = (await readdir(path.join(SOURCE, folder))).filter((f) => PHOTO.test(f)).sort();
  if (files.length === 0) {
    console.warn(`skip ${folder}: no photos`);
    continue;
  }
  const info = await readInfo(folder);
  const slug = slugify(folder);
  if (!slug) throw new Error(`figureImage/${folder}: folder name gives an empty slug`);
  if (products.some((p) => p.slug === slug)) throw new Error(`duplicate slug "${slug}"`);
  const name = info.name ?? folder;
  const dir = path.join(OUT, slug);
  await mkdir(dir, { recursive: true });
  const old = await readManifest(dir);
  const manifest = {};
  const photos = [];

  for (const [i, file] of files.entries()) {
    const nn = String(i + 1).padStart(2, "0");
    const source = path.join(SOURCE, folder, file);
    const { size, mtimeMs } = await stat(source);
    const big = path.join(dir, `${nn}.webp`);
    const small = path.join(dir, `${nn}-700.webp`);
    const prev = old[nn];
    let entry;
    if (
      prev?.file === file &&
      prev.size === size &&
      prev.mtimeMs === mtimeMs &&
      existsSync(big) &&
      existsSync(small)
    ) {
      entry = prev;
      skipped++;
    } else {
      // rotate() applies the EXIF orientation; sharp drops all metadata unless asked to keep it.
      const out = await sharp(source)
        .rotate()
        .resize(1400, 1400, { fit: "inside", withoutEnlargement: true })
        .webp({ quality: 80 })
        .toFile(big);
      const thumb = await sharp(source)
        .rotate()
        .resize({ width: 700, withoutEnlargement: true })
        .webp({ quality: 78 })
        .toFile(small);
      entry = { file, size, mtimeMs, width: out.width, height: out.height, width700: thumb.width };
      written++;
    }
    manifest[nn] = entry;
    photos.push({
      src: `/frontend-test/real/${slug}/${nn}.webp`,
      src700: `/frontend-test/real/${slug}/${nn}-700.webp`,
      width: entry.width,
      height: entry.height,
      width700: entry.width700,
      alt: info.alt?.[i + 1] ?? `${name} figure, photo ${i + 1} of ${files.length}`,
      objectPosition: info.objectPosition?.[i + 1] ?? "50% 50%",
    });
  }

  // Drop outputs of photos that no longer exist.
  for (const f of await readdir(dir)) {
    if (f !== MANIFEST && !manifest[f.slice(0, 2)]) await rm(path.join(dir, f));
  }
  await writeFile(path.join(dir, MANIFEST), JSON.stringify(manifest, null, 2));

  const inRange = (n) => Number.isInteger(n) && n >= 1 && n <= photos.length;
  if (info.cover !== undefined && !inRange(info.cover))
    fail(folder, `cover must be 1..${photos.length}`);
  if (info.hover !== undefined && !inRange(info.hover))
    fail(folder, `hover must be 1..${photos.length}`);
  const cover = (info.cover ?? 1) - 1;
  const hover = info.hover ? info.hover - 1 : photos.findIndex((_, i) => i !== cover);
  const heightCm = info.heightCm ?? 25;

  products.push({
    order: info.order ?? Infinity,
    slug,
    name,
    status: info.status ?? STATUSES[index % STATUSES.length],
    priceCents: Math.round((info.priceUsd ?? 119 + ((index * 3) % 7) * 10) * 100),
    descriptor: info.descriptor ?? `${heightCm} cm, hand-painted`,
    heightCm,
    isNew: info.isNew === true,
    description:
      info.description ??
      `${name}, 3D printed in resin and painted by hand in our studio. Every piece is finished one at a time, so small differences from the photos are part of the charm.`,
    cover,
    hover: hover === -1 || hover === cover ? null : hover,
    photos,
  });
}

// Stable sort: products without "order" keep their by-name order after the ordered ones.
products.sort((a, b) => a.order - b.order);
for (const p of products) delete p.order;

// Remove output folders whose source folder is gone (only folders this script created).
const slugs = new Set(products.map((p) => p.slug));
for (const d of await readdir(OUT, { withFileTypes: true })) {
  if (d.isDirectory() && !slugs.has(d.name) && existsSync(path.join(OUT, d.name, MANIFEST))) {
    await rm(path.join(OUT, d.name), { recursive: true });
    console.log(`removed ${d.name}/ (no source folder)`);
  }
}

const code = `// GENERATED by scripts/sync-demo-photos.mjs (npm run demo:photos). Do not edit by hand:
// change figureImage/<Name>/info.json or the photos, then run the script again.
export type DemoStatus = "IN_STOCK" | "MADE_TO_ORDER" | "COMMISSION";

export type DemoPhoto = {
  src: string;
  src700: string;
  width: number;
  height: number;
  /** Real pixel width of the src700 file (a small original is not enlarged). */
  width700: number;
  alt: string;
  /** CSS object-position used where a tile crops the photo. */
  objectPosition: string;
};

export type DemoProduct = {
  slug: string;
  name: string;
  status: DemoStatus;
  priceCents: number;
  descriptor: string;
  heightCm: number;
  isNew: boolean;
  description: string;
  /** Index into photos of the shelf cover. */
  cover: number;
  /** Index into photos of the hover image; null when there is one photo. */
  hover: number | null;
  photos: DemoPhoto[];
};

export const PRODUCTS: DemoProduct[] = ${JSON.stringify(products, null, 2)};
`;

const prettier = await import("prettier");
const formatted = await prettier.format(code, {
  ...(await prettier.resolveConfig(GENERATED)),
  filepath: GENERATED,
});
await writeFile(GENERATED, formatted);

console.log(
  `${products.length} products, ${written} photos written, ${skipped} unchanged (skipped).`,
);
