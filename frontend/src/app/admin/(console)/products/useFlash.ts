"use client";

import { useCallback, useEffect, useRef, useState } from "react";

/** A confirmation message that clears itself after ~1.5 s; render it in a role="status" element. */
export function useFlash() {
  const [message, setMessage] = useState<string | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout>>(undefined);

  useEffect(() => () => clearTimeout(timer.current), []);

  const flash = useCallback((text: string) => {
    clearTimeout(timer.current);
    setMessage(text);
    timer.current = setTimeout(() => setMessage(null), 1500);
  }, []);

  return [message, flash] as const;
}
