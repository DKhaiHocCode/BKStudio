# Deployment, Backup & Recovery — BKStudio

> Trạng thái: **Đã duyệt (Phase 3)** · Cập nhật: 2026-11-16
> Lệnh chạy production bằng Docker Compose: §3 (T-042). Build image trên CI + deploy/rollback: §6b (T-045).

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
| `R2_BACKUP_BUCKET`, `R2_BACKUP_ACCESS_KEY_ID`, `R2_BACKUP_SECRET_ACCESS_KEY` | Backup (bucket riêng, khoá riêng chỉ cho bucket đó; R2 không có token chỉ-ghi, xem §4). Dùng chung `R2_ENDPOINT`, `R2_REGION` |
| `BACKUP_ENCRYPTION_PASSPHRASE` | Mã hoá file backup (gpg AES256). Có bản sao trong password manager, mất là backup vô dụng |
| `BACKUP_HEALTHCHECK_URL` | Ping cảnh báo backup (ADR-0013). Không bắt buộc nhưng thiếu = không ai biết backup hỏng. Coi như secret |
| `R2_BACKUP_READ_ACCESS_KEY_ID`, `R2_BACKUP_READ_SECRET_ACCESS_KEY` | Chỉ khi khôi phục (§6), **không** đặt trong `.env` của VPS |
| `RESEND_API_KEY`, `MAIL_FROM` | Email. Profile `prod` không khởi động nếu thiếu một trong hai. Domain gửi cấu hình SPF/DKIM/DMARC theo ADR-0009 |
| `TOTP_ENCRYPTION_KEY` | Mã hoá secret TOTP trong DB |
| `TURNSTILE_SECRET_KEY` | Secret key Cloudflare Turnstile (T-060), backend dùng để gọi `siteverify` trước khi tạo đơn và trước khi tra cứu đơn (T-064). **Secret**, chỉ ở backend. Profile `prod` không khởi động nếu trống hoặc là key thử của Cloudflare (`1x…AA`, `2x…AA`, `3x…AA`). Dev/test để trống = tắt captcha |
| `NEXT_PUBLIC_TURNSTILE_SITE_KEY` | Site key Turnstile (công khai), **nhúng vào image frontend lúc build** như `NEXT_PUBLIC_SITE_URL`. Compose prod bắt buộc (thiếu widget = backend từ chối mọi đơn và mọi lượt tra cứu đơn). Dev để trống = không có widget. Đổi key = build lại frontend. Cách tạo: §3 "Cloudflare Turnstile" |
| `FORWARD_HEADERS_STRATEGY`, `TRUSTED_PROXIES` | Sau Caddy: `native` + `TRUSTED_PROXIES` = địa chỉ của Caddy để rate limit theo IP khách thật. Mặc định `none`/rỗng = bỏ qua `X-Forwarded-For` (chống giả IP). **`native` + `TRUSTED_PROXIES` rỗng = Tomcat KHÔNG tin proxy nào** (đã kiểm bytecode Tomcat 11.0.27: chuỗi rỗng => regex và CIDR đều null), không phải "mọi dải private". Tomcat đọc giá trị chứa `/` là danh sách CIDR, không có `/` là regex. Profile prod (T-047) chỉ nhận **một IPv4 literal** (dấu chấm có thể escape) hoặc **danh sách CIDR cách nhau bằng dấu phẩy, không rộng hơn `/24` (IPv4) hay `/64` (IPv6)**; regex (vd `.*`, `10\..*`) và CIDR rộng hơn → từ chối khởi động. **Proxy IPv6 phải viết dạng CIDR** (vd `fd00::10/128`): IPv6 literal không có `/` bị từ chối, vì Tomcat coi nó là regex chỉ khớp đúng một cách viết. `/24` chỉ là sàn: khai `/24` là tin mọi máy trong subnet đó (kể cả frontend); dùng `/32` cho đúng một proxy. Khuyến nghị: compose gán subnet cố định và `ipv4_address` cố định cho Caddy, rồi đặt vd `172.30.0.10/32` (đừng tin cả dải `172.x`: container frontend cũng nằm trong đó). **Không bao giờ publish cổng backend ra host** (Docker NAT khiến traffic bên ngoài trông như peer `172.x` đáng tin). **Không dùng strategy `framework`** (tin `X-Forwarded-For` của bất kỳ client). Profile prod sẽ từ chối khởi động nếu giá trị này trống hoặc quá rộng (`ProxyConfigValidator`). IPv6: sau userland-proxy của Docker mọi client IPv6 có thể chung IP gateway (rate limit gộp chung) — T-042 xử lý bằng cách chỉ publish cổng Caddy trên IPv4 (xem §3). Caddy dùng `infra/Caddyfile`; biến `SITE_ADDRESS`, `CDN_ORIGIN` (bắt buộc ở prod, rỗng = CSP chặn ảnh R2) |
Upload ảnh (T-013): backend đặt `spring.servlet.multipart.max-file-size=10MB`, `max-request-size=11MB`; Caddy/Cloudflare phía trước phải cho body tối thiểu 11 MiB trên `/api/admin/**` (riêng ảnh tiến trình `/api/admin/orders/*/progress/*` là 52 MiB, T-029; `infra/Caddyfile` đã đặt đúng, dùng MiB vì Caddy "MB" là SI). Tomcat ghi file upload tạm vào `java.io.tmpdir` nên thư mục này phải ghi được trong container (kể cả khi filesystem read-only).
Giới hạn chống lạm dụng (T-047, mặc định trong `application.yml`; `infra/docker-compose.yml` truyền các biến env sau cho backend với cùng mặc định, đặt trong `.env` để đổi): `APP_CHECKOUT_RATE_LIMIT_MAX` (5) / `APP_CHECKOUT_RATE_LIMIT_WINDOW` (1h) = `app.checkout.rate-limit-max`/`-window`, đơn theo IP; `APP_CHECKOUT_EMAIL_LIMIT_MAX` (3) / `APP_CHECKOUT_EMAIL_LIMIT_WINDOW` (1h) = `app.checkout.email-limit-max`/`-window`, đơn theo email khách (đếm trong DB theo **địa chỉ chuẩn hoá**: chữ thường, bỏ `+tag` ở phần trước `@`, nên `a+1@x.com` và `A@x.com` chung một hạn mức; vượt → `429 RATE_LIMITED`); `APP_NOTIFICATION_OUTBOX_DAILY_CAP` (80) = `app.notification.outbox.daily-cap`, email / 24 giờ cuốn chiếu (Resend free 100/ngày): email vượt trần ở lại `PENDING`, gửi tiếp khi cửa sổ trống, log `WARN` "Outbox daily cap reached".
Một file `.env.example` (không có giá trị thật) sẽ nằm trong repo.

## 3. Chạy production bằng Docker Compose (T-042)
File: `infra/docker-compose.yml` (project `bkstudio`): `caddy` → `frontend` + `backend` → `postgres`, cộng `backup` (§4).
- Chỉ `caddy` publish cổng (80, 443, 443/udp). `backend`, `frontend`, `postgres` **không** publish cổng nào.
- Mạng `db` (`internal: true`, không ra ngoài) chỉ có backend + postgres: Caddy và frontend không chạm được Postgres.
- Mạng nội bộ `172.30.0.0/24`, Caddy cố định `172.30.0.10`; IP động chỉ cấp trong `172.30.0.128/25` nên không container nào chiếm được IP của Caddy. Compose tự đặt `FORWARD_HEADERS_STRATEGY=native`, `TRUSTED_PROXIES=172.30.0.10/32` cho backend. Nếu subnet trùng mạng có sẵn trên VPS thì đổi subnet **và** `TRUSTED_PROXIES` cùng lúc.
- **IPv6:** cổng Caddy chỉ bind `0.0.0.0` (IPv4). Lý do: listener IPv6 đi qua docker-proxy, mọi khách IPv6 bị đổi thành IP gateway và chung một bucket rate limit. Hệ quả: **không tạo bản ghi DNS AAAA** cho domain (khi chưa có Cloudflare proxy). Muốn bật IPv6 sau này: bật IPv6 cho mạng Docker + `ip6tables` (hoặc `"userland-proxy": false` trong `/etc/docker/daemon.json`) rồi kiểm lại IP khách thật.
- Hardening: rootfs read-only + `tmpfs` (backend: `/tmp` 16 MB có `exec` vì libwebp được giải nén và nạp từ đó, file upload tạm nằm ở `/uploads` 128 MB `noexec` chỉ user app đọc được; frontend: `.next/cache`), `cap_drop: ALL` (Caddy thêm lại `NET_BIND_SERVICE`), `no-new-privileges`, `pids_limit` 256 (backend 512 vì Tomcat tới 200 thread), Postgres chạy bằng user `postgres` ngay từ đầu, log json-file 10 MB × 5. RAM: backend 1280 MB (heap 50% = 640 MB), frontend 512 MB, postgres 768 MB, caddy 128 MB, backup 384 MB (gần như không dùng ngoài lúc chạy đêm) = 3072 MB, còn ~1 GB cho hệ điều hành trên VPS 4 GB.
- Nợ đã biết: backend kết nối Postgres bằng chính user superuser của image (`POSTGRES_USER`). Nên tách user ứng dụng ít quyền sau.
- Image base đã pin theo digest (Temurin 25 JRE Ubuntu noble, Node 22 bookworm-slim, Caddy 2.11.9, Postgres 16). Cập nhật digest có chủ đích khi có bản vá.

Biến bắt buộc (compose báo lỗi ngay nếu thiếu): `POSTGRES_*`, `APP_BASE_URL`, `NEXT_PUBLIC_SITE_URL`, `R2_ENDPOINT`, `R2_BUCKET`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `R2_PUBLIC_BASE_URL`, `RESEND_API_KEY`, `MAIL_FROM`, `TOTP_ENCRYPTION_KEY`, `TURNSTILE_SECRET_KEY`, `NEXT_PUBLIC_TURNSTILE_SITE_KEY`, `SITE_ADDRESS`, `CDN_ORIGIN`, `R2_BACKUP_BUCKET`, `R2_BACKUP_ACCESS_KEY_ID`, `R2_BACKUP_SECRET_ACCESS_KEY`, `BACKUP_ENCRYPTION_PASSPHRASE`. `NEXT_PUBLIC_SITE_URL` và `NEXT_PUBLIC_TURNSTILE_SITE_KEY` được nhúng vào image frontend lúc build, nên đổi domain hay site key = build lại frontend.

### Cloudflare Turnstile (T-060, thủ công, một lần, trước launch)
Turnstile là captcha của Cloudflare chặn bot đặt đơn rác ở `/checkout` và bot dội form tra cứu đơn (T-064). Miễn phí, dùng được cho site **không** đi qua proxy Cloudflare; chỉ cần tài khoản Cloudflare (đã có cho R2). Đã kiểm trên tài liệu Cloudflare ngày 2026-11-16 (trang Plans, Get started, Dashboard, Testing, CSP): gói Free tới 20 widget, 10 hostname/widget, không giới hạn lượt xác minh. Tài liệu không nói gì về thẻ tín dụng; tạo widget không phải mua gì.
1. Cloudflare dashboard → **Turnstile** → **Add widget**.
2. **Widget name**: vd `bkstudio-checkout`. **Hostname management**: thêm domain shop (vd `bk3dprints.com`, thêm `www.` nếu dùng). Không thêm `localhost` (dev dùng key thử, xem dưới).
3. **Widget mode**: **Managed**. **Pre-clearance**: để tắt (không cần, chỉ dùng cho SPA dựa vào cookie clearance).
4. **Create** → copy **Site key** và **Secret key**. Ghi vào `.env` trên VPS: `NEXT_PUBLIC_TURNSTILE_SITE_KEY=<site key>`, `TURNSTILE_SECRET_KEY=<secret key>`. Lưu secret key vào password manager; không dán vào chat, issue hay log.
5. Site key nhúng vào image frontend lúc build **trên CI**: đặt site key vào repository variable `NEXT_PUBLIC_TURNSTILE_SITE_KEY` (§6b), tạo tag release mới rồi `bash infra/deploy.sh <tag>` trên VPS. Kiểm tra: mở `/checkout`, widget hiện ra, đặt một đơn thử thành công.
Đổi/xoay secret: widget → **Settings** (hoặc rotate secret), cập nhật `.env`, `$C up -d backend` (`$C` của VPS, định nghĩa ở dưới).
Key thử của Cloudflare (chỉ dev/test, chạy cả trên localhost): site key `1x00000000000000000000AA` + secret `1x0000000000000000000000000000000AA` luôn qua (secret thử chỉ nhận token giả `XXXX.DUMMY.TOKEN.XXXX`). Với secret thử, backend **vẫn gọi `siteverify` thật** của Cloudflare (cần Internet; mất mạng → `503`). Profile `prod` từ chối secret thử, trừ khi `TURNSTILE_ALLOW_TEST_SECRET=true` (chỉ đặt trong `infra/docker-compose.local-test.yml`, **không bao giờ** trong `.env` của VPS).
CSP trong `infra/Caddyfile` đã cho `https://challenges.cloudflare.com` ở `script-src` và `frame-src` (đúng hai mục tài liệu CSP của Turnstile yêu cầu). Backend gọi `https://challenges.cloudflare.com/turnstile/v0/siteverify` ra Internet qua mạng `internal` (mạng này có đường ra; mạng `db` thì không).

Lệnh (chạy ở thư mục gốc repo trên VPS, `.env` quyền 600). Đây là định nghĩa `$C` **duy nhất** cho VPS; mọi lệnh `$C` trên VPS trong tài liệu này dùng nó. Nó luôn có cả hai file compose và `IMAGE_TAG` = tag đang chạy, nên dùng đúng image release từ GHCR (§6b):
```
T="$(head -n1 infra/.deployed-tag)"   # tag đang chạy, deploy.sh ghi. Trước lần deploy đầu tiên: T=vX.Y.Z (tag sắp deploy)
C="env IMAGE_TAG=$T docker compose --env-file .env -f infra/docker-compose.yml -f infra/docker-compose.ghcr.yml"
$C config -q                 # kiểm tra file + biến bắt buộc. Đừng bỏ -q: không có -q lệnh in toàn bộ secret ra màn hình
bash infra/deploy.sh "$T"    # chạy/cập nhật: pull image, backup pre-deploy, up -d, đợi health; Flyway migrate khi backend khởi động
$C ps                        # mọi service phải "healthy"
$C logs -f --tail=100 backend
curl -fsS https://<domain>/api/health     # {"groups":[...],"status":"UP"}
```
Sau lần `up -d` đầu tiên: mở site **từ một máy bên ngoài** (vd điện thoại dùng 4G), rồi `$C logs caddy | grep client_ip`. `client_ip` phải là IP public của máy đó, **không** phải `172.30.0.1` hay IP nội bộ khác. Nếu sai thì rate limit đang gộp mọi khách vào một IP: dừng lại và kiểm tra cổng/IPv6.
Mật khẩu và khoá trong `.env`: sinh dạng hex hoặc base64url (vd `openssl rand -hex 24`), không chứa `$` (compose sẽ hiểu `$` là biến).
Cập nhật bản mới: `git fetch --tags && git checkout <tag>` → `bash infra/deploy.sh <tag>` (script tự backup `pre-deploy/` trước mỗi deploy). Rollback: `bash infra/deploy.sh --rollback`. Chi tiết §6b.
**Cảnh báo:** trên VPS **không** chạy `docker compose ... -f infra/docker-compose.yml` một mình (thiếu file `docker-compose.ghcr.yml`) với `up -d` hay `build`: compose sẽ build image local `bkstudio-*:local` từ mã đang checkout và thay image release đang chạy. Luôn dùng `$C` ở trên hoặc `deploy.sh`.

Quy tắc chung:
1. Flyway tự chạy migration khi backend khởi động. `deploy.sh` luôn backup `pre-deploy/` trước khi đổi image.
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
# Key thử "luôn qua" của Cloudflare. File prod bắt buộc có hai biến này; file local-test ghi đè đúng hai giá trị
# này và bật TURNSTILE_ALLOW_TEST_SECRET=true (chỉ local-test). Backend vẫn gọi siteverify thật: cần Internet.
NEXT_PUBLIC_TURNSTILE_SITE_KEY=1x00000000000000000000AA
TURNSTILE_SECRET_KEY=1x0000000000000000000000000000000AA
# Backup (§4) vào bucket MinIO riêng, không public; khoá = khoá root MinIO ở trên
R2_BACKUP_BUCKET=bkstudio-localtest-backup
R2_BACKUP_ACCESS_KEY_ID=localtest
R2_BACKUP_SECRET_ACCESS_KEY=<giống R2_SECRET_ACCESS_KEY>
BACKUP_ENCRYPTION_PASSPHRASE=<ngẫu nhiên>
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
| PostgreSQL | `pg_dump -Fc` → gpg AES256 → upload R2 bucket riêng (service `backup`, T-044) | Hằng ngày 03:00 SGT + trước mỗi deploy (`deploy.sh`, §6b) | `daily/` 15 ngày, `weekly/` + `pre-deploy/` 60 ngày (lifecycle R2, dưới) |
| Ảnh sản phẩm | Đã nằm trên R2 (độ bền cao); ảnh gốc admin tự giữ trên máy | — | — |
| Cấu hình (`docker-compose.yml`, `Caddyfile`) | Trong git | Mỗi thay đổi | Vĩnh viễn |
| `.env` | Bản sao mã hoá trong trình quản lý mật khẩu của Khai | Mỗi thay đổi | — |
| Toàn bộ VPS | Backup/snapshot của nhà cung cấp | Hằng ngày (nếu nằm trong ngân sách) | Theo nhà cung cấp |

Backup thất bại hoặc không chạy → email từ dịch vụ ping (ADR-0013; thay dòng cũ "email cảnh báo, job ghi vào outbox" của mục này, bổ sung cho ADR-0010).

### Cách chạy (T-044)
Code: `infra/backup/` (`Dockerfile`, `entrypoint.sh`, `backup.sh`, `restore.sh`). Image = đúng image `postgres:16` theo digest của service `postgres` (pg_dump cùng bản với server) + `curl` (upload S3 SigV4) + `gpg` có sẵn.
- `entrypoint.sh` ngủ tới 03:00 Asia/Singapore rồi chạy `backup.sh daily`, lặp mãi (không cần cron). Lỗi không làm dừng lịch.
- `backup.sh [label]`: dump vào tmpfs `/work` → `pg_restore --list` kiểm file đọc được → mã hoá gpg → xoá bản rõ → PUT lên `R2_BACKUP_BUCKET` với tên `<label>/<UTC YYYY-MM-DDTHHMMSSZ>.dump.gpg` (tên có giờ nên không bao giờ ghi đè). Chủ nhật (giờ SGT) bản `daily` được upload thêm một lần vào `weekly/`. Thoát (kể cả lỗi) luôn xoá `/work/backup.*`.
- Thành công → ping `BACKUP_HEALTHCHECK_URL`; lỗi → ping `BACKUP_HEALTHCHECK_URL/fail`, exit khác 0. Thiếu URL → log cảnh báo nhưng vẫn backup.
- Backup trước mỗi deploy: `deploy.sh` (§6b) tự chạy `$C run --rm backup /backup.sh pre-deploy`; chạy tay cùng lệnh đó (dòng cuối phải là `backup: OK`).
- Mạng: `db` (tới postgres) + `egress` (chỉ backup, để ra R2). Hardening như các service khác: user `postgres`, rootfs read-only, `cap_drop: ALL`, `no-new-privileges`, `pids_limit` 64, RAM 384 MB, tmpfs `/work` 256 MB (đủ cho dump ~120 MB; DB lớn hơn thì tăng cả hai).
- Secret: passphrase đưa cho gpg qua fd 3, khoá R2 và URL ping đưa cho curl qua config trên stdin, nên **không** hiện trong `ps`, dòng lệnh hay log (đã kiểm 2026-11-15 trên local-test: lấy mẫu `/proc/*/cmdline` liên tục trong lúc backup chạy và đọc log, 0 lần thấy passphrase, khoá R2, `PGPASSWORD` hay URL ping). Nhưng chúng là biến môi trường, nên `docker inspect bkstudio-backup-1` (cần quyền Docker = quyền root) vẫn thấy, giống mọi secret khác của backend. Compose không cho secret từ biến môi trường trên container read-only; muốn ẩn khỏi `docker inspect` thì phải dùng secret dạng file (nợ đã biết).
- Mạng `egress` ra được **mọi** địa chỉ, không chỉ R2. Muốn siết: luật iptables trong chain `DOCKER-USER` chỉ cho subnet của `egress` ra cổng 443 (chưa làm).
- Dừng/tạo lại container (`up -d` sau deploy, `docker stop`) lúc đang backup: compose gửi SIGTERM cho cả nhóm tiến trình, `backup.sh` thoát 143 và ping `/fail`. Tạo lại container ngay khoảng 03:00 có thể bỏ lỡ lần chạy đêm đó; không có ping thì healthchecks.io báo, chạy tay `backup.sh` là xong.

Nợ đã biết (backup):
- Dump bằng user superuser `POSTGRES_USER`; nên có role riêng chỉ `pg_read_all_data`.
- Mã hoá đối xứng: passphrase nằm trên VPS, nên kẻ chiếm VPS giải mã được bản cũ đọc được từ R2. Tốt hơn: mã hoá bằng public key (age hoặc gpg `--recipient`), private key chỉ ở password manager.
- Secret là biến môi trường (thấy trong `docker inspect`), xem trên.
- tmpfs 256 MB: dump tối đa ~120 MB.

### Cài đặt trên Cloudflare (thủ công, một lần, trước launch)
1. Tạo bucket R2 **riêng, private** (vd `bkstudio-backup`), không bật public access, không gắn domain.
2. Token ghi: R2 **không có quyền chỉ-ghi** (chỉ có Admin R/W, Admin Read, Object R/W, Object Read; đã kiểm tài liệu Cloudflare 2026-11-15). Tạo token **Object Read & Write, chỉ cho bucket backup** → `R2_BACKUP_ACCESS_KEY_ID`/`R2_BACKUP_SECRET_ACCESS_KEY`. Đọc được backup từ VPS không mất gì thêm (VPS đã có DB thật), cái cần chặn là **xoá/ghi đè** → bước 3.
3. **Bucket lock** (Settings → Bucket lock rules → Add rule), mỗi prefix giữ bằng đúng thời gian lưu: `daily/` 15 ngày, `weekly/` 60 ngày, `pre-deploy/` 60 ngày. Trong thời gian đó không ai (kể cả khoá trên VPS bị lộ) xoá hay ghi đè được backup. Lock thắng lifecycle. _Khai duyệt 2026-11-15._
4. **Lifecycle** (Settings → Object Lifecycle Rules), script không bao giờ xoá: `daily/` xoá sau 15 ngày, `weekly/` và `pre-deploy/` xoá sau 60 ngày (bằng lock ở trên). _Khai duyệt 2026-11-15._
   Kiểm tra lock (checklist SECURITY.md §5): dùng khoá trên VPS, DELETE và PUT ghi đè một object vừa upload phải bị từ chối (R2 trả 409 `ObjectLockedByBucketPolicy`). Kết quả: _chưa kiểm (chưa có bucket thật)_.
5. Token đọc: **Object Read only, chỉ bucket backup** → cất trong password manager làm `R2_BACKUP_READ_*`. Chỉ dùng khi khôi phục, không để trên VPS.
6. healthchecks.io (free): tạo check lịch 1 ngày, grace 2 giờ, email Khai → URL ping vào `BACKUP_HEALTHCHECK_URL`.
7. Chạy `$C up -d backup`, rồi `$C run --rm backup /backup.sh pre-deploy` một lần để thấy object trên R2 và ping trên healthchecks.io.

## 5. Mục tiêu khôi phục
- **RPO (mất tối đa bao nhiêu dữ liệu): 24 giờ.** Chấp nhận được vì vài đơn/ngày, và mọi email gửi khách + invoice Artistree là nguồn để nhập lại.
- **RTO (sập bao lâu thì chạy lại): 4 giờ.**

## 6. Quy trình khôi phục (diễn tập trước launch)
1. Thuê VPS mới (cùng hoặc khác nhà cung cấp), cài Docker.
2. Lấy repo + `.env` từ trình quản lý mật khẩu (`BACKUP_ENCRYPTION_PASSPHRASE` phải đúng bản đã dùng lúc backup).
3. Chỉ chạy Postgres + kéo image backup (DB rỗng, chưa có backend nên Flyway chưa tạo bảng). `$C` như §3, với `T=<tag release mới nhất>` (VPS mới chưa có `infra/.deployed-tag`):
   ```
   git fetch --tags && git checkout "$T"
   $C up -d postgres && $C pull backup
   ```
4. Tìm tên bản mới nhất trên dashboard R2 (bucket backup, thư mục `daily/`), rồi khôi phục. Khoá đọc chỉ nằm trong shell, `-e` không kèm giá trị:
   ```
   read -rs R2_BACKUP_READ_ACCESS_KEY_ID && read -rs R2_BACKUP_READ_SECRET_ACCESS_KEY
   export R2_BACKUP_READ_ACCESS_KEY_ID R2_BACKUP_READ_SECRET_ACCESS_KEY
   $C run --rm --no-deps -e R2_BACKUP_READ_ACCESS_KEY_ID -e R2_BACKUP_READ_SECRET_ACCESS_KEY backup \
     /restore.sh daily/<YYYY-MM-DDTHHMMSSZ>.dump.gpg postgresql://<POSTGRES_USER>@postgres:5432/<POSTGRES_DB> --allow-prod-name
   unset R2_BACKUP_READ_ACCESS_KEY_ID R2_BACKUP_READ_SECRET_ACCESS_KEY
   ```
   `restore.sh` tải → giải mã → `pg_restore --no-owner --single-transaction --exit-on-error` (lỗi giữa chừng = không ghi gì) và in `restore: OK, ... in N s`. Nó **từ chối** khi: DB đích trùng tên DB prod (`POSTGRES_DB`) mà thiếu `--allow-prod-name` (chỉ dùng cờ này trên server MỚI); DB đích đã có bảng; URL chứa mật khẩu (mật khẩu đi qua `PGPASSWORD`, compose đã đặt = `POSTGRES_PASSWORD`). Sai passphrase → `gpg: decryption failed: Bad session key`, exit 2, DB không đổi.
   Diễn tập không đụng prod: tạo DB rỗng tên khác rồi khôi phục vào đó, bỏ `--allow-prod-name`:
   `$C exec postgres createdb -U <POSTGRES_USER> bkstudio_restore` → `... /restore.sh <key> postgresql://<POSTGRES_USER>@postgres:5432/bkstudio_restore` → xem xong thì `$C exec postgres dropdb -U <POSTGRES_USER> bkstudio_restore`.
   Trên Windows Git Bash, đặt `export MSYS_NO_PATHCONV=1` trước, nếu không `/restore.sh` bị đổi thành `C:/Program Files/Git/restore.sh`.
   **Sau khi bị xâm nhập**: chỉ khôi phục bản backup có ngày **trước** thời điểm bị xâm nhập (bản sau có thể đã bị sửa dữ liệu), rồi đổi passphrase backup và **mọi** secret trong `.env` (DB, R2, Resend, TOTP key, URL ping) cùng các token R2.
5. `bash infra/deploy.sh "$T"` (§6b), kiểm tra health + vài đơn gần nhất (Flyway thấy schema đã có, chỉ chạy migration mới hơn bản backup).
6. Trỏ DNS Cloudflare sang IP mới.
7. Ghi lại thời gian thực tế vào bảng dưới.

### Thời gian khôi phục đã đo
| Ngày | Ở đâu | Dữ liệu | Backup (dump+mã hoá+upload) | Restore (`restore.sh`) | Ghi chú |
|---|---|---|---|---|---|
| 2026-11-15 | Máy dev, local-test (MinIO), Docker Desktop | DB 19 MB: schema V1–V3, 20 000 product + 20 000 variant + 24 tháng; file mã hoá 70 KB | 3,1 s (gồm khởi động container) | 1 s theo script, 1,7 s gồm khởi động container | Số dòng 4 bảng chính khớp nguồn. Chưa đo với R2 thật |
| _trước launch_ | VPS thật, R2 thật | | | | Diễn tập theo bước 1–5, ghi cả thời gian thuê VPS/DNS (RTO 4 giờ) |

## 6b. Deploy và rollback (T-045)
Luồng: tag release trên `main` → GitHub Actions build + push image lên GHCR → trên VPS checkout đúng tag rồi chạy **một lệnh** `infra/deploy.sh`. CI **không bao giờ** SSH vào VPS; deploy luôn do Khai gõ.

**1. Tạo release** (máy local, sau khi PR đã merge vào `main` và CI xanh):
```
git checkout main && git pull
git tag v1.0.0 && git push origin v1.0.0
```
Workflow `.github/workflows/deploy.yml` ("Release images") chạy khi push tag `v*`:
- Từ chối tag không đúng dạng `vX.Y.Z` hoặc trỏ vào commit **không** nằm trên `main` (`git merge-base --is-ancestor`).
- Từ chối nếu repository variable `NEXT_PUBLIC_SITE_URL` không có dạng `https://<host>` (không path) hoặc `NEXT_PUBLIC_TURNSTILE_SITE_KEY` trống / có ký tự lạ.
- Build 3 image `backend`, `frontend`, `backup` cho `linux/amd64` → `ghcr.io/dkhaihoccode/bkstudio-<name>:<tag>` và `:sha-<commit>`. Digest của từng image ghi vào Job summary; `deploy.sh` in digest đã pull để so.
- Quyền: chỉ `GITHUB_TOKEN` (`contents: read`, `packages: write` chỉ ở job build). Không dùng secret nào khác.
- **Tag phải bất biến:** một tag đã release không bao giờ được xoá, đẩy lại hay trỏ sang commit khác (nếu không, VPS và GHCR có thể chạy mã khác với cái đã review). Sửa lỗi = tag mới (`v1.0.1`).

**2. Cài đặt một lần (thủ công, Khai làm):**
- **Tag ruleset:** GitHub → repo Settings → Rules → Rulesets → New tag ruleset, target `v*`, bật **Restrict creations**, **Restrict updates**, **Restrict deletions**, bypass list chỉ có Admin (Khai). Người khác (hay token bị lộ) không tạo/sửa/xoá được tag release.
- **Branch protection `main`:** xác nhận rule hiện có bắt buộc các check CI xanh trước khi merge (tag chỉ được tạo trên commit của `main`, nên đây là cổng kiểm tra duy nhất trước release).
- Repository variables (Settings → Secrets and variables → Actions → **Variables**, không phải Secrets vì là giá trị công khai): `NEXT_PUBLIC_SITE_URL` (vd `https://bk3dprints.com`), `NEXT_PUBLIC_TURNSTILE_SITE_KEY` (site key thật, §3). Đổi một trong hai = tạo tag mới để build lại frontend. Giá trị `NEXT_PUBLIC_*` trong `.env` của VPS vẫn bắt buộc (compose kiểm) nhưng **không** dùng nữa: image đã nhúng giá trị lúc build trên CI.
- **GHCR Public:** sau lần push tag đầu tiên, vào GitHub → Packages → từng package `bkstudio-backend`, `bkstudio-frontend`, `bkstudio-backup` → Package settings → Change visibility → **Public**. Repo đã public nên image không lộ thêm gì; image không chứa secret (chỉ `NEXT_PUBLIC_*` vốn công khai). VPS pull không cần đăng nhập. Chưa đặt Public thì `deploy.sh` lỗi ở bước pull.
- **VPS phải là x86_64 (amd64):** image chỉ build cho `linux/amd64`; VPS ARM sẽ không chạy được. Ghi chú này áp dụng khi chọn VPS ở T-043.
- VPS: Docker Compose bản mới (file `infra/docker-compose.ghcr.yml` dùng tag `!reset`; đã kiểm với Compose v5.6.1) và `flock` (gói `util-linux`, có sẵn trên Ubuntu/Debian).
- **Không** đặt `IMAGE_TAG` trong `.env` của VPS: `deploy.sh` tự đặt.

**3. Deploy** (trên VPS, trong thư mục repo, có `.env`). Checkout **đúng tag** trước để compose, Caddyfile và script khớp với image:
```
git fetch --tags && git checkout v1.0.0
bash infra/deploy.sh v1.0.0
```
`deploy.sh` làm lần lượt:
1. Kiểm: đúng một tham số, tag `vX.Y.Z`, có `.env`, có `flock`; khoá `infra/.deploy.lock` (một deploy chạy cùng lúc); file trạng thái `infra/.deployed-tag` hợp lệ (sai → dừng "corrupt state", sửa tay: dòng 1 tag đang chạy, dòng 2 tag trước).
2. `pull` image của tag, in digest của 3 image.
3. **Backup trước deploy** (`run --rm backup /backup.sh pre-deploy`, cùng lệnh §4, file vào `pre-deploy/`); lỗi = dừng, chưa đổi gì.
4. Ghi `infra/.deploying` (= deploy đang dở), rồi `up -d --wait --wait-timeout 180`: đợi tới 3 phút cho mọi service running và healthy (backend healthy = `/api/health` trả `UP` theo healthcheck trong `backend/Dockerfile`; Caddy chỉ chạy sau khi backend + frontend healthy).
5. Thành công → ghi `infra/.deployed-tag` (dòng 1 tag hiện tại, dòng 2 tag trước), xoá `.deploying`. Dòng cuối: `deploy: OK, v1.0.0 is live (previous: ...)`.
- Lỗi hoặc không healthy trong 3 phút → **tự rollback** về tag đang chạy trước đó (dòng 1), exit khác 0, file trạng thái không đổi. Rollback cũng lỗi → `.deploying` còn lại; sửa xong chạy `bash infra/deploy.sh --rollback`. Lần deploy đầu tiên (chưa có tag trước) thì chỉ báo lỗi, xem `$C ps` / `$C logs backend`.
- Khi đổi image, Caddy và backend được tạo lại: khách có thể thấy lỗi **502 khoảng 1 phút**. Deploy lúc vắng khách.
- Chạy lại cùng tag là an toàn (idempotent). Script không bao giờ chạy `down`, không đụng volume, không in `.env`.
- Image cũ tích lại trên đĩa: thỉnh thoảng `docker image prune -a` (chỉ xoá image không có container nào dùng; giữ được image của tag đang chạy). Sau khi prune, `--rollback` sẽ tự pull lại image tag trước.
- `infra/.deployed-tag`, `.deploying`, `.deploy.lock` đều gitignored.

**4. Rollback** về tag trước trong `infra/.deployed-tag`:
```
git checkout <tag trước>        # dòng 2 của infra/.deployed-tag
bash infra/deploy.sh --rollback
```
- Rollback **chỉ đổi image**: không pull trước (`up` tự kéo image còn thiếu), không backup (để rollback được cả khi R2 lỗi), vẫn đợi health như trên. `git checkout` tag trước là để compose/Caddyfile khớp image cũ; script không tự làm việc này.
- Nếu còn `infra/.deploying` (deploy trước đó dở dang: SSH rớt, rollback tự động lỗi), `--rollback` quay về **dòng 1** (tag cuối cùng đã healthy) thay vì dòng 2.
- Chạy `--rollback` hai lần = quay lại tag ban đầu. Rollback về một tag cụ thể: `bash infra/deploy.sh v0.9.0` (có backup).
- **Lưu ý migration:** rollback không đổi schema. Flyway của bản cũ bỏ qua migration mới hơn nó, nên bản cũ chạy được nếu migration mới chỉ **thêm** (cột nullable, bảng mới). Migration đổi tên/xoá cột → bản cũ lỗi; khi đó khôi phục bản `pre-deploy/` vừa tạo (§6) rồi mới rollback. Vì vậy: migration nên viết kiểu chỉ thêm.

**5. Kiểm tra trước launch (tiêu chí T-045):** deploy 1 tag, deploy tag thứ 2, `--rollback` thành công; ghi kết quả vào đây. Kết quả: _chưa thử (chưa có VPS)_.

## 7. Giám sát
- **Uptime monitor (thủ công, Khai làm, trước launch):** monitor HTTP(s) tới `https://<domain>/api/health` từ bên ngoài VPS, cảnh báo qua email của Khai. Chưa chọn dịch vụ. Điều đã đọc ngày 2026-11-16 (devops đọc trang web, không phải từ trí nhớ; Khai xem lại trước khi đăng ký):
  | Dịch vụ | Gói free (theo trang đã đọc) | Dùng thương mại | Nguồn |
  |---|---|---|---|
  | UptimeRobot | 50 monitor, chu kỳ tối thiểu 5 phút; SMS/gọi không có. Bảng so sánh không hiện rõ email có trong gói Free hay không (dòng "Email, SMS, Voice call" mô tả là kênh của mọi thành viên) | Được: trang giá ghi "Good for personal and commercial projects"; điều khoản (cập nhật 2026-06-24) ghi "available for any use, including commercial and business use" | uptimerobot.com/pricing, uptimerobot.com/terms |
  | HetrixTools | 15 uptime monitor, kiểm mỗi 1 phút (từ 4/12 địa điểm), có email (không nhiều người nhận); điều kiện: đăng nhập ít nhất 90 ngày/lần | Trang giá không nêu hạn chế; điều khoản chưa đọc | hetrixtools.com/pricing/uptime-monitor |
  | Better Stack | 10 monitor + heartbeat, có email + Slack; trang không nêu chu kỳ của gói free | **Không phù hợp:** gói free ghi "Free for personal projects" | betterstack.com/pricing |
  Đề xuất: UptimeRobot (cho phép thương mại rõ ràng), chu kỳ 5 phút; xác nhận email cảnh báo có trong gói Free khi tạo monitor, nếu không thì dùng HetrixTools.
- **Thử cảnh báo** (tiêu chí T-045): trên VPS `$C stop backend` (`$C` của §3) → trong ≤ 10 phút phải nhận email "down" → `$C up -d backend` → nhận email "up". Ghi lại ngày thử ở đây. Kết quả: _chưa thử_.
- Dashboard admin hiện: email gửi lỗi. Backup gần nhất thành công lúc nào: xem trên trang healthchecks.io (ADR-0013), admin không có dữ liệu này.
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
$C run --rm -e ADMIN_INITIAL_PASSWORD backend --create-admin=<email>     # $C của VPS (§3)
unset ADMIN_INITIAL_PASSWORD
```
`run --rm` xoá container cùng file log json-file của nó khi thoát, nên secret TOTP không nằm lại trong log Docker. Nếu SSH bị rớt giữa chừng, `--rm` có thể không chạy: kiểm `docker ps -a` và `docker rm` container `bkstudio-backend-run-...` còn sót (log của nó chứa secret).
- Ứng dụng **không** mở web server ở chế độ này; in **một lần** ra stdout: secret TOTP (Base32), URI `otpauth://` và 10 mã khôi phục, rồi thoát (exit 0). Lỗi → exit khác 0. Email và mật khẩu không bao giờ được in.
- Từ chối nếu email đã tồn tại hoặc mật khẩu < 12 ký tự.
- Quét URI/secret bằng app authenticator, cất mã khôi phục ở nơi an toàn, rồi xoá `ADMIN_INITIAL_PASSWORD` khỏi môi trường/`.env`.
- **Bảo mật khi chạy ở prod:** dùng `docker compose run --rm` (không `-d`, không gửi vào log collector) để stdout chỉ hiện trên terminal của bạn; **không** gửi stdout của container này vào log driver (json-file/loki...) vì nó chứa secret TOTP và mã khôi phục. Truyền `ADMIN_INITIAL_PASSWORD` **chỉ qua biến môi trường** (không bao giờ là đối số dòng lệnh, vì lộ qua `ps`/history); sau khi dùng xong, không giữ nó trong `.env`/compose. Lưu ngay secret và mã khôi phục vào password manager.
