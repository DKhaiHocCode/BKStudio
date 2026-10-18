# ADR-0002: Frontend — Next.js (TypeScript)

- Trạng thái: Accepted
- Ngày: 2026-10-18

## Bối cảnh
Khách đến chủ yếu từ link chia sẻ trên mạng xã hội. Khi dán link sản phẩm lên Facebook/X/Discord, ảnh và tên sản phẩm phải hiện ra (thẻ Open Graph) — việc này cần HTML được render **trên server** cho từng sản phẩm. Chủ dự án muốn học React.

## Quyết định
**Next.js (App Router, TypeScript)**, chạy ở chế độ server (Node) trên chính VPS (ADR-0005), **không dùng Vercel**.
- Trang khách (catalog, sản phẩm, policy): render trên server → có thẻ OG + SEO.
- Giỏ hàng, checkout, admin: component phía client gọi `/api`.
- Styling: Tailwind CSS.

## Phương án đã cân nhắc
| Phương án | Ưu | Nhược |
|---|---|---|
| **Next.js (chọn)** | SSR → thẻ OG/SEO; React; hệ sinh thái lớn | Khái niệm server/client component cần học thêm |
| React SPA (Vite) | Đơn giản hơn, deploy file tĩnh | **Không có thẻ OG riêng từng sản phẩm** khi share link — điểm yếu lớn với shop bán qua mạng xã hội |
| Thymeleaf trong Spring Boot | Ít code nhất, 1 codebase | Không học frontend hiện đại; UI tương tác (giỏ hàng, admin) vất vả hơn |

## Về Vercel
Tài liệu chính thức của Vercel ghi gói Hobby (miễn phí) **chỉ cho dùng cá nhân, phi thương mại**; gói Pro $20/người/tháng — vượt ngân sách. Vì vậy tự host Next.js trên VPS.

## Hệ quả
- ✅ Link sản phẩm share lên mạng xã hội có ảnh + tên + giá.
- ⚠️ Next.js chiếm thêm ~150–300 MB RAM trên VPS (đã tính trong ADR-0005).
- ⚠️ Đường cong học: phân biệt server/client component. Sẽ có hướng dẫn trong task tương ứng.
