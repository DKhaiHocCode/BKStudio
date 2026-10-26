# Changelog — BKStudio

Ghi các thay đổi quan trọng. Mới nhất ở trên. Định dạng: ngày · mã task · mô tả ngắn.

## [Chưa phát hành]

### Tính năng
- 2026-10-26 · T-010: Catalog API công khai — `GET /api/products` (chỉ ACTIVE, có sản phẩm bán được, phân trang, lọc `series`) và `GET /api/products/{slug}` (ACTIVE + UNLISTED, cờ `unlisted`; DRAFT/ARCHIVED/không tồn tại đều 404 giống nhau). JPA entity chỉ-đọc + `ddl-auto=validate`, không lộ số tồn kho/quota, `estimatedStartMonth` = null tới T-021. Thêm `R2_PUBLIC_BASE_URL`. `ProductCatalogIT` 23 test; Surefire 11 + Failsafe 43 xanh.

### Hạ tầng
- 2026-10-26 · chore: Dependabot bỏ qua bản major của `typescript`, `eslint`, `@types/node` (typescript 7 làm hỏng lint qua `eslint-config-next`); chốt tên hiển thị **BKStudio**; bật branch protection (`backend`, `frontend`, `gitleaks`).
- 2026-10-26 · T-007: checksum SHA-256 cho Maven wrapper (3.10.0), Spotless (palantir-java-format) chạy trong `verify` nên CI đỏ khi code Java sai định dạng; định dạng lại 3 file Java. Dev chạy `./mvnw spotless:apply` để sửa. 11 test Surefire + 20 test Failsafe xanh.
- 2026-10-26 · T-005: `.github/workflows/ci.yml` (3 job song song: backend `mvnw verify` JDK 25, frontend lint/typecheck/format/build Node 22, gitleaks quét toàn bộ lịch sử bằng binary đã kiểm checksum), mọi action ghim SHA, `contents: read`, `persist-credentials: false`; `.github/dependabot.yml` (maven, npm, github-actions, hằng tuần). Thêm T-007 (Spotless + checksum Maven wrapper). `SECURITY.md` T14 cập nhật.
- 2026-10-26 · T-003: Flyway `V1__init.sql` (14 bảng, mọi constraint/index có tên tường minh), Testcontainers (PostgreSQL 16, `@ServiceConnection`), `DbConstraintsIT` 17 tình huống DATABASE.md §5 + 3 test siết thêm (độ dài hash token, TOTP bật phải có secret, email admin không phân biệt hoa/thường). Thêm `maven-failsafe-plugin` để `*IT` chạy trong `verify`. Chưa có JPA (T-010). 11 test Surefire + 20 test Failsafe xanh.
- 2026-10-18 · T-004: Next.js 16.4.2 skeleton (`frontend/`, App Router, TypeScript, Tailwind 4), API client `apiFetch`/`ApiError`, proxy `/api` → backend, error boundary hiển thị requestId, ESLint + Prettier.
- 2026-10-18 · T-002: Spring Boot 4.1.1 skeleton (`backend/`, Java 25, package `com.bkstudio`), `/api/health`, `RequestIdFilter`, `GlobalExceptionHandler` (Problem Details có `code` + `requestId`), log JSON. 11 test xanh.
- 2026-10-18 · T-001: cấu trúc repo (`backend/ frontend/ infra/`), `.editorconfig`, `.env.example`, `infra/docker-compose.dev.yml` (PostgreSQL 16 + MinIO). MinIO dùng image Chainguard vì Docker Hub đã gỡ `minio/minio`.

### Tài liệu & kế hoạch
- 2026-10-19 · T-006: thiết kế giao diện — mockup 9 màn hình trên canvas, font Rubik, design tokens (`docs/DESIGN.md`, `docs/design/tokens.css`), logo. Thêm T-006 vào kế hoạch; T-014 nhận phần áp dụng tokens (+1h).
- 2026-10-18 · ADR-0011: dùng Java 25 (LTS) thay Java 21.
- 2026-10-18 · Phase 5: quy trình phát triển (`DEVELOPMENT.md`), chiến lược test (`TESTING.md`), template PR/bug, cập nhật `CLAUDE.md`.
- 2026-10-18 · Phase 4: roadmap + 40 task (`tasks.py`, `TASKS.md`, `ROADMAP.md`). Chọn phương án A: giữ phạm vi, 10 giờ/tuần, launch ~đầu 3/2027.
- 2026-10-18 · Phase 3: thiết kế DB (chạy thử trên PostgreSQL 16, 17 tình huống đạt), workflow, API, security, deployment. Thêm commission qua sản phẩm UNLISTED.
- 2026-10-18 · Phase 2: ADR 0001–0010 (Next.js, Spring Boot, PostgreSQL, VPS Singapore, R2, Resend, thanh toán thủ công).
- 2026-10-17 · Phase 1: yêu cầu nghiệp vụ v1.

## Incidents
_(chưa có)_
