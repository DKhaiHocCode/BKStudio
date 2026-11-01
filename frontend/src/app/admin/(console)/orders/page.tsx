import { Suspense } from "react";
import { OrdersList } from "./OrdersList";

export default function OrdersPage() {
  return (
    <Suspense
      fallback={
        <p role="status" className="p-8 text-center font-medium">
          Loading orders...
        </p>
      }
    >
      <OrdersList />
    </Suspense>
  );
}
