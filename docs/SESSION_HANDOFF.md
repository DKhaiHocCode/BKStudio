# Session Handoff

> Cập nhật: 2026-10-18

## Đang ở đâu
**T-001 xong** (nhánh `chore/T-001-repo-structure`, chờ tạo PR và merge). Task tiếp theo: **T-002** (Spring Boot skeleton) hoặc **T-004** (Next.js skeleton) — cả hai chỉ phụ thuộc T-001.

## Đã làm
- Phase 1–5: lập kế hoạch (yêu cầu, ADR, DB, workflow, API, security, 40 task, quy trình).
- T-001: `backend/ frontend/ infra/` (có `.gitkeep`), `.editorconfig`, `.env.example`, `infra/docker-compose.dev.yml` (PostgreSQL 16 + MinIO + tự tạo bucket `bkstudio-dev`), README có hướng dẫn chạy local.
  - Bằng chứng: Postgres `healthy`, `psql` trả PostgreSQL 16.15; MinIO `/minio/health/live` = 200, console = 200, bucket tạo thành công.

## Lưu ý / vướng
- `minio/minio` và `minio/mc` trên Docker Hub đã bị gỡ (pull access denied), quay.io trả 401. Đã chuyển sang `cgr.dev/chainguard/minio` + `minio-client` (distroless, không shell, nên `minio-init` dùng `restart: on-failure` thay vì vòng chờ). Chỉ dùng cho dev; khi cần thì xem lại cho production (R2 thật, không dùng MinIO).
- **JDK trên máy là 24 (non-LTS)** → cài **JDK 25 LTS (Eclipse Temurin)** trước T-002, không cần gỡ JDK 24 (ADR-0011).
- Windows: `python3` là alias Microsoft Store, dùng `py`/`python`; chạy `PYTHONUTF8=1 python docs/tasks.py --render` để khỏi lỗi mã hoá.
- Chưa ghi `actual` giờ cho T-001 trong `tasks.py` (cần Khai báo số giờ thực tế).

## Đang chặn
- (Trước T-032) O-2 nội dung điều khoản; O-5 link mạng xã hội.
- (Trước T-043) O-4 chọn VPS + tên miền, duyệt chi phí.
- (Trước launch) O-1 ToS Artistree.

## Việc tiếp theo
1. Khai review, push nhánh, tạo PR, bật bảo vệ nhánh `main` (PR + CI xanh), squash merge.
2. Cài JDK 25 (Temurin) và chọn làm `JAVA_HOME` / SDK của dự án.
3. **T-002** Spring Boot skeleton (4 giờ).
Mở phiên mới: *"Đọc SESSION_HANDOFF và bắt đầu T-002."*
