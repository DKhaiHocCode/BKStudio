# ADR-0005: Hosting — 1 VPS ở Singapore + Docker Compose + Caddy, DNS qua Cloudflare

- Trạng thái: Accepted (có điều kiện về giá — xem mục "Chọn nhà cung cấp")
- Ngày: 2026-10-18

## Bối cảnh
- Ngân sách ~$5–15/tháng. Cần chạy: Spring Boot, Next.js (Node), PostgreSQL. Lưu lượng thấp.
- **Khách chủ yếu ở Singapore và Úc** (theo lịch sử đơn của chủ shop).
- Độ trễ ước tính từ Singapore: tới khách Singapore < 10 ms, tới Úc ~90–100 ms, tới admin ở Hà Nội ~30–50 ms. Từ EU tới hai nhóm khách này ~170–280 ms.

## Quyết định
- **Region: Singapore.**
- **1 VPS ≥ 4 GB RAM** (Spring Boot + Next.js + PostgreSQL cần khoảng 1,5–2,5 GB khi chạy).
- Mọi thứ chạy bằng **Docker Compose**: `caddy`, `frontend`, `backend`, `postgres`.
- **Caddy** làm reverse proxy + tự cấp HTTPS (Let's Encrypt).
- **Tên miền + DNS ở Cloudflare.**
- Image Docker build qua GitHub Actions, VPS kéo về và chạy.

## Chọn nhà cung cấp (quyết định lúc deploy, sau khi xem giá thật trong console)
Giá Hetzner sau đợt điều chỉnh 2026 **không thống nhất giữa các nguồn tổng hợp** và trang chính thức không hiển thị giá cố định. Hai nguồn độc lập cùng ghi: VPS Hetzner ở Singapore chỉ kèm **0,5 TB lưu lượng ra/tháng** (EU: 20 TB), vượt mức tính ~€7–8/TB.

Quy tắc chọn:
1. Ưu tiên **Hetzner Singapore**, gói 4 GB, nếu tổng (server + IPv4 + backup) **≤ $15/tháng**.
2. Nếu vượt: so sánh **DigitalOcean / Vultr Singapore** gói 4 GB.
3. Nếu tất cả đều vượt ngân sách: dùng **Hetzner EU** (rẻ nhất) và chấp nhận độ trễ cao hơn, hoặc chủ dự án duyệt nâng ngân sách.
→ **Không tiêu tiền khi chưa được chủ dự án duyệt.**

**0,5 TB có đủ không?** Ảnh (phần nặng nhất) đi qua Cloudflare R2, không tính vào băng thông VPS. Mỗi lượt xem trang chỉ tốn ~100–300 KB HTML/JS từ VPS → 0,5 TB ≈ 1,5–5 triệu lượt xem/tháng. Dư nhiều lần so với nhu cầu. Có thể bật proxy Cloudflare cho file tĩnh của Next.js để giảm thêm.

## Phương án đã cân nhắc
| Phương án | Vì sao không chọn |
|---|---|
| Region EU | Rẻ hơn, nhưng xa khách Singapore/Úc (~170–280 ms mỗi round-trip) |
| Railway (PaaS) | RAM ~$10/GB-tháng → 3 dịch vụ chạy 24/7 dễ vượt $15 |
| Vercel (frontend) + PaaS | Gói Hobby của Vercel cấm dùng thương mại; Pro $20/người/tháng |
| Oracle Cloud Free Tier | Hay bị từ chối đăng ký / thu hồi máy idle |

## Hệ quả
- ✅ Gần khách, nhanh cho cả admin.
- ✅ Dễ đổi region/nhà cung cấp: Docker Compose + restore backup + đổi DNS ≈ 1–2 giờ.
- ⚠️ Chi phí có thể cao hơn ước tính ban đầu ($6–8) — biết chính xác khi xem console.
- ⚠️ Tự chịu trách nhiệm vá bảo mật OS, firewall, SSH key (checklist trong task setup).
