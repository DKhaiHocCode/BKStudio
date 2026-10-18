# Architecture Decision Records

Mỗi quyết định kiến trúc quan trọng = 1 file `NNNN-ten-quyet-dinh.md`.
Trạng thái: `Proposed` (chờ duyệt) → `Accepted` → (`Superseded by ADR-XXXX` nếu bị thay).
Không sửa nội dung ADR đã `Accepted`; muốn đổi thì viết ADR mới thay thế.

| ADR | Quyết định | Trạng thái |
|---|---|---|
| [0001](0001-overall-architecture.md) | Kiến trúc tổng thể: monolith + frontend riêng, chung domain | Accepted |
| [0002](0002-frontend-nextjs.md) | Frontend: Next.js (TypeScript) | Accepted |
| [0003](0003-backend-spring-boot.md) | Backend: Spring Boot (Java 21), modular monolith | Accepted |
| [0004](0004-database-postgresql.md) | Database: PostgreSQL + Flyway | Accepted |
| [0005](0005-hosting-vps-docker.md) | Hosting: 1 VPS ở Singapore + Docker Compose + Caddy, Cloudflare DNS | Accepted (có điều kiện giá) |
| [0006](0006-auth-admin-session-totp.md) | Auth: admin session cookie + TOTP 2FA; khách tra cứu đơn bằng token | Accepted |
| [0007](0007-image-storage-r2.md) | Lưu ảnh: Cloudflare R2 | Accepted |
| [0008](0008-payments-manual-payment-request.md) | Thanh toán: `PaymentRequest` thủ công, độc lập nền tảng | Accepted |
| [0009](0009-email-resend.md) | Email giao dịch: Resend | Accepted |
| [0010](0010-ops-logging-monitoring-backup.md) | Vận hành: log, giám sát, backup | Accepted |

## Mẫu
```markdown
# ADR-NNNN: Tiêu đề
- Trạng thái: Proposed | Accepted | Superseded
- Ngày: YYYY-MM-DD
## Bối cảnh
## Quyết định
## Phương án đã cân nhắc
## Hệ quả (ưu / nhược, chi phí, rủi ro, cách đổi sau này)
```
