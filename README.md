# BKStudio

Website bán figure anime in 3D, sơn tay hoàn thiện, dành cho khách quốc tế.

> **Trạng thái:** Lập kế hoạch xong (Phase 1–5). Bắt đầu code từ T-001. Launch dự kiến ~đầu tháng 3/2027.

## Tài liệu

| File | Nội dung |
|---|---|
| [`CLAUDE.md`](CLAUDE.md) | Hướng dẫn cố định cho Claude Code |
| [`docs/PROJECT_OVERVIEW.md`](docs/PROJECT_OVERVIEW.md) | Mục tiêu, phạm vi, ràng buộc, rủi ro |
| [`docs/REQUIREMENTS.md`](docs/REQUIREMENTS.md) | Yêu cầu chức năng / phi chức năng, MVP (P0/P1/P2) |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Stack, sơ đồ hệ thống, chi phí |
| [`docs/DECISIONS/`](docs/DECISIONS/README.md) | Architecture Decision Records |
| [`docs/DATABASE.md`](docs/DATABASE.md) | ERD, schema, ràng buộc, kết quả chạy thử |
| [`docs/WORKFLOWS.md`](docs/WORKFLOWS.md) | Máy trạng thái đơn, checkout, thanh toán, slot, job |
| [`docs/API_SPEC.md`](docs/API_SPEC.md) | Hợp đồng API công khai + admin |
| [`docs/SECURITY.md`](docs/SECURITY.md) | Threat model, xử lý lỗi, logging |
| [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md) | Biến môi trường, deploy, backup, khôi phục |
| [`docs/ROADMAP.md`](docs/ROADMAP.md) | Mốc, phương án thời gian, tiêu chí launch |
| [`docs/TASKS.md`](docs/TASKS.md) | 40 task chi tiết + sơ đồ phụ thuộc (sinh từ `docs/tasks.py`) |
| [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) | Quy trình làm việc, git, quy ước code, Definition of Done |
| [`docs/TESTING.md`](docs/TESTING.md) | Chiến lược test |
| [`docs/CHANGELOG.md`](docs/CHANGELOG.md) | Thay đổi quan trọng + sự cố |
| [`docs/SESSION_HANDOFF.md`](docs/SESSION_HANDOFF.md) | Tiến độ hiện tại và việc tiếp theo |

Phiên Claude Code mới: *"Đọc SESSION_HANDOFF và tiếp tục."*

## Chạy local

Yêu cầu: Docker Desktop (WSL2). Chạy từ thư mục gốc repo:

```bash
cp .env.example .env     # chỉnh mật khẩu dev nếu muốn; .env không được commit
docker compose --env-file .env -f infra/docker-compose.dev.yml up -d
```

| Dịch vụ | Địa chỉ | Ghi chú |
|---|---|---|
| PostgreSQL 16 | `127.0.0.1:5432` | DB/user/password lấy từ `.env` |
| MinIO API (giả lập R2) | `127.0.0.1:9000` | S3-compatible; bucket `bkstudio-dev` tự tạo |
| MinIO console | http://127.0.0.1:9001 | đăng nhập bằng `MINIO_ROOT_*` trong `.env` |

Kiểm tra kết nối Postgres:

```bash
docker compose --env-file .env -f infra/docker-compose.dev.yml exec postgres psql -U bkstudio -d bkstudio -c "select version();"
```

Dừng: `docker compose --env-file .env -f infra/docker-compose.dev.yml down` (thêm `-v` để xoá cả dữ liệu).

> Image MinIO trên Docker Hub đã bị gỡ, nên compose dùng bản của Chainguard (`cgr.dev/chainguard/minio`). Chỉ dùng cho dev.

### Backend

Cần JDK 25 (Temurin), `JAVA_HOME` trỏ tới nó. Từ thư mục `backend/`:

```bash
./mvnw verify                                                  # Windows CMD: mvnw.cmd verify
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev          # http://localhost:8080/api/health
```
