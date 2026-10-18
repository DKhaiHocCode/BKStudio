# Session Handoff

> Cập nhật: 2026-10-18

## Đang ở đâu
**T-002 code xong, chờ Khai review + tạo PR** (nhánh `feat/T-002-spring-boot-skeleton`). T-001 đã merge (PR #1). Thiếu `actual` giờ của T-002. Task tiếp theo: **T-003** (Flyway V1 + Testcontainers) hoặc **T-004** (Next.js skeleton).

## Đã làm
- T-001: repo structure + `infra/docker-compose.dev.yml` (Postgres 16 + MinIO) — xong, 3 giờ thực tế.
- T-002: Spring Boot 4.1.1 (Maven, Java 25, package gốc `com.bkstudio`) trong `backend/`.
  - Package module: catalog, order, queue, payment, notification, admin (chỉ `package-info`), `shared/web`: `RequestIdFilter`, `ErrorCode`, `ApiException`, `GlobalExceptionHandler`.
  - `GET /api/health` = actuator health (base-path `/api`, chỉ lộ `health`). Log JSON (ECS) mặc định; profile `dev` dùng log text.
  - **Bằng chứng:** `mvnw verify` BUILD SUCCESS, 11 test xanh (JDK Temurin 25.0.4.1). Chạy jar thật: `/api/health` 200 `status=UP` + header `X-Request-Id`; `/api/nope` trả 404 Problem Details có `code` + `requestId`.

## Lưu ý / quyết định nhỏ cần Khai biết
- Máy Khai vẫn chưa có JDK 25 (chỉ jdk-23, jdk-24; `JAVA_HOME` → jdk-24). Tôi build bằng bản Temurin 25 giải nén trong thư mục tạm của phiên, **chưa cài vào hệ thống**. Khai cần cài JDK 25 và đặt `JAVA_HOME` trước khi tự chạy `mvnw.cmd verify`.
- `ErrorCode.BAD_REQUEST` (400/405/415… không có mã riêng) là thêm mới, **chưa có trong API_SPEC §1** → cần quyết định giữ hay đổi.
- `type` của lỗi đang dùng placeholder `https://bkstudio.xxx/errors/<slug>` theo API_SPEC; đổi khi có tên miền (O-4).
- Request ID là UUID không gạch (32 ký tự hex), chưa phải ULID như ví dụ trong API_SPEC.
- Log JSON dùng định dạng ECS có sẵn của Spring Boot: trường là `@timestamp`, `log.level`, `log.logger`, `message`, `requestId` (khác tên `timestamp/level/logger` trong SECURITY §4 một chút).
- `/api/health` trả thêm `"groups":["liveness","readiness"]` ngoài `status`.
- Mockito in cảnh báo self-attach trên JDK mới (chưa ảnh hưởng, xử lý khi nâng JDK).
- Windows: `python3` là alias Store, dùng `python`; chạy `PYTHONUTF8=1 python docs/tasks.py --render`. PowerShell 5.1 `Set-Content -Encoding utf8` ghi BOM, làm javac lỗi — dùng công cụ Write hoặc `UTF8Encoding($false)`.

## Đang chặn
- (Trước T-032) O-2 nội dung điều khoản; O-5 link mạng xã hội.
- (Trước T-043) O-4 chọn VPS + tên miền, duyệt chi phí.
- (Trước launch) O-1 ToS Artistree.

## Việc tiếp theo
1. Khai cài JDK 25, review, push, tạo PR T-002 (CI chưa có tới T-005), squash merge; báo số giờ thực tế để ghi `actual`.
2. **T-003** Flyway V1 + Testcontainers + 17 test DB (5 giờ) — hoặc T-004 nếu muốn làm frontend trước.
Mở phiên mới: *"Đọc SESSION_HANDOFF và bắt đầu T-003."*
