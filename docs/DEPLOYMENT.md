# Deployment, Backup & Recovery — BKStudio

> Trạng thái: **Đã duyệt (Phase 3)** · Cập nhật: 2026-11-15
> Lệnh chạy production bằng Docker Compose: §3 (T-042). Pipeline CI build/push image: T-045.

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
| `RESEND_API_KEY`, `MAIL_FROM` | Email. Profile `prod` không khởi động nếu thiếu một trong hai. Domain gửi cấu hình SPF/DKIM/DMARC theo ADR-0009 |
| `TOTP_ENCRYPTION_KEY` | Mã hoá secret TOTP trong DB |
| `FORWARD_HEADERS_STRATEGY`, `TRUSTED_PROXIES` | Sau Caddy: `native` + `TRUSTED_PROXIES` = địa chỉ của Caddy để rate limit theo IP khách thật. Mặc định `none`/rỗng = bỏ qua `X-Forwarded-For` (chống giả IP). **`native` + `TRUSTED_PROXIES` rỗng = Tomcat KHÔNG tin proxy nào** (đã kiểm bytecode Tomcat 11.0.27: chuỗi rỗng => regex và CIDR đều null), không phải "mọi dải private". Giá trị nhận regex hoặc CIDR (chứa `/` là CIDR). Khuyến nghị: compose gán subnet cố định và `ipv4_address` cố định cho Caddy, rồi đặt vd `172.30.0.10/32` (đừng tin cả dải `172.x`: container frontend cũng nằm trong đó). **Không bao giờ publish cổng backend ra host** (Docker NAT khiến traffic bên ngoài trông như peer `172.x` đáng tin). **Không dùng strategy `framework`** (tin `X-Forwarded-For` của bất kỳ client). Profile prod sẽ từ chối khởi động nếu giá trị này trống (`ProxyConfigValidator`). IPv6: sau userland-proxy của Docker mọi client IPv6 có thể chung IP gateway (rate limit gộp chung) — T-042 xử lý bằng cách chỉ publish cổng Caddy trên IPv4 (xem §3). Caddy dùng `infra/Caddyfile`; biến `SITE_ADDRESS`, `CDN_ORIGIN` (bắt buộc ở prod, rỗng = CSP chặn ảnh R2) |
Upload ảnh (T-013): backend đặt `spring.servlet.multipart.max-file-size=10MB`, `max-request-size=11MB`; Caddy/Cloudflare phía trước phải cho body tối thiểu 11 MiB trên `/api/admin/**` (riêng ảnh tiến trình `/api/admin/orders/*/progress/*` là 52 MiB, T-029; `infra/Caddyfile` đã đặt đúng, dùng MiB vì Caddy "MB" là SI). Tomcat ghi file upload tạm vào `java.io.tmpdir` nên thư mục này phải ghi được trong container (kể cả khi filesystem read-only).
Một file `.env.example` (không có giá trị thật) sẽ nằm trong repo.

## 3. Chạy production bằng Docker Compose (T-042)
File: `infra/docker-compose.yml` (project `bkstudio`): `caddy` → `frontend` + `backend` → `postgres`.
- Chỉ `caddy` publish cổng (80, 443, 443/udp). `backend`, `frontend`, `postgres` **không** publish cổng nào.
- Mạng `db` (`internal: true`, không ra ngoài) chỉ có backend + postgres: Caddy và frontend không chạm được Postgres.
- Mạng nội bộ `172.30.0.0/24`, Caddy cố định `172.30.0.10`; IP động chỉ cấp trong `172.30.0.128/25` nên không container nào chiếm được IP của Caddy. Compose tự đặt `FORWARD_HEADERS_STRATEGY=native`, `TRUSTED_PROXIES=172.30.0.10/32` cho backend. Nếu subnet trùng mạng có sẵn trên VPS thì đổi subnet **và** `TRUSTED_PROXIES` cùng lúc.
- **IPv6:** cổng Caddy chỉ bind `0.0.0.0` (IPv4). Lý do: listener IPv6 đi qua docker-proxy, mọi khách IPv6 bị đổi thành IP gateway và chung một bucket rate limit. Hệ quả: **không tạo bản ghi DNS AAAA** cho domain (khi chưa có Cloudflare proxy). Muốn bật IPv6 sau này: bật IPv6 cho mạng Docker + `ip6tables` (hoặc `"userland-proxy": false` trong `/etc/docker/daemon.json`) rồi kiểm lại IP khách thật.
- Hardening: rootfs read-only + `tmpfs` (backend: `/tmp` 16 MB có `exec` vì libwebp được giải nén và nạp từ đó, file upload tạm nằm ở `/uploads` 128 MB `noexec` chỉ user app đọc được; frontend: `.next/cache`), `cap_drop: ALL` (Caddy thêm lại `NET_BIND_SERVICE`), `no-new-privileges`, `pids_limit` 256 (backend 512 vì Tomcat tới 200 thread), Postgres chạy bằng user `postgres` ngay từ đầu, log json-file 10 MB × 5. RAM: backend 1280 MB (heap 50% = 640 MB), frontend 512 MB, postgres 768 MB, caddy 128 MB = 2688 MB, còn ~1,3 GB cho hệ điều hành trên VPS 4 GB.
- Nợ đã biết: backend kết nối Postgres bằng chính user superuser của image (`POSTGRES_USER`). Nên tách user ứng dụng ít quyền sau.
- Image base đã pin theo digest (Temurin 25 JRE Ubuntu noble, Node 22 bookworm-slim, Caddy 2.11.9, Postgres 16). Cập nhật digest có chủ đích khi có bản vá.

Biến bắt buộc (compose báo lỗi ngay nếu thiếu): `POSTGRES_*`, `APP_BASE_URL`, `NEXT_PUBLIC_SITE_URL`, `R2_ENDPOINT`, `R2_BUCKET`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `R2_PUBLIC_BASE_URL`, `RESEND_API_KEY`, `MAIL_FROM`, `TOTP_ENCRYPTION_KEY`, `SITE_ADDRESS`, `CDN_ORIGIN`. `NEXT_PUBLIC_SITE_URL` được nhúng vào image frontend lúc build, nên đổi domain = build lại frontend.

Lệnh (chạy ở thư mục gốc repo trên VPS, `.env` quyền 600):
```
C="docker compose --env-file .env -f infra/docker-compose.yml"
$C config -q                 # kiểm tra file + biến bắt buộc. Đừng bỏ -q: không có -q lệnh in toàn bộ secret ra màn hình
$C build                     # build image backend + frontend (lần đầu vài phút)
$C up -d                     # chạy; Flyway tự migrate khi backend khởi động
$C ps                        # mọi service phải "healthy"
$C logs -f --tail=100 backend
curl -fsS https://<domain>/api/health     # {"groups":[...],"status":"UP"}
```
Sau lần `up -d` đầu tiên: mở site **từ một máy bên ngoài** (vd điện thoại dùng 4G), rồi `$C logs caddy | grep client_ip`. `client_ip` phải là IP public của máy đó, **không** phải `172.30.0.1` hay IP nội bộ khác. Nếu sai thì rate limit đang gộp mọi khách vào một IP: dừng lại và kiểm tra cổng/IPv6.
Mật khẩu và khoá trong `.env`: sinh dạng hex hoặc base64url (vd `openssl rand -hex 24`), không chứa `$` (compose sẽ hiểu `$` là biến).
Cập nhật bản mới: `git pull` → **backup nếu có migration** → `$C build && $C up -d`. Rollback: checkout commit trước, build lại, `up -d` (migration chỉ thêm, xem dưới).
Dự kiến T-045: GitHub Actions build image → GitHub Container Registry, VPS chỉ `docker compose pull && docker compose up -d`.

Quy tắc chung:
1. Flyway tự chạy migration khi backend khởi động. **Trước migration có thay đổi lớn → backup thủ công.**
2. Sau deploy: kiểm tra `/api/health` + mở trang chủ.
3. Migration chỉ thêm (không xoá cột trong cùng bản phát hành) để rollback an toàn.

### Chạy thử giống production trên máy local
`infra/docker-compose.local-test.yml` dùng đúng image, profile `prod` và Caddy như production, nhưng là project riêng `bkstudio-localtest` (volume riêng) và chỉ chạy khi env có `LOCAL_TEST_ONLY=1`. Khác production: MinIO thay R2 (đi qua một Caddy TLS nội bộ `https://s3.bkstudio.test` vì profile prod bắt buộc `https://`, backend tin CA nội bộ đó qua truststore riêng) và tắt outbox để không gọi Resend. **Không dùng file này trên VPS.**
Tạo file env giả **ngoài repo** (vd `%TEMP%\local-test.env`), giá trị ngẫu nhiên:
```
LOCAL_TEST_ONLY=1
POSTGRES_DB=bkstudio
POSTGRES_USER=bkstudio
POSTGRES_PASSWORD=<ngẫu nhiên>
# APP_BASE_URL chỉ để qua validator prod (bắt buộc https); link đơn trong email trỏ sai origin, không sao
APP_BASE_URL=https://localhost
NEXT_PUBLIC_SITE_URL=http://localhost:8088
SITE_ADDRESS=:80
HTTP_PORT=8088
HTTPS_PORT=8443
CDN_ORIGIN=http://localhost:9100
R2_ENDPOINT=https://s3.bkstudio.test
R2_BUCKET=bkstudio-localtest
R2_ACCESS_KEY_ID=localtest
R2_SECRET_ACCESS_KEY=<ngẫu nhiên>
R2_PUBLIC_BASE_URL=http://localhost:9100/bkstudio-localtest
RESEND_API_KEY=re_fake_localtest_only
MAIL_FROM=BKStudio <orders@example.test>
TOTP_ENCRYPTION_KEY=<base64 32 byte, lệnh trong .env.example>
```
```
C="docker compose --env-file <đường dẫn>/local-test.env -f infra/docker-compose.yml -f infra/docker-compose.local-test.yml"
$C up -d --build             # shop thật ở http://localhost:8088 (khác APP_BASE_URL ở trên)
# E2E qua Caddy: cần admin trước (lệnh §8, thay -f bằng hai file local-test) để có E2E_TOTP_SECRET:
#   cd e2e && E2E_BASE_URL=http://localhost:8088 E2E_ADMIN_EMAIL=... E2E_ADMIN_PASSWORD=... E2E_TOTP_SECRET=... npx playwright test
$C down -v                   # CHỈ cho local-test: xoá project bkstudio-localtest và dữ liệu thử của nó
```
**Cảnh báo:** `down -v` xoá volume, kể cả database. Không bao giờ chạy `down -v` với `infra/docker-compose.yml` trên VPS (mất `pgdata` = mất đơn hàng). Trên VPS chỉ dùng `down` (không `-v`) khi thật cần.
(`HTTP_PORT=8088` vì trên Windows cổng 80 thường bị dịch vụ hệ thống chiếm.)

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
Ở prod (Postgres đang chạy), dùng chính image backend; mật khẩu chỉ đi qua biến môi trường của shell, `-e` không kèm giá trị:
```
read -rs ADMIN_INITIAL_PASSWORD && export ADMIN_INITIAL_PASSWORD     # bash, gõ mật khẩu không hiện
docker compose --env-file .env -f infra/docker-compose.yml run --rm -e ADMIN_INITIAL_PASSWORD backend --create-admin=<email>
unset ADMIN_INITIAL_PASSWORD
```
`run --rm` xoá container cùng file log json-file của nó khi thoát, nên secret TOTP không nằm lại trong log Docker. Nếu SSH bị rớt giữa chừng, `--rm` có thể không chạy: kiểm `docker ps -a` và `docker rm` container `bkstudio-backend-run-...` còn sót (log của nó chứa secret).
- Ứng dụng **không** mở web server ở chế độ này; in **một lần** ra stdout: secret TOTP (Base32), URI `otpauth://` và 10 mã khôi phục, rồi thoát (exit 0). Lỗi → exit khác 0. Email và mật khẩu không bao giờ được in.
- Từ chối nếu email đã tồn tại hoặc mật khẩu < 12 ký tự.
- Quét URI/secret bằng app authenticator, cất mã khôi phục ở nơi an toàn, rồi xoá `ADMIN_INITIAL_PASSWORD` khỏi môi trường/`.env`.
- **Bảo mật khi chạy ở prod:** dùng `docker compose run --rm` (không `-d`, không gửi vào log collector) để stdout chỉ hiện trên terminal của bạn; **không** gửi stdout của container này vào log driver (json-file/loki...) vì nó chứa secret TOTP và mã khôi phục. Truyền `ADMIN_INITIAL_PASSWORD` **chỉ qua biến môi trường** (không bao giờ là đối số dòng lệnh, vì lộ qua `ps`/history); sau khi dùng xong, không giữ nó trong `.env`/compose. Lưu ngay secret và mã khôi phục vào password manager.
