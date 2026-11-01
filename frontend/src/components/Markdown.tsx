import ReactMarkdown, { defaultUrlTransform } from "react-markdown";
import { focusRing } from "@/components/ui";

// Raw HTML is never parsed (no rehype-raw) and is skipped; images are disallowed.
// Only http(s) and mailto links survive, on top of react-markdown's default javascript: blocking.
function urlTransform(url: string): string {
  const safe = defaultUrlTransform(url);
  // Relative links: "/path" and "#anchor" only, never "//host" or "/\host" (browsers read both as another site).
  return /^(https?:|mailto:|#)/i.test(safe) || /^\/(?![/\\])/.test(safe) ? safe : "";
}

const heading = "mt-6 mb-2 font-extrabold";

export function Markdown({ children }: { children: string }) {
  return (
    <ReactMarkdown
      skipHtml
      disallowedElements={["img"]}
      urlTransform={urlTransform}
      components={{
        h1: ({ children }) => <h2 className={`${heading} text-2xl`}>{children}</h2>,
        h2: ({ children }) => <h2 className={`${heading} text-2xl`}>{children}</h2>,
        h3: ({ children }) => <h3 className={`${heading} text-xl`}>{children}</h3>,
        h4: ({ children }) => <h4 className={`${heading} text-lg`}>{children}</h4>,
        h5: ({ children }) => <h4 className={`${heading} text-lg`}>{children}</h4>,
        h6: ({ children }) => <h4 className={`${heading} text-lg`}>{children}</h4>,
        p: ({ children }) => <p className="my-3">{children}</p>,
        ul: ({ children }) => <ul className="my-3 list-disc pl-6">{children}</ul>,
        ol: ({ children }) => <ol className="my-3 list-decimal pl-6">{children}</ol>,
        li: ({ children }) => <li className="my-1">{children}</li>,
        a: ({ href, children }) => {
          if (!href) return <span>{children}</span>;
          const external = /^https?:/i.test(href);
          return (
            <a
              href={href}
              {...(external ? { target: "_blank", rel: "noopener noreferrer" } : {})}
              className={`font-bold underline hover:text-danger ${focusRing}`}
            >
              {children}
              {external && <span className="sr-only"> (opens in a new tab)</span>}
            </a>
          );
        },
      }}
    >
      {children}
    </ReactMarkdown>
  );
}
