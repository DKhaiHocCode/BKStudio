# ADR-0001: Kiến trúc tổng thể

- Trạng thái: Accepted
- Ngày: 2026-10-18

## Bối cảnh
1 người phát triển, ~130 giờ, vài chục đơn/tháng, ngân sách ~$5–15/tháng. Mục tiêu chính là học full-stack nhưng vẫn phải chạy thật và an toàn.

## Quyết định
- **1 backend monolith** (Spring Boot) chia module rõ ràng bên trong (catalog, order, queue, payment, notification, admin).
- **1 frontend** (Next.js) cho cả trang khách và trang admin.
- Cả hai chạy **chung một domain** sau reverse proxy: `bkstudio.xxx/` → Next.js, `bkstudio.xxx/api/` → Spring Boot. Ảnh phục vụ từ `cdn.bkstudio.xxx` (R2).
- **Monorepo**: `backend/`, `frontend/`, `infra/`, `docs/`.

## Phương án đã cân nhắc
| Phương án | Vì sao không chọn |
|---|---|
| Microservices | Quá phức tạp cho 1 người và lưu lượng này; không có lợi ích thực |
| Chỉ Spring Boot + Thymeleaf (server-rendered) | Đơn giản nhất, nhưng không học được frontend hiện đại — xem ADR-0002 |
| Frontend và API ở 2 domain khác nhau | Phải xử lý CORS và cookie cross-site, khó bảo mật hơn mà không được gì |

## Hệ quả
- ✅ Chung domain → cookie admin là same-site, không cần CORS.
- ✅ Monolith dễ debug, dễ test, deploy 1 lần.
- ⚠️ Hai codebase (Java + TypeScript) — tốn thời gian hơn Thymeleaf; bù lại đúng mục tiêu học.
- Đổi sau này: các module tách ranh giới rõ nên có thể tách service nếu thật sự cần.
