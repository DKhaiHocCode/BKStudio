# Deployment, Backup & Recovery — BKStudio

> Trạng thái: **Đã duyệt (Phase 3)** · Cập nhật: 2026-10-18
> Chi tiết lệnh cụ thể sẽ bổ sung khi làm task deploy ở Phase 4.

## 1. Môi trường
| Môi trường | Ở đâu | Dữ liệu |
|---|---|---|
| `local` | Máy của Khai — Docker Compose (Postgres) + chạy backend/frontend trực tiếp | Dữ liệu giả |
| `prod` | VPS Singapore (ADR-0005) — Docker Compose đầy đủ | Thật |
Chưa cần môi trường staging; dùng nhánh + chạy local trước khi deploy.

## 2. Biến môi trường (`.env` trên VPS, quyền 600 — không commit)
| Biến | Dùng cho |
|---|---|
| `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | Database |
| `APP_BASE_URL` | Tạo link trong email (vd `https://bkstudio.xxx`) |
| `R2_ENDPOINT` (`https://<account>.r2.cloudflarestorage.com`), `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `R2_BUCKET`, `R2_PUBLIC_BASE_URL`, `R2_REGION` (tuỳ chọn, mặc định `auto`) | Ảnh. Token R2 chỉ quyền Object Read & Write trên bucket ảnh. Profile `prod` không khởi động nếu thiếu hoặc endpoint không phải `https://` |
| `R2_BACKUP_BUCKET`, `R2_BACKUP_ACCESS_KEY_ID`, `R2_BACKUP_SECRET_ACCESS_KEY` | Backup (khoá riêng, chỉ ghi) |
| `BACKUP_ENCRYPTION_PASSPHRASE` | Mã hoá file backup |
| `RESEND_API_KEY`, `MAIL_FROM` | Email |
| `TOTP_ENCRYPTION_KEY` | Mã hoá secret TOTP trong DB |
| `ALLOWED_PAYMENT_HOSTS` | Danh sách tên miền link thanh toán |
Upload ảnh (T-013): backend đặt `spring.servlet.multipart.max-file-size=10MB`, `max-request-size=11MB`; Caddy/Cloudflare phía trước phải cho body tối thiểu 11 MB trên `/api/admin/**`. Tomcat ghi file upload tạm vào `java.io.tmpdir` nên thư mục này phải ghi được trong container (kể cả khi filesystem read-only).
Một file `.env.example` (không có giá trị thật) sẽ nằm trong repo.

## 3. Quy trình deploy (dự kiến)
1. Merge vào `main` → GitHub Actions: chạy test → build image backend + frontend → đẩy lên GitHub Container Registry.
2. Trên VPS: `docker compose pull && docker compose up -d` (thủ công ở MVP; tự động hoá P1).
3. Flyway tự chạy migration khi backend khởi động. **Trước migration có thay đổi lớn → backup thủ công.**
4. Kiểm tra `/api/health` + mở trang chủ.
5. Rollback: chạy lại image tag trước. Migration chỉ thêm (không xoá cột trong cùng bản phát hành) để rollback an toàn.

## 4. Backup
| Thứ gì | Cách | Tần suất | Giữ |
|---|---|---|---|
| PostgreSQL | `pg_dump -Fc` → mã hoá (age/gpg) → upload R2 bucket riêng | Hằng ngày 03:00 SGT + trước mỗi deploy có migration | 14 bản ngày + 8 bản tuần |
| Ảnh sản phẩm | Đã nằm trên R2 (độ bền cao); ảnh gốc admin tự giữ trên máy | — | — |
| Cấu hình (`docker-compose.yml`, `Caddyfile`) | Trong git | Mỗi thay đổi | Vĩnh viễn |
| `.env` | Bản sao mã hoá trong trình quản lý mật khẩu của Khai | Mỗi thay đổi | — |
| Toàn bộ VPS | Backup/snapshot của nhà cung cấp | Hằng ngày (nếu nằm trong ngân sách) | Theo nhà cung cấp |

Backup thất bại → email cảnh báo (job ghi vào outbox).

## 5. Mục tiêu khôi phục
- **RPO (mất tối đa bao nhiêu dữ liệu): 24 giờ.** Chấp nhận được vì vài đơn/ngày, và mọi email gửi khách + invoice Artistree là nguồn để nhập lại.
- **RTO (sập bao lâu thì chạy lại): 4 giờ.**

## 6. Quy trình khôi phục (sẽ diễn tập trước launch)
1. Thuê VPS mới (cùng hoặc khác nhà cung cấp), cài Docker.
2. Lấy repo + `.env` từ trình quản lý mật khẩu.
3. Tải bản backup mới nhất từ R2, giải mã, `pg_restore` vào Postgres mới.
4. `docker compose up -d`, kiểm tra health + vài đơn gần nhất.
5. Trỏ DNS Cloudflare sang IP mới.
6. Ghi lại thời gian thực tế vào file này.

## 7. Giám sát
- Uptime monitor ping `/api/health` mỗi 5 phút → email khi sập.
- Dashboard admin hiện: email gửi lỗi, backup gần nhất thành công lúc nào.
- P1: Sentry cho lỗi backend/frontend.

## 8. Tạo tài khoản admin đầu tiên (CLI, chạy một lần)
Cần `TOTP_ENCRYPTION_KEY` (base64 của 32 byte ngẫu nhiên, sinh bằng lệnh trong `.env.example`) và DB đã chạy.
```
cd backend
$env:ADMIN_INITIAL_PASSWORD = "<mật khẩu >= 12 ký tự>"   # PowerShell; bash: export ADMIN_INITIAL_PASSWORD=...
./mvnw spring-boot:run "-Dspring-boot.run.profiles=dev" "-Dspring-boot.run.arguments=--create-admin=you@example.com"
```
Ở prod, chạy cùng image với đối số `--create-admin=<email>` và biến môi trường `ADMIN_INITIAL_PASSWORD`.
- Ứng dụng **không** mở web server ở chế độ này; in **một lần** ra stdout: secret TOTP (Base32), URI `otpauth://` và 10 mã khôi phục, rồi thoát (exit 0). Lỗi → exit khác 0. Email và mật khẩu không bao giờ được in.
- Từ chối nếu email đã tồn tại hoặc mật khẩu < 12 ký tự.
- Quét URI/secret bằng app authenticator, cất mã khôi phục ở nơi an toàn, rồi xoá `ADMIN_INITIAL_PASSWORD` khỏi môi trường/`.env`.
- **Bảo mật khi chạy ở prod:** dùng `docker compose run --rm` (không `-d`) để stdout chỉ hiện trên terminal của bạn; **không** gửi stdout của container này vào log driver (json-file/loki...) vì nó chứa secret TOTP và mã khôi phục. Truyền `ADMIN_INITIAL_PASSWORD` **chỉ qua biến môi trường** (không bao giờ là đối số dòng lệnh, vì lộ qua `ps`/history); sau khi dùng xong, không giữ nó trong `.env`/compose. Lưu ngay secret và mã khôi phục vào password manager.
