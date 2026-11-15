"use client";

import Link from "next/link";
import {
  useEffect,
  useRef,
  useState,
  useSyncExternalStore,
  type KeyboardEvent,
  type ReactNode,
} from "react";
import { focusRing } from "@/components/ui";
import { ProductImage } from "./ProductImage";

// Static slides: committed 1600x700 artwork (flat colour on the left, drawing on the right) with
// the HTML text laid over the left half. No product names or dates, so nothing goes stale.
const SLIDES = [
  {
    art: "/shop/slides/slide-1-new.svg",
    kicker: "New",
    title: "New on the shelf",
    cta: "See the figures",
    href: "/#shop",
    tone: "bg-tint-pink",
  },
  {
    art: "/shop/slides/slide-2-mto.svg",
    kicker: "Made to order",
    title: "Reserve a slot, we paint yours",
    cta: "How it works",
    href: "/#how",
    tone: "bg-tint-mto",
  },
  {
    art: "/shop/slides/slide-3-commission.svg",
    kicker: "Commission",
    title: "Your character, on your desk",
    cta: "Start a commission",
    href: "/commission",
    tone: "bg-tint-commission",
  },
  {
    art: "/shop/slides/slide-4-process.svg",
    kicker: "How we paint",
    title: "From raw print to final coat",
    cta: "See the three stages",
    href: "/#process",
    tone: "bg-tint-neutral",
  },
  {
    art: "/shop/slides/slide-5-restock.svg",
    kicker: "Restock",
    title: "Ready to ship figures",
    cta: "Shop the shelf",
    href: "/#shop",
    tone: "bg-tint-stock",
  },
];

const AUTOPLAY_MS = 5000;
const SWIPE_PX = 40;
const COUNT = SLIDES.length;
const HALF = Math.floor(COUNT / 2);

const REDUCED_MOTION = "(prefers-reduced-motion: reduce)";
function subscribeReducedMotion(onChange: () => void) {
  const mq = window.matchMedia(REDUCED_MOTION);
  mq.addEventListener("change", onChange);
  return () => mq.removeEventListener("change", onChange);
}

const roundButton =
  "flex size-11 items-center justify-center rounded-full border-[3px] border-border bg-card text-fg shadow-sticker-sm " +
  "transition-colors duration-100 hover:bg-citrus hover:text-ink motion-reduce:transition-none " +
  focusRing;

function Icon({ children }: { children: ReactNode }) {
  return (
    <svg
      viewBox="0 0 24 24"
      className="size-5"
      fill="none"
      stroke="currentColor"
      strokeWidth="3"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {children}
    </svg>
  );
}

export function HeroSlider() {
  const [index, setIndex] = useState(0);
  // null = the visitor has not pressed pause/play yet: follow the OS motion preference.
  const [userPlaying, setUserPlaying] = useState<boolean | null>(null);
  const [hovered, setHovered] = useState(false);
  const [focused, setFocused] = useState(false);
  // Server snapshot "reduced": the first paint never autoplays, so hydration always matches.
  const reducedMotion = useSyncExternalStore(
    subscribeReducedMotion,
    () => window.matchMedia(REDUCED_MOTION).matches,
    () => true,
  );
  const playing = userPlaying ?? !reducedMotion;
  // An explicit Play/Pause choice wins over the hover and focus pauses.
  const running = userPlaying ?? (!reducedMotion && !hovered && !focused);
  const slides = useRef<HTMLDivElement>(null);
  // Set when an arrow key moves away from a focused slide link: focus must follow to the new slide.
  const refocus = useRef(false);
  const dragStart = useRef<number | null>(null);
  const dragged = useRef(false);

  const go = (step: number) => setIndex((i) => (i + step + COUNT) % COUNT);

  useEffect(() => {
    if (!running) return;
    const timer = setTimeout(() => setIndex((i) => (i + 1) % COUNT), AUTOPLAY_MS);
    return () => clearTimeout(timer);
  }, [running, index]);

  useEffect(() => {
    if (!refocus.current) return;
    refocus.current = false;
    slides.current
      ?.querySelector<HTMLElement>("[aria-roledescription=slide]:not([inert]) a")
      ?.focus();
  }, [index]);

  function onKeyDown(e: KeyboardEvent<HTMLElement>) {
    if (e.key !== "ArrowLeft" && e.key !== "ArrowRight") return;
    // The old slide turns inert, which would drop focus to <body>.
    refocus.current = (e.target as HTMLElement).closest("[aria-roledescription=slide]") !== null;
    if (e.key === "ArrowLeft") go(-1);
    else if (e.key === "ArrowRight") go(1);
    else return;
    e.preventDefault();
  }

  return (
    <section
      aria-roledescription="carousel"
      aria-label="Featured"
      className="mx-auto w-full max-w-[1600px]"
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
      onFocus={() => setFocused(true)}
      onBlur={(e) => {
        if (!e.currentTarget.contains(e.relatedTarget)) setFocused(false);
      }}
      onKeyDown={onKeyDown}
    >
      {/* Every slide sits in the same grid cell, so the row keeps one fixed height (no layout shift). */}
      <div
        id="hero-slides"
        ref={slides}
        aria-live={playing ? "off" : "polite"}
        className="grid touch-pan-y justify-items-center overflow-hidden pt-4 pb-3 *:col-start-1 *:row-start-1 md:pt-6"
        onPointerDown={(e) => {
          dragStart.current = e.clientX;
          dragged.current = false;
        }}
        onPointerUp={(e) => {
          if (dragStart.current === null) return;
          const dx = e.clientX - dragStart.current;
          dragStart.current = null;
          if (Math.abs(dx) < SWIPE_PX) return;
          dragged.current = true;
          go(dx < 0 ? 1 : -1);
        }}
        onPointerCancel={() => {
          dragStart.current = null;
        }}
        onClickCapture={(e) => {
          // A mouse drag that ends on the slide link must not follow it.
          if (dragged.current) e.preventDefault();
          dragged.current = false;
        }}
      >
        {SLIDES.map((slide, i) => {
          // Position relative to the active slide, wrapped to -HALF..HALF so both neighbours peek.
          const offset = ((i - index + COUNT + HALF) % COUNT) - HALF;
          const active = offset === 0;
          return (
            <div
              key={slide.art}
              role="group"
              aria-roledescription="slide"
              aria-label={`${i + 1} of ${COUNT}`}
              inert={!active}
              className={`@container relative aspect-[16/7] w-[min(84vw,820px)] overflow-hidden rounded-[18px] border-[3px] border-border shadow-sticker transition-[transform,opacity] duration-500 motion-reduce:transition-none sm:rounded-card ${slide.tone}`}
              style={{
                transform: `translateX(calc(${offset} * (100% + clamp(10px, 2vw, 20px))))`,
                opacity: active ? 1 : Math.abs(offset) === 1 ? 0.45 : 0,
              }}
            >
              {/* Decorative: the slide text carries the meaning. Only the first slide is the LCP image. */}
              <ProductImage
                src={slide.art}
                alt=""
                width={1600}
                height={700}
                draggable={false}
                loading={i === 0 ? "eager" : "lazy"}
                fetchPriority={i === 0 ? "high" : undefined}
                className="absolute inset-0 size-full object-cover"
              />
              {/* Sizes in cqw: the text scales with the slide like art baked into the banner. */}
              <div className="absolute inset-y-0 left-0 flex w-[52%] flex-col items-start justify-center gap-[max(4px,1.6cqw)] pr-[2cqw] pl-[max(32px,5cqw)] text-ink sm:w-1/2">
                <p className="m-0 rounded-full border-2 border-ink bg-cream px-[max(6px,1.6cqw)] py-[0.3cqw] text-[length:max(10px,1.9cqw)] font-bold">
                  {slide.kicker}
                </p>
                <p className="m-0 text-[length:max(17px,5.2cqw)] leading-[1.05] font-extrabold tracking-tight">
                  <Link
                    href={slide.href}
                    draggable={false}
                    className="text-ink no-underline after:absolute after:inset-0 focus-visible:outline-none focus-visible:after:outline-[3px] focus-visible:after:-outline-offset-[6px] focus-visible:after:outline-focus"
                  >
                    {slide.title}
                  </Link>
                </p>
                <span
                  aria-hidden="true"
                  className="hidden rounded-button border-[3px] border-ink bg-red px-[2.2cqw] py-[1cqw] text-[length:max(13px,2cqw)] font-bold text-cream shadow-[3px_3px_0_var(--color-ink)] sm:inline-block"
                >
                  {slide.cta}
                </span>
              </div>
            </div>
          );
        })}
        {/* Arrow rail: same box as the active slide, so the buttons straddle its edges. */}
        <div className="pointer-events-none relative z-10 flex aspect-[16/7] w-[min(84vw,820px)] items-center justify-between">
          <button
            type="button"
            aria-label="Previous slide"
            aria-controls="hero-slides"
            onClick={() => go(-1)}
            className={`${roundButton} pointer-events-auto -ml-[22px]`}
          >
            <Icon>
              <path d="M15 5l-7 7 7 7" />
            </Icon>
          </button>
          <button
            type="button"
            aria-label="Next slide"
            aria-controls="hero-slides"
            onClick={() => go(1)}
            className={`${roundButton} pointer-events-auto -mr-[22px]`}
          >
            <Icon>
              <path d="M9 5l7 7-7 7" />
            </Icon>
          </button>
        </div>
      </div>

      <div className="flex items-center justify-center gap-1 px-4">
        <div role="group" aria-label="Choose slide" className="flex items-center">
          {SLIDES.map((slide, i) => (
            <button
              key={slide.art}
              type="button"
              aria-label={`Slide ${i + 1} of ${COUNT}: ${slide.title}`}
              aria-current={i === index ? "true" : undefined}
              aria-controls="hero-slides"
              onClick={() => setIndex(i)}
              className={`group flex h-11 min-w-11 items-center justify-center rounded-full px-1.5 ${focusRing}`}
            >
              <span
                className={`block h-3.5 rounded-full border-2 border-border transition-[width,background-color] duration-200 motion-reduce:transition-none ${i === index ? "w-9 bg-red" : "w-3.5 bg-card group-hover:bg-citrus"}`}
              />
            </button>
          ))}
        </div>
        <button
          type="button"
          aria-label={playing ? "Pause slideshow" : "Play slideshow"}
          onClick={() => setUserPlaying(!playing)}
          className={`${roundButton} border-2 shadow-none`}
        >
          <Icon>{playing ? <path d="M8 5v14M16 5v14" /> : <path d="M8 5l11 7-11 7z" />}</Icon>
        </button>
      </div>
    </section>
  );
}
