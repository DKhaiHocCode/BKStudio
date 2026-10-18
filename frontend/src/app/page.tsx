import { HealthStatus } from "@/components/HealthStatus";

export default function Home() {
  return (
    <main className="mx-auto flex max-w-3xl flex-1 flex-col justify-center gap-4 px-4 py-16">
      <h1 className="text-3xl font-semibold">BKStudio</h1>
      <p className="text-neutral-600 dark:text-neutral-400">
        Hand-painted 3D printed anime figures. The shop is under construction.
      </p>
      <HealthStatus />
    </main>
  );
}
