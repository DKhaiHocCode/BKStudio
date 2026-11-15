import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";
import prettier from "eslint-config-prettier/flat";

const eslintConfig = defineConfig([
  ...nextVitals,
  ...nextTs,
  {
    // XSS guard (docs/SECURITY.md T4): React escapes output only when HTML is not injected raw.
    rules: {
      "no-restricted-syntax": [
        "error",
        {
          selector: "JSXAttribute[name.name='dangerouslySetInnerHTML']",
          message:
            "dangerouslySetInnerHTML is forbidden (XSS, docs/SECURITY.md T4). Render text through JSX; sanitized HTML needs an eslint-disable with the reason.",
        },
        {
          // Object-literal forms: createElement("div", { dangerouslySetInnerHTML }), {...{ dangerouslySetInnerHTML }},
          // and the quoted key "dangerouslySetInnerHTML".
          selector:
            "Property:matches([key.name='dangerouslySetInnerHTML'], [key.value='dangerouslySetInnerHTML'])",
          message:
            "dangerouslySetInnerHTML is forbidden (XSS, docs/SECURITY.md T4). Render text through JSX; sanitized HTML needs an eslint-disable with the reason.",
        },
      ],
    },
  },
  prettier, // last: turns off ESLint rules that would fight Prettier's formatting
  // Override default ignores of eslint-config-next.
  globalIgnores([
    // Default ignores of eslint-config-next:
    ".next/**",
    "out/**",
    "build/**",
    "next-env.d.ts",
  ]),
]);

export default eslintConfig;
