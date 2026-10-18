"use client";

import { useEffect } from "react";
import { ErrorView } from "@/components/ErrorView";

export default function Error({ error, retry }: { error: unknown; retry: () => void }) {
  useEffect(() => {
    // Real error reporting arrives with T-046 (monitoring); for now the console is enough.
    console.error(error);
  }, [error]);

  return <ErrorView error={error} retry={retry} />;
}
