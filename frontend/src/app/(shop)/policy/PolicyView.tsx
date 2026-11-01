"use client";

import { Markdown } from "@/components/Markdown";
import { useTerms } from "@/lib/useTerms";

export function PolicyView() {
  const { terms } = useTerms();
  switch (terms.status) {
    case "loading":
      return <p className="m-0 text-muted">Loading…</p>;
    case "none":
      return (
        <p className="m-0 text-muted">
          Our terms and policy have not been published yet. Please check back soon.
        </p>
      );
    case "error":
      return (
        <p role="alert" className="m-0 text-danger">
          We could not load the terms and policy. Please refresh the page to try again.
        </p>
      );
    case "ready":
      return (
        <>
          <p className="m-0 text-sm text-muted">Version {terms.version}</p>
          <Markdown>{terms.contentMd}</Markdown>
        </>
      );
  }
}
