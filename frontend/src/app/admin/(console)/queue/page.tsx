import { Suspense } from "react";
import { QueueView } from "./QueueView";

export default function QueuePage() {
  return (
    <Suspense
      fallback={
        <p role="status" className="p-8 text-center font-medium">
          Loading queue...
        </p>
      }
    >
      <QueueView />
    </Suspense>
  );
}
