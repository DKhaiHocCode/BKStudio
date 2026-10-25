# Design — BKStudio

> Trạng thái: **Đã duyệt v1 (T-006)** · Cập nhật: 2026-10-19
> Mockup: canvas thiết kế "BKStudio — UI mockups" (link riêng tư của Khai): https://claude.ai/artifact/Y5FMwgeJNzzyf4ZA14QqkP
> Tokens dùng được ngay: [`design/tokens.css`](design/tokens.css) · Logo: [`design/logo-512.png`](design/logo-512.png)

## 1. Định hướng
**Sáng · vui · kiểu sticker.** Lấy thẳng từ logo capybara: viền đen dày, bóng đổ cứng không mờ, màu đỏ áo polo + vàng cam + nâu capybara trên nền kem. Ảnh figure (thường nhiều màu) đặt trong khung viền đen để luôn nổi bật.

## 2. Màu
| Token | Hex | Dùng cho |
|---|---|---|
| `red` | `#D63A1A` | Nút chính, chữ nhấn trong tiêu đề |
| `red-dark` | `#A82A10` | Hover, chữ nút huỷ |
| `citrus` | `#F6B634` | Khối nổi bật (Made to order), nút giỏ hàng, sticker |
| `capybara` | `#C99B70` | Nền sau ảnh |
| `bark` | `#7A4E30` | Điểm nhấn tối |
| `ink` | `#151515` | Viền, chữ chính, bóng đổ |
| `paper` | `#F4F1EA` | Nền trang |
| `cream` | `#FFFDF8` | Thẻ, ô nhập |
| `muted` / `subtle` | `#4A433C` / `#6A5E52` | Chữ phụ / nhãn nhỏ |
| Tint trạng thái | `#BFE6C6` In stock · `#FFE3A3` Made to order · `#BDE3F7` Commission · `#E4DFD6` Reserved/disabled | Nhãn (badge) — luôn chữ `ink` + viền 2px |

**Tương phản (đã đo, WCAG AA ≥ 4.5:1):** chữ cream trên `red` 4.6 · `ink` trên `citrus` 10.1 · `muted` trên `paper` 8.6 · `subtle` trên `cream` 6.2 · `red-dark` trên `cream` 6.9. Không đặt chữ trắng lên `citrus` hay `capybara`.

## 3. Chữ
- **Rubik** cho toàn site (đã chọn giữa 4 phương án; font cũ Dela Gothic One bị khó đọc).
- Tiêu đề 800 · nội dung 400 · nhãn, giá, nút 700.
- Cỡ gợi ý: H1 trang chủ `clamp(40px, 5.4vw, 68px)` · H1 trang con 40–52px · H2 36–40px · tên sản phẩm 22px · nội dung 16–19px · nhãn 13–15px. Không dùng chữ < 13px.

## 4. Hình khối
| Thành phần | Quy tắc |
|---|---|
| Viền | 3px `ink` (thẻ, nút, panel) · 2px cho badge, ô nhập |
| Bo góc | 14px nút · 22px thẻ · 30px panel lớn · 999px pill/badge |
| Bóng đổ | Cứng, lệch phải-dưới: 3px (nhỏ) · 5px (nút, thẻ) · 8px (ảnh hero, panel) — không blur |
| Sticker | Nhãn pill nghiêng 5–8°, nền `citrus`, chữ 800 — tối đa 1–2 cái mỗi màn hình |
| Ảnh sản phẩm | Khung 4:5 (trang chi tiết, hero) hoặc 1:1 (thẻ), `object-fit: cover`, viền 3px |
| Icon | SVG nét 2.4px, không dùng emoji |
| Vùng chạm | ≥ 44px |

## 5. Áp dụng vào code (T-014)
1. `src/app/layout.tsx`: thay Geist bằng `Rubik` từ `next/font/google` (weights 400/500/700/800, `variable: "--font-rubik"`).
2. `src/app/globals.css`: thay khối `:root` + dark mode mặc định bằng nội dung `docs/design/tokens.css`. **Thiết kế chỉ có giao diện sáng.**
3. Đặt logo vào `frontend/public/logo.png` (từ `docs/design/logo-512.png`).
4. Header/footer theo mockup (logo tròn viền đen, nút giỏ hàng pill `citrus`).
5. Admin (T-015, T-016) dùng cùng tokens nhưng bố cục đơn giản, không cần sticker.

## 6. Các màn hình đã có mockup
Trang chủ (desktop + mobile) · Sản phẩm · Commission · Giỏ hàng (tách 2 khối hàng sẵn / làm theo đơn) · Checkout (không có ô thẻ, checkbox điều khoản bắt buộc) · Trạng thái đơn (thanh tiến trình 6 bước).
Chưa có: tra cứu đơn, trang Policy/FAQ, toàn bộ admin — làm thẳng trong code theo tokens.

## 7. Còn mở
- Tên hiển thị: **BKStudio** hay **BK3D** (chữ trên áo logo)?
- Ảnh sản phẩm: chụp lại trên phông trơn, khung dọc 4:5, tắt watermark camera, tránh để ảnh/màn hình của người khác trong khung.
