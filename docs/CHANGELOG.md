# Changelog — BKStudio

Ghi các thay đổi quan trọng. Mới nhất ở trên. Định dạng: ngày · mã task · mô tả ngắn.

## [Chưa phát hành]

### Hạ tầng
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
