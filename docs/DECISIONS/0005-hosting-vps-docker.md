# ADR-0005: Hosting — 1 VPS + Docker Compose + Caddy, DNS qua Cloudflare

- Trạng thái: Proposed
- Ngày: 2026-10-18

## Bối cảnh
Ngân sách ~$5–15/tháng. Cần chạy: Spring Boot, Next.js (Node), PostgreSQL. Lưu lượng thấp.

## Quyết định
- **1 VPS ~4 GB RAM**, ứng viên chính **Hetzner Cloud CX23** (2 vCPU, 4 GB, 40 GB) ≈ **€4.49/tháng** có IPv4, + backup tự động ≈ 20% giá server.
- Mọi thứ chạy bằng **Docker Compose**: `caddy`, `frontend`, `backend`, `postgres`.
- **Caddy** làm reverse proxy + **tự cấp HTTPS** (Let's Encrypt).
- **Tên miền + DNS ở Cloudflare** (DNS miễn phí; registrar bán giá gốc).
- Image Docker build qua GitHub Actions, VPS kéo về và chạy.

## Phương án đã cân nhắc
| Phương án | Chi phí ước tính | Vì sao không chọn |
|---|---|---|
| **Hetzner CX23 (chọn)** | ~€5.4/tháng kèm backup | — |
| Railway (PaaS) | Hobby $5 kèm $5 usage; RAM ~$10/GB-tháng → 3 dịch vụ dễ vượt $15 | Đắt hơn khi chạy 24/7; ít học về vận hành |
| Vercel (frontend) + PaaS (backend) | $20+/tháng | Hobby của Vercel cấm thương mại |
| Oracle Cloud Free Tier | $0 | Hay bị từ chối đăng ký / thu hồi máy idle; không tin cậy cho shop thật |
| DigitalOcean / Vultr 2–4 GB | ~$12–24/tháng | Đắt hơn cho cùng cấu hình |

## Hệ quả
- ✅ Tổng chi phí thấp nhất; học Docker, reverse proxy, Linux — kỹ năng có giá trị.
- ⚠️ Tự chịu trách nhiệm vá bảo mật OS, cấu hình firewall, SSH key — có checklist trong task setup.
- ⚠️ Hetzner yêu cầu xác minh danh tính/thẻ khi đăng ký; có thể bị từ chối. Phương án dự phòng: DigitalOcean/Vultr (cùng Docker Compose, chuyển dễ).
- ❓ **Vị trí datacenter**: chọn theo nơi đa số khách ở (EU: Falkenstein/Helsinki; châu Á: Singapore). Ảnh đã qua CDN nên ảnh hưởng không lớn.
- Cần duyệt trước khi **tiêu tiền** thật (đăng ký VPS, mua domain).
