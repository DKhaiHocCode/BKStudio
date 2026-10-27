# Mockups

Source export of the design canvas "BKStudio — UI mockups" (private link in `../../DESIGN.md`). Updated 2026-10-28.
These files are a **reference for building the real UI in `frontend/`**. They are not runnable pages.

| Folder | Status |
|---|---|
| `light/` | **Approved v1 (T-006).** This is what we build for launch. |
| `dark/` | **Exploration only (P1, not in launch scope).** Do not implement until Khai approves dark mode. Contrast is estimated, not measured. |

| Screen | Light | Dark |
|---|---|---|
| Home, desktop | `light/Main.dc.html` | `dark/MainDark.dc.html` |
| Home, mobile 390px | `light/MobileHome.dc.html` | `dark/MobileHomeDark.dc.html` |
| Product detail | `light/Product.dc.html` | `dark/ProductDark.dc.html` |
| Commission | `light/Commission.dc.html` | `dark/CommissionDark.dc.html` |
| Cart (in-stock / made-to-order split) | `light/Cart.dc.html` | `dark/CartDark.dc.html` |
| Checkout (no card fields, mandatory terms) | `light/Checkout.dc.html` | `dark/CheckoutDark.dc.html` |
| Order status (6-step progress) | `light/OrderStatus.dc.html` | `dark/OrderStatusDark.dc.html` |
| Style tile: colours, buttons, badges, type | `light/Brand.dc.html` | `dark/BrandDark.dc.html` |
| Font comparison (Rubik chosen) | `light/Fonts.dc.html` | — |

`canvas.json` is the canvas layout metadata.

## Dark palette (exploration)
| Token | Hex | Use |
|---|---|---|
| night | `#1A1714` | page ground, inputs |
| surface | `#2A2520` | cards, header, panels |
| cream | `#F4F1EA` | text, 3px outlines |
| soft cream | `#D9D0C4` | body text |
| muted | `#CFC6BA` / `#B5AA9C` | secondary text / small labels |
| divider | `#5A5249` | dashed separators |
| shadow | `#F6B634` (citrus) | hard offset shadow, replaces ink |
| coral | `#FF8A70` | danger text (cancel), replaces red-dark |
Unchanged: red buttons, citrus panels, pastel badges and info boxes (keep ink text).

## How to read them
- Markup and inline styles are plain HTML/CSS. Copy layout, spacing, copy text and colours; the light tokens are in `../tokens.css`.
- `{{name}}` placeholders and `<sc-for>` / `<sc-if>` are the canvas template syntax. Sample data is in the `renderVals()` script at the bottom of each file. `data-props` holds design tweaks (e.g. shadow colour in dark).
- `/_blob/...` image paths only resolve inside the canvas. The logo is `../logo-512.png`. Product photos are placeholders until reshoot (DESIGN.md §7).
- Text in `[BRACKETS]` is placeholder content (price, month, handles).
