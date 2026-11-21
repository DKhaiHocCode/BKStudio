# Runbook T-043: dựng server, tên miền, email (kèm thử thật T-044/T-045)

> Trạng thái: **Bản nháp, chưa chạy lần nào** · Viết: 2026-11-21 · Nguồn: `docs/DEPLOYMENT.md`, ADR-0005, `docs/SECURITY.md`, `infra/*`, task T-043/T-044/T-045 trong `docs/tasks.py`.
> Domain: `bk3dprints.com` (đã mua). Email liên hệ shop: `bk3dprinls@gmail.com` (chữ "ls" là cố ý, đừng sửa).
> Quy ước: `[CHƯA KIỂM]` = chưa đối chiếu được với tài liệu chính thức, có URL để kiểm. `VPS$` = gõ trên VPS (bash). `PC>` = gõ trên máy Khai (PowerShell). `<...>` = thay bằng giá trị thật.
> Mỗi bước có **Kiểm tra**: chưa đạt thì dừng, không đi tiếp.

Thời gian ước tính: §0–§8 ≈ 4 giờ 30 (T-043). §9–§10 ≈ 45 phút (phần thử thật của T-045, T-044). Chưa tính thời gian chờ DNS/xác minh nhà cung cấp.

---

## 0. Điều kiện trước và ai làm gì (15 phút)

| Việc | Khai (chủ dự án) | Claude |
|---|---|---|
| Đăng ký tài khoản, nhập thẻ, bấm trong dashboard (VPS, Cloudflare, Resend, GitHub, UptimeRobot, healthchecks.io) | Làm | Không bao giờ |
| Gõ lệnh trên VPS | Làm | Chuẩn bị lệnh, đọc output Khai dán (đã che secret). **Ngoại lệ: output tạo admin ở §8.1 không bao giờ dán cho Claude, chỉ báo "OK"** |
| Giữ secret (password manager) | Làm | Không bao giờ nhìn thấy secret |
| Cập nhật `DEPLOYMENT.md`, `tasks.py`, handoff sau khi xong | Duyệt | Điều phối viên làm |

Cần có sẵn trước khi bắt đầu:
- [ ] Password manager (Bitwarden, 1Password, KeePassXC...) để cất mọi secret dưới đây.
- [ ] SSH key trên máy Khai. Chưa có: `PC> ssh-keygen -t ed25519 -C "bkstudio-vps"` (đặt passphrase). Khoá công khai: `PC> Get-Content $env:USERPROFILE\.ssh\id_ed25519.pub`.
- [ ] Tài khoản Cloudflare có zone `bk3dprints.com` (nameserver trỏ về Cloudflare). Kiểm: `PC> Resolve-DnsName bk3dprints.com -Type NS` → hai nameserver `*.ns.cloudflare.com`.
- [ ] Repo GitHub: PR mới nhất đã merge vào `main`, CI xanh.
- [ ] Điện thoại có 4G (để thử từ "máy bên ngoài" có IP khác máy tính).
- [ ] Bản `docs/TERMS_DRAFT.md` đã xử lý hết `[CẦN DUYỆT]` (cần cho §8; chưa xong thì checkout trả `409 TERMS_OUTDATED` và §7 không thử được).

> **⛔ STOP: cần Khai duyệt rõ ràng trước khi tiêu tiền**
> Các bước có biểu tượng ⛔ dưới đây tốn tiền hoặc gắn thẻ thanh toán: thuê VPS (§1), bật backup/snapshot trả phí của nhà cung cấp (§1), gắn phương thức thanh toán cho R2 (§4). Claude không làm, không thúc. Khai ghi lại: nhà cung cấp, gói, tổng giá/tháng, ngày duyệt. Tổng phải nằm trong ngân sách ADR-0005 (≤ $15/tháng) hoặc Khai duyệt nâng ngân sách.

---

## 1. Chọn VPS (20 phút) ⛔

Tiêu chí bắt buộc (từ ADR-0005 và DEPLOYMENT §6b):

| Tiêu chí | Yêu cầu | Lý do |
|---|---|---|
| Kiến trúc CPU | **x86_64 (amd64)**, không ARM | CI chỉ build image `linux/amd64` (`.github/workflows/deploy.yml`). VPS ARM báo `exec format error` |
| RAM | **≥ 4 GB** | Compose đặt tổng `mem_limit` 3072 MB, còn ~1 GB cho OS |
| Đĩa | ≥ 40 GB SSD (gợi ý) | Image cũ tích lại; Postgres + log |
| Region | **Singapore** | Khách SG/AU |
| IPv4 public | Có | Chỉ dùng IPv4 (không bản ghi AAAA, DEPLOYMENT §3) |
| OS | **Ubuntu 24.04 LTS** | Docker hỗ trợ chính thức 22.04/24.04/26.04 (đã kiểm docs.docker.com 2026-11-21); runbook viết cho 24.04 |
| Firewall của nhà cung cấp | Có thì dùng (thêm một lớp) | Docker bỏ qua ufw với cổng publish (xem §2.5) |

Thứ tự chọn (ADR-0005): Hetzner Singapore 4 GB nếu tổng ≤ $15/tháng → nếu không, DigitalOcean / Vultr Singapore 4 GB → nếu vẫn vượt, Hetzner EU hoặc Khai duyệt nâng ngân sách. Lưu ý Hetzner Singapore chỉ kèm lưu lượng ra ít (ADR-0005 ghi 0,5 TB) `[CHƯA KIỂM]` trên trang giá hiện tại.

Khai điền từ **trang giá trực tiếp** trong console (Claude không ghi giá):

| Nhà cung cấp | Gói | x86_64? | RAM | Server/tháng | IPv4/tháng | Backup/snapshot/tháng | Lưu lượng gồm | **Tổng/tháng** |
|---|---|---|---|---|---|---|---|---|
| Hetzner SG | | | | | | | | |
| DigitalOcean SG | | | | | | | | |
| Vultr SG | | | | | | | | |

Khi tạo server: chọn Ubuntu 24.04, region Singapore, **dán SSH public key** ở bước tạo (để không bao giờ cần mật khẩu root), đặt tên `bkstudio-prod`. Backup của nhà cung cấp: bật nếu nằm trong ngân sách (DEPLOYMENT §4, dòng "Toàn bộ VPS").

**Kiểm tra:** console hiện server đang chạy, có IPv4. Ghi `VPS_IP` vào password manager (không phải secret, nhưng cần dùng nhiều lần). Ghi giá thật + ngày duyệt cho điều phối viên.

---

## 2. Đăng nhập lần đầu và hardening (40 phút)

Nguyên tắc chống tự khoá mình: **giữ nguyên một phiên SSH đang mở** trong lúc đổi SSH/firewall, thử đăng nhập bằng **cửa sổ thứ hai**. Mất kết nối hẳn → dùng web console (VNC) của nhà cung cấp (§11).

### 2.1 Đăng nhập root bằng key, cập nhật hệ thống
```
PC>  ssh root@<VPS_IP>
VPS$ apt update && apt -y full-upgrade
VPS$ uname -m          # phải là x86_64
VPS$ lsb_release -ds   # Ubuntu 24.04.x LTS
VPS$ free -h           # Mem total ~3.8Gi trở lên
```
**Kiểm tra:** `x86_64`, Ubuntu 24.04, RAM ≥ ~3,8 GiB. Sai kiến trúc → dừng, đổi server.
Nếu `full-upgrade` báo cần reboot: `reboot`, đăng nhập lại.

### 2.2 User thường `deploy` có sudo, dùng chung SSH key
```
VPS$ adduser --gecos "" deploy                  # đặt mật khẩu mạnh (dùng cho sudo), cất vào password manager
VPS$ usermod -aG sudo deploy
VPS$ install -d -m 700 -o deploy -g deploy /home/deploy/.ssh
VPS$ install -m 600 -o deploy -g deploy /root/.ssh/authorized_keys /home/deploy/.ssh/authorized_keys
VPS$ cat /home/deploy/.ssh/authorized_keys   # chỉ khoá của Khai; dòng không được bắt đầu bằng command="..." hay no-port-forwarding,... (một số image cloud chèn tiền tố chặn đăng nhập) — có thì xoá tiền tố đó
```
**Kiểm tra (cửa sổ thứ hai):** `PC> ssh deploy@<VPS_IP>` vào được không hỏi mật khẩu đăng nhập; `VPS$ sudo -v` nhận mật khẩu của `deploy`.

### 2.3 Chỉ SSH key, tắt root login và mật khẩu
sshd dùng **giá trị đầu tiên** đọc được cho mỗi từ khoá, file trong `sshd_config.d/` đọc theo thứ tự chữ cái (đã kiểm man.openbsd.org/sshd_config). Một số image cloud có `50-cloud-init.conf` bật `PasswordAuthentication yes`, nên đặt tên file bắt đầu bằng `00-`:
```
VPS$ sudo tee /etc/ssh/sshd_config.d/00-bkstudio.conf >/dev/null <<'EOF'
PermitRootLogin no
PasswordAuthentication no
KbdInteractiveAuthentication no
PubkeyAuthentication yes
EOF
VPS$ sudo sshd -t && sudo systemctl restart ssh
```
**Kiểm tra:**
```
VPS$ sudo sshd -T | grep -Ei '^(permitrootlogin|passwordauthentication|kbdinteractiveauthentication) '
# permitrootlogin no / passwordauthentication no / kbdinteractiveauthentication no
PC>  ssh -o PubkeyAuthentication=no -o PreferredAuthentications=password deploy@<VPS_IP>   # phải bị từ chối: Permission denied (publickey)
PC>  ssh root@<VPS_IP>                                                                     # phải bị từ chối
PC>  ssh deploy@<VPS_IP>                                                                   # vẫn vào được
```
Ubuntu 24.04 dùng socket activation cho ssh; nếu `systemctl restart ssh` không áp dụng cấu hình, chạy thêm `sudo systemctl restart ssh.socket` `[CHƯA KIỂM]` (https://discourse.ubuntu.com/t/sshd-now-uses-socket-based-activation-ubuntu-22-10-and-later/30189). `sshd -T` chỉ kiểm cú pháp và giá trị cấu hình đọc được; **bằng chứng thật là ba lần đăng nhập ở cửa sổ thứ hai** (hai lần bị từ chối, một lần vào được).
Từ đây chỉ dùng `deploy`; đóng phiên root.

### 2.4 Firewall ufw: chỉ 22, 80, 443
```
VPS$ sudo ufw default deny incoming
VPS$ sudo ufw default allow outgoing
VPS$ sudo ufw allow 22/tcp
VPS$ sudo ufw allow 80/tcp
VPS$ sudo ufw allow 443/tcp
VPS$ sudo ufw allow 443/udp        # HTTP/3, compose publish 443/udp
VPS$ sudo ufw enable
```
(Lệnh allow/enable/status theo tài liệu Ubuntu Server, đã kiểm 2026-11-21; tài liệu đó không ghi chính sách mặc định, nên đặt tường minh như trên.)
**Kiểm tra:** `VPS$ sudo ufw status verbose` → `Status: active`, `Default: deny (incoming), allow (outgoing)`, chỉ 22/tcp, 80/tcp, 443/tcp, 443/udp. Cửa sổ thứ hai `ssh deploy@<VPS_IP>` vẫn vào.

### 2.5 Firewall của nhà cung cấp (nếu có, miễn phí)
Docker **bỏ qua ufw** cho cổng đã publish (đã kiểm docs.docker.com/engine/install/ubuntu: "bypass your firewall rules"). Compose chỉ publish cổng của Caddy, nên hiện tại không sao; nhưng một lỗi publish sau này sẽ lộ thẳng ra Internet. Tạo firewall ở console nhà cung cấp: inbound TCP 22, 80, 443 và UDP 443, chặn còn lại, gắn vào server. Có miễn phí hay không tuỳ nhà cung cấp `[CHƯA KIỂM]`.
**Kiểm tra:** console hiện firewall gắn với server; `ssh deploy@<VPS_IP>` vẫn vào.

### 2.6 fail2ban
```
VPS$ sudo apt -y install fail2ban
VPS$ sudo systemctl enable --now fail2ban
```
Gói Ubuntu 24.04 được cho là bật sẵn jail `sshd` qua `/etc/fail2ban/jail.d/defaults-debian.conf` (backend systemd) `[CHƯA KIỂM]`, nguồn chỉ là hướng dẫn bên thứ ba; kiểm trên máy: `cat /etc/fail2ban/jail.d/defaults-debian.conf`, tài liệu gốc https://github.com/fail2ban/fail2ban. Nếu file không có `[sshd] enabled = true`:
```
VPS$ printf '[sshd]\nenabled = true\nbackend = systemd\n' | sudo tee /etc/fail2ban/jail.d/sshd.local && sudo systemctl restart fail2ban
```
**Kiểm tra:** `VPS$ sudo fail2ban-client status sshd` → in `Status for the jail: sshd`, không báo lỗi.

### 2.7 Cập nhật bảo mật tự động
```
VPS$ sudo apt -y install unattended-upgrades
VPS$ sudo dpkg-reconfigure -plow unattended-upgrades    # chọn Yes
```
**Kiểm tra** (theo tài liệu Ubuntu Server, đã kiểm 2026-11-21):
```
VPS$ cat /etc/apt/apt.conf.d/20auto-upgrades
# APT::Periodic::Update-Package-Lists "1";
# APT::Periodic::Unattended-Upgrade "1";
VPS$ sudo unattended-upgrade -v --dry-run | tail -n 5   # không có lỗi
```
Không bật tự reboot: kernel mới cần Khai `sudo reboot` lúc vắng khách (thấy `/var/run/reboot-required`). Container có `restart: unless-stopped` nên tự chạy lại sau reboot.

### 2.8 Múi giờ
Giữ máy ở **UTC** (log thống nhất với `Instant` trong app). Lịch backup 03:00 Asia/Singapore do container `backup` tự tính, không phụ thuộc múi giờ máy (DEPLOYMENT §4).
```
VPS$ sudo timedatectl set-timezone Etc/UTC
```
**Kiểm tra:** `VPS$ timedatectl` → `Time zone: Etc/UTC`, `System clock synchronized: yes`.

---

## 3. Docker, clone repo, tạo `.env` (40 phút)

### 3.1 Cài Docker Engine + Compose plugin (apt repo chính thức)
Lệnh lấy từ https://docs.docker.com/engine/install/ubuntu/ (đã kiểm 2026-11-21):
```
VPS$ sudo apt update
VPS$ sudo apt -y install ca-certificates curl
VPS$ sudo install -m 0755 -d /etc/apt/keyrings
VPS$ sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
VPS$ sudo chmod a+r /etc/apt/keyrings/docker.asc
VPS$ sudo tee /etc/apt/sources.list.d/docker.sources <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: $(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}")
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
EOF
VPS$ sudo apt update
VPS$ sudo apt -y install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
VPS$ sudo usermod -aG docker deploy     # nhóm docker = quyền ngang root; chỉ user deploy
VPS$ exit                                # đăng nhập lại để nhận nhóm docker
```
**Kiểm tra:**
```
VPS$ docker run --rm hello-world        # "Hello from Docker!"
VPS$ docker compose version             # cần bản hỗ trợ `!reset` (đã kiểm với v5.6.1, DEPLOYMENT §6b)
VPS$ command -v flock git               # cả hai có đường dẫn (deploy.sh cần flock)
VPS$ docker network inspect $(docker network ls -q) --format '{{.Name}} {{range .IPAM.Config}}{{.Subnet}} {{end}}'; ip -4 addr
# không mạng Docker / interface nào nằm trong 172.30.0.0/24
```
Nếu trùng, Compose dừng an toàn với lỗi `Pool overlaps with other one on this address space` (không tạo gì). Nếu `172.30.0.0/24` đã bị dùng: dừng, báo Claude (phải đổi subnet **và** `TRUSTED_PROXIES` trong `infra/docker-compose.yml` cùng lúc, là một PR riêng).

### 3.2 Clone repo (repo public, không cần token)
```
VPS$ git clone https://github.com/DKhaiHocCode/BKStudio.git ~/bkstudio
VPS$ cd ~/bkstudio
```
**Kiểm tra:** `ls infra/` có `docker-compose.yml`, `docker-compose.ghcr.yml`, `Caddyfile`, `deploy.sh`.
Checkout đúng tag làm ở §6 (sau khi có tag).

### 3.3 Tạo `.env`
Sinh secret ngay trên VPS (dạng hex/base64, không có `$`; DEPLOYMENT §3). Giá trị lấy từ dashboard (R2, Resend, Turnstile) điền sau ở §4–§5; dùng `nano .env` để dán, **không** dán vào chat/issue.
```
VPS$ cd ~/bkstudio
VPS$ (umask 077 && cat > .env <<EOF
# --- Database ---
POSTGRES_DB=bkstudio
POSTGRES_USER=bkstudio
POSTGRES_PASSWORD=$(openssl rand -hex 24)
# --- Site ---
SITE_ADDRESS=bk3dprints.com
APP_BASE_URL=https://bk3dprints.com
NEXT_PUBLIC_SITE_URL=https://bk3dprints.com
CDN_ORIGIN=https://cdn.bk3dprints.com
# --- R2 images (section 4) ---
R2_ENDPOINT=https://<ACCOUNT_ID>.r2.cloudflarestorage.com
R2_BUCKET=bkstudio-images
R2_ACCESS_KEY_ID=<from R2 token>
R2_SECRET_ACCESS_KEY=<from R2 token>
R2_PUBLIC_BASE_URL=https://cdn.bk3dprints.com
# --- Email (section 5) ---
RESEND_API_KEY=<from Resend>
MAIL_FROM=BKStudio <orders@bk3dprints.com>
# --- Admin TOTP ---
TOTP_ENCRYPTION_KEY=$(openssl rand -base64 32)
# --- Turnstile (this section, below) ---
NEXT_PUBLIC_TURNSTILE_SITE_KEY=<site key>
TURNSTILE_SECRET_KEY=<secret key>
# --- Backup (section 4) ---
R2_BACKUP_BUCKET=bkstudio-backup
R2_BACKUP_ACCESS_KEY_ID=<from backup token>
R2_BACKUP_SECRET_ACCESS_KEY=<from backup token>
BACKUP_ENCRYPTION_PASSPHRASE=$(openssl rand -hex 32)
# Left empty until section 10 (healthchecks.io ping URL)
BACKUP_HEALTHCHECK_URL=
EOF
)
VPS$ chmod 600 .env && ls -l .env      # -rw------- deploy deploy
```
Ngay sau đó: mở `.env` (`nano .env`), copy **toàn bộ** vào password manager (mục "BKStudio prod .env"). `BACKUP_ENCRYPTION_PASSPHRASE` mất = mọi backup vô dụng. Không chia sẻ màn hình lúc này.

Bảng biến (đọc từ `infra/docker-compose.yml`, `.env.example`, DEPLOYMENT §2):

| Biến | Bắt buộc? | Secret? | Nguồn giá trị |
|---|---|---|---|
| `POSTGRES_DB`, `POSTGRES_USER` | Có | Không | Cố định như trên |
| `POSTGRES_PASSWORD` | Có | **Sinh** | `openssl rand -hex 24` |
| `SITE_ADDRESS` | Có | Không | `bk3dprints.com` (Caddy tự lấy cert) |
| `APP_BASE_URL` | Có | Không | `https://bk3dprints.com` (prod bắt buộc https) |
| `NEXT_PUBLIC_SITE_URL` | Có (compose kiểm) | Không | Như trên; giá trị thật nhúng lúc build từ **repository variable** (§6) |
| `CDN_ORIGIN` | Có | Không | `https://cdn.bk3dprints.com` (origin, không path); sai = CSP chặn ảnh |
| `R2_ENDPOINT` | Có | Không | Dashboard R2 (§4.3), phải `https://` |
| `R2_BUCKET` | Có | Không | Tên bucket ảnh (§4.3) |
| `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY` | Có | **Dashboard** | Token R2 Object Read & Write, chỉ bucket ảnh |
| `R2_PUBLIC_BASE_URL` | Có | Không | `https://cdn.bk3dprints.com`, không `/` cuối |
| `R2_REGION` | Không (mặc định `auto`) | Không | Bỏ trống |
| `RESEND_API_KEY` | Có | **Dashboard** | Resend → API Keys (§5) |
| `MAIL_FROM` | Có | Không | Địa chỉ trên domain đã xác minh ở Resend |
| `TOTP_ENCRYPTION_KEY` | Có | **Sinh** | base64 của 32 byte. Đổi sau khi đã tạo admin = TOTP hỏng |
| `NEXT_PUBLIC_TURNSTILE_SITE_KEY` | Có (compose kiểm) | Không (công khai) | Turnstile; giá trị thật nhúng lúc build từ repository variable |
| `TURNSTILE_SECRET_KEY` | Có | **Dashboard** | Turnstile; prod từ chối key thử `1x…AA`/`2x…AA`/`3x…AA` |
| `R2_BACKUP_BUCKET` | Có | Không | Bucket backup private (§4.4) |
| `R2_BACKUP_ACCESS_KEY_ID`, `R2_BACKUP_SECRET_ACCESS_KEY` | Có | **Dashboard** | Token riêng, chỉ bucket backup |
| `BACKUP_ENCRYPTION_PASSPHRASE` | Có | **Sinh** | `openssl rand -hex 32` |
| `BACKUP_HEALTHCHECK_URL` | Không, nhưng nên có | **Coi như secret** | healthchecks.io (§10) |
| `APP_CHECKOUT_*`, `APP_NOTIFICATION_OUTBOX_DAILY_CAP` | Không | Không | Để mặc định (5/h, 3/h, 80/ngày) |
| `HTTP_PORT`, `HTTPS_PORT` | Không | Không | Để mặc định 80/443 |

**Không bao giờ** đặt trong `.env` của VPS: `IMAGE_TAG` (deploy.sh tự đặt), `TURNSTILE_ALLOW_TEST_SECRET`, `LOCAL_TEST_ONLY`, `R2_BACKUP_READ_*`, `ADMIN_INITIAL_PASSWORD`, `FORWARD_HEADERS_STRATEGY`/`TRUSTED_PROXIES` (compose đã đặt cứng `native` + `172.30.0.10/32`).

### 3.4 Cloudflare Turnstile (key thật)
Theo DEPLOYMENT §3 "Cloudflare Turnstile", bổ sung từ tài liệu Cloudflare (đã kiểm 2026-11-21: developers.cloudflare.com/turnstile/get-started/widget-management/dashboard/):
1. Cloudflare dashboard → **Turnstile** → **Add widget**.
2. Widget name `bkstudio-checkout`; **Region: Rest of World** (không đổi được sau khi tạo); Hostname: `bk3dprints.com`; Mode: **Managed**; Pre-clearance: tắt.
3. **Create** → copy Site key vào `.env` (`NEXT_PUBLIC_TURNSTILE_SITE_KEY`) **và** vào GitHub repository variable (§6.1). Copy Secret key vào `.env` (`TURNSTILE_SECRET_KEY`) và password manager. Secret key không bao giờ vào repo hay GitHub.
Turnstile không cần site đi qua proxy Cloudflare (đã kiểm trang Get started).
**Kiểm tra:** widget hiện trong danh sách Turnstile với hostname `bk3dprints.com`; hai lệnh sau không in giá trị:
```
VPS$ grep -c '^TURNSTILE_SECRET_KEY=0x' .env   # 1
VPS$ grep -c '^TURNSTILE_SECRET_KEY=.*<' .env  # 0 (không còn placeholder)
```
Secret thật bắt đầu bằng `0x` `[CHƯA KIỂM]`, xác nhận trên dashboard Turnstile; nếu không phải `0x` thì chỉ cần lệnh thứ hai in `0`.

---

## 4. DNS Cloudflare, R2 ảnh, R2 backup (40 phút)

### 4.1 Quyết định proxy: **DNS only (mây xám)** cho `bk3dprints.com`
| | DNS only (chọn) | Proxied (mây cam) |
|---|---|---|
| Caddy thấy IP nào | IP thật của khách | IP của Cloudflare edge |
| Rate limit / khoá đăng nhập theo IP | Đúng từng khách (đạt tiêu chí T-043) | **Gộp khách** theo IP edge, trừ khi sửa Caddyfile: `trusted_proxies` dải IP Cloudflare + `client_ip_headers CF-Connecting-IP` (comment trong `infra/Caddyfile` đã ghi; chưa làm) |
| `TRUSTED_PROXIES` | Giữ `172.30.0.10/32` (chỉ Caddy) | Vẫn giữ (backend chỉ tin Caddy), nhưng Caddy phải tin Cloudflare trước |
| Cert | Caddy tự lấy Let's Encrypt | Cần SSL mode Full (strict) |
| Lộ IP VPS | Có | Không |

Cloudflare gửi IP khách trong `CF-Connecting-IP` và nối/đặt `X-Forwarded-For` (đã kiểm developers.cloudflare.com/fundamentals/reference/http-headers/). Bật proxy sau launch = việc làm sau, PR riêng sửa Caddyfile: `trusted_proxies` + `client_ip_headers CF-Connecting-IP`, **và** `header_up X-Forwarded-For {client_ip}` trong mọi khối `reverse_proxy` (để backend nhận đúng một IP khách, không phải chuỗi XFF do client gửi), rồi thử lại §7.6.

### 4.2 Bản ghi DNS cho site
Cloudflare → `bk3dprints.com` → DNS → Records → Add record:
- `A` · Name `@` · IPv4 `<VPS_IP>` · Proxy status **DNS only** · TTL Auto.
- **Không** tạo `AAAA` (DEPLOYMENT §3: IPv6 đi qua docker-proxy, mọi khách IPv6 chung một IP).
- Không tạo `www` lúc launch (Caddy chỉ phục vụ `SITE_ADDRESS`, Turnstile chỉ có hostname apex). Muốn có `www` → hỏi Claude, cần sửa cấu hình.
**Kiểm tra:**
```
PC> Resolve-DnsName bk3dprints.com -Type A      # = <VPS_IP>, không phải IP Cloudflare
PC> Resolve-DnsName bk3dprints.com -Type AAAA   # không có bản ghi AAAA nào trỏ về VPS
```
Chưa trỏ đúng thì **chưa** chạy §6: Caddy sẽ xin cert thất bại, Let's Encrypt có giới hạn số lần thất bại `[CHƯA KIỂM]` (https://letsencrypt.org/docs/rate-limits/).

### 4.3 R2: bucket ảnh + domain `cdn.bk3dprints.com` ⛔
R2 có thể yêu cầu gắn phương thức thanh toán dù dùng trong gói miễn phí `[CHƯA KIỂM]` (https://developers.cloudflare.com/r2/pricing/). Đó là bước ⛔.
1. R2 Object Storage → **Create bucket** → `bkstudio-images`, location Automatic (hoặc gợi ý APAC).
2. Bucket → **Settings** → **Custom Domains** → **Add** → `cdn.bk3dprints.com` → xem bản ghi DNS sẽ thêm → **Connect Domain** → đợi trạng thái **Active** (không Active thì menu "..." → Retry connection). Zone phải nằm cùng tài khoản với bucket (đã kiểm developers.cloudflare.com/r2/buckets/public-buckets/). Bản ghi `cdn` do Cloudflare tạo có proxy hay không: tài liệu không nói `[CHƯA KIỂM]`; không ảnh hưởng IP khách vì `cdn` không trỏ về VPS.
3. **Không** dùng `r2.dev` cho production (tài liệu: giới hạn tốc độ, chỉ để dev).
4. R2 → **Manage API tokens** → Create token: quyền **Object Read & Write**, chỉ bucket `bkstudio-images` → copy Access Key ID + Secret vào `.env` (`R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`). Endpoint `https://<ACCOUNT_ID>.r2.cloudflarestorage.com` hiện cùng trang → `R2_ENDPOINT`.
5. Không cần CORS: ảnh upload qua backend, trình duyệt chỉ GET ảnh.
**Kiểm tra:** Custom domain `Active`; upload tay một file `test.txt` trên dashboard rồi `PC> curl.exe -sI https://cdn.bk3dprints.com/test.txt` → `HTTP/1.1 200` (hoặc HTTP/2 200). Xoá `test.txt` sau đó.

### 4.4 R2: bucket backup private + lock + lifecycle
Đúng DEPLOYMENT §4 "Cài đặt trên Cloudflare" (Khai đã duyệt số ngày 2026-11-15):
1. Create bucket `bkstudio-backup`. **Không** bật public, **không** gắn domain.
2. Token **Object Read & Write chỉ bucket `bkstudio-backup`** → `R2_BACKUP_ACCESS_KEY_ID` / `R2_BACKUP_SECRET_ACCESS_KEY`.
3. Settings → **Bucket lock rules** → Add rule theo prefix: `daily/` 15 ngày, `weekly/` 60 ngày, `pre-deploy/` 60 ngày.
4. Settings → **Object Lifecycle Rules** → Add rule (đã kiểm đường dẫn dashboard 2026-11-21: hỗ trợ lọc theo prefix, xoá sau N ngày; object thường bị xoá trong vòng 24 giờ sau hạn): `daily/` xoá sau 15 ngày, `weekly/` 60 ngày, `pre-deploy/` 60 ngày.
5. Token thứ ba **Object Read only, chỉ bucket backup** → cất trong password manager làm `R2_BACKUP_READ_*`. **Không** đặt vào `.env` của VPS.
**Kiểm tra:** dashboard bucket backup: Public access = disabled, 3 lock rule, 3 lifecycle rule, không custom domain. Thử thật lock ở §10.

---

## 5. Resend: xác minh domain, SPF/DKIM/DMARC (20 phút + chờ DNS)

Giá trị bản ghi lấy **từ dashboard Resend**, không lấy từ tài liệu này.
1. resend.com → Domains → **Add domain** → `bk3dprints.com` (region gửi: chọn gần Singapore nếu có lựa chọn `[CHƯA KIỂM]`, https://resend.com/docs/dashboard/domains/introduction).
2. Thêm bản ghi vào Cloudflare. Hai cách (đã kiểm https://resend.com/docs/knowledge-base/cloudflare 2026-11-21):
   - Tự động: nút **Sign in to Cloudflare** (Domain Connect) trên trang domain của Resend, cho phép Resend ghi DNS.
   - Thủ công: Resend liệt kê ba bản ghi, dán **đúng giá trị Resend hiển thị**, phần Name bỏ đuôi `bk3dprints.com`:
     | Type | Name | Ghi chú |
     |---|---|---|
     | MX | `send` | Priority 10 (bận thì 11/12) |
     | TXT (SPF) | `send` | `v=spf1 include:...` do Resend đưa |
     | TXT (DKIM) | `resend._domainkey` | Proxy status **DNS only** |
   - Không bật "Receiving" (shop nhận thư ở Gmail `bk3dprinls@gmail.com`).
3. DMARC (Resend khuyên bắt đầu `p=none`, đã kiểm https://resend.com/docs/dashboard/domains/dmarc): Cloudflare → Add record `TXT` · Name `_dmarc` · Content `v=DMARC1; p=none;`. Thêm `rua=mailto:<địa chỉ>` nếu muốn nhận báo cáo; gửi báo cáo về địa chỉ khác domain (vd Gmail) có thể bị bên gửi bỏ qua vì thiếu bản ghi uỷ quyền `[CHƯA KIỂM]` (RFC 7489 §7.1, https://datatracker.ietf.org/doc/html/rfc7489#section-7.1). Sau 2–4 tuần không thấy lỗi → `p=quarantine` (Khai quyết).
4. Resend → **Verify**. → API Keys → Create, quyền **Sending access**, chỉ domain `bk3dprints.com` → `RESEND_API_KEY` trong `.env`.
**Kiểm tra:** Resend hiện domain **Verified** (SPF + DKIM xanh); `PC> Resolve-DnsName _dmarc.bk3dprints.com -Type TXT` in `v=DMARC1; p=none...`. Thử thật ở §7.4.

---

## 6. Deploy lần đầu (30 phút)

### 6.1 Cài đặt GitHub một lần (Khai, DEPLOYMENT §6b mục 2)
- Settings → Secrets and variables → Actions → **Variables**: `NEXT_PUBLIC_SITE_URL` = `https://bk3dprints.com`; `NEXT_PUBLIC_TURNSTILE_SITE_KEY` = site key thật (§3.4).
- Branch protection `main` bắt buộc CI xanh (T-045 ghi: hiện **chưa bật**).
- Tag ruleset `v*`: Restrict creations / updates / deletions, bypass chỉ Admin.
**Kiểm tra:** `PC> gh variable list` hiện đủ 2 biến; `PC> gh api repos/DKhaiHocCode/BKStudio/branches/main/protection` không còn 404.

### 6.2 Tạo tag → workflow "Release images"
Tag lần đầu đề xuất `v0.9.0` (bản chạy thử trước launch; tag bất biến, không xoá/đẩy lại):
```
PC> git checkout main; git pull
PC> git tag v0.9.0; git push origin v0.9.0
PC> gh run watch                # chọn run "Release images"
```
**Kiểm tra:** run xanh; Job summary có digest của `bkstudio-backend`, `bkstudio-frontend`, `bkstudio-backup` (ghi lại để so ở 6.4).

### 6.3 GHCR Public (một lần)
GitHub → Packages → từng package `bkstudio-backend`, `bkstudio-frontend`, `bkstudio-backup` → Package settings → Change visibility → **Public**.
**Kiểm tra:** `VPS$ docker pull ghcr.io/dkhaihoccode/bkstudio-backend:v0.9.0` chạy được khi chưa `docker login`.

### 6.4 Deploy trên VPS
```
VPS$ cd ~/bkstudio
VPS$ git fetch --tags && git checkout v0.9.0
VPS$ T=v0.9.0
VPS$ C="env IMAGE_TAG=$T docker compose --env-file .env -f infra/docker-compose.yml -f infra/docker-compose.ghcr.yml"
VPS$ $C config -q && echo CONFIG_OK     # luôn có -q: bỏ -q là in toàn bộ secret
VPS$ bash infra/deploy.sh v0.9.0
```
Trước `deploy.sh`, `VPS$ grep -n '=<' .env` phải **không in gì** (không còn placeholder `<...>`; giá trị thật không bắt đầu bằng `<`, nên dòng nào in ra là placeholder chưa thay, không phải secret).
Lần đầu chưa có R2 backup nào: `deploy.sh` chạy backup `pre-deploy` trước, cần bucket backup + token đúng (§4.4). Lỗi backup = deploy dừng trước khi đổi gì.
**Kiểm tra:**
- Dòng cuối: `deploy: OK, v0.9.0 is live (previous: none)`; 3 digest in ra trùng Job summary.
- `VPS$ $C ps` → mọi service `healthy` (backup `running`).
- `VPS$ curl -fsS https://bk3dprints.com/api/health` → `{"groups":[...],"status":"UP"}`.
- `VPS$ $C logs caddy | grep -i 'certificate obtained'` có dòng cho `bk3dprints.com` `[CHƯA KIỂM]` câu chữ chính xác trong log Caddy (https://caddyserver.com/docs/automatic-https).
- R2 bucket backup có object mới trong `pre-deploy/`.

Định nghĩa `$C` ở trên dùng cho mọi bước sau. Phiên SSH mới: `T="$(head -n1 infra/.deployed-tag)"` rồi định nghĩa lại `C` y như trên.

---

## 7. Kiểm tra nghiệm thu T-043 (45 phút)

Điều kiện: §8 đã xong (có admin, đã đăng điều khoản, có **1 sản phẩm thử còn hàng, có ảnh**). Làm §8 trước rồi quay lại đây.

| # | Tiêu chí T-043 (tasks.py) | Mục |
|---|---|---|
| 1 | HTTPS hoạt động | 7.1 |
| 2 | securityheaders.com ≥ A | 7.2 |
| 3 | Port 5432 không truy cập được từ ngoài | 7.3 |
| 4 | mail-tester ≥ 9/10 | 7.4 |
| 5 | SPF/DKIM/DMARC pass | 7.5 |
| 6 | Rate limit thấy IP khách thật (`FORWARD_HEADERS_STRATEGY=native` + `TRUSTED_PROXIES`) | 7.6, 7.9 |
| CHẶN CỨNG | Caddy body 11MB toàn cục, 52MB chỉ ở progress; `no-store` cho `/api/orders/**` | 7.7, 7.8 |
| CHẶN CỨNG | Caddy không log URI đơn / header; `APP_BASE_URL` https | 7.10 |
| Thêm | CSP trong trình duyệt thật + Turnstile ở `/checkout` (có hàng trong giỏ) và `/orders/lookup` | 7.11 |

### 7.1 HTTPS
```
PC> curl.exe -sSI http://bk3dprints.com/        # 308 Permanent Redirect, Location: https://bk3dprints.com/
PC> curl.exe -sSI https://bk3dprints.com/       # 200, có Strict-Transport-Security: max-age=31536000; includeSubDomains
```
Trình duyệt: ổ khoá hợp lệ, cert do Let's Encrypt (hoặc ZeroSSL) cấp cho `bk3dprints.com`.
**Đạt khi:** redirect sang https, cert hợp lệ, có HSTS. Ghi ngày + output vào DEPLOYMENT.

### 7.2 securityheaders.com ≥ A
Mở https://securityheaders.com/?q=https%3A%2F%2Fbk3dprints.com&followRedirects=on. Caddyfile có CSP, HSTS, X-Content-Type-Options, Referrer-Policy, Permissions-Policy, nhưng **không có `X-Frame-Options`** (đã thay bằng CSP `frame-ancestors 'none'`). Trang có thể trừ điểm vì thiếu header đó và cảnh báo `'unsafe-inline'` `[CHƯA KIỂM]` cách chấm điểm (https://securityheaders.com/ → FAQ). Dưới A → dừng, báo Claude (thêm `X-Frame-Options "DENY"` là một PR nhỏ vào `infra/Caddyfile`).
**Đạt khi:** điểm A hoặc A+. Chụp màn hình.

### 7.3 Port 5432 (và cổng nội bộ khác) đóng từ ngoài
Từ máy Khai (bên ngoài VPS):
```
PC> foreach ($p in 22,80,443,3000,5432,8080) { $r = Test-NetConnection bk3dprints.com -Port $p -WarningAction SilentlyContinue; "$p $($r.TcpTestSucceeded)" }
# 22 True, 80 True, 443 True, 3000 False, 5432 False, 8080 False
```
Có nmap thì thêm: `nmap -Pn -p 22,80,443,3000,5432,8080 bk3dprints.com` → chỉ 22/80/443 `open`.
Trên VPS (vì Docker bỏ qua ufw, đây là bằng chứng chính):
```
VPS$ sudo ss -tlnp | grep -v '127.0.0'    # chỉ :22, 0.0.0.0:80, 0.0.0.0:443 (docker-proxy)
VPS$ sudo ss -ulnp | grep -v '127.0.0'    # chỉ 0.0.0.0:443 (HTTP/3)
VPS$ docker ps --format '{{.Names}} {{.Ports}}'   # chỉ bkstudio-caddy-1 có cổng, postgres/backend/frontend trống
```
**Đạt khi:** 5432 `False` từ ngoài, không container nào ngoài Caddy publish cổng.

### 7.4 mail-tester ≥ 9/10
1. Mở https://www.mail-tester.com/ → copy địa chỉ test (dạng `test-xxxx@srv1.mail-tester.com`). Số lần thử miễn phí mỗi ngày có giới hạn `[CHƯA KIỂM]` (trang FAQ của mail-tester).
2. Đặt **một đơn thử** trên shop với email = địa chỉ đó (sản phẩm thử, địa chỉ giả hợp lệ, Singapore). Backend gửi email `ORDER_RECEIVED` qua outbox.
3. `VPS$ $C logs --since 10m backend | grep -i outbox` thấy gửi thành công (không có lỗi Resend). Resend → Emails thấy email `Delivered`.
4. Quay lại mail-tester → **Then check your score**.
5. Sau đó: admin huỷ đơn thử (đơn hết hàng/kho trả lại).
**Đạt khi:** ≥ 9/10. Dưới 9: đọc mục bị trừ (thường là DKIM chưa verified, thiếu DMARC, link lạ), sửa, thử lại.

### 7.5 SPF/DKIM/DMARC pass
Cùng email ở 7.4: mail-tester mục "You're properly authenticated" phải xanh cả SPF, DKIM, DMARC. Kiểm thêm bằng Gmail: đặt thêm một đơn thử bằng `bk3dprinls@gmail.com` (đếm vào hạn mức 3 đơn/email/giờ) → mở email → ⋮ → **Show original** → `SPF: PASS`, `DKIM: PASS`, `DMARC: PASS`.
**Đạt khi:** cả ba PASS ở cả hai nơi.

### 7.6 Rate limit thấy IP khách thật
Endpoint thử: `GET /api/orders/<token>` giới hạn 60 lần/phút/IP, kiểm hạn mức **trước** khi tra token (`CustomerOrderLimiters`), nên token giả trả `404` tới khi vượt thì `429`. Không tạo dữ liệu, không gửi email.
1. Máy tính (Wi-Fi nhà), Git Bash:
   ```
   for i in $(seq 1 62); do curl -s -o /dev/null -w '%{http_code}\n' "https://bk3dprints.com/api/orders/rl-probe-$i"; done | sort | uniq -c
   # mong đợi:  60 404   /   2 429
   ```
2. **Trong vòng 1 phút**, điện thoại tắt Wi-Fi, dùng 4G, mở `https://bk3dprints.com/api/orders/rl-probe-x` → phải thấy lỗi **404** (JSON `NOT_FOUND`), **không** phải 429.
3. Chống giả IP: máy tính, ngay khi đang bị 429:
   ```
   curl -s -o /dev/null -w '%{http_code}\n' -H 'X-Forwarded-For: 203.0.113.7' https://bk3dprints.com/api/orders/rl-probe-spoof
   # vẫn 429 (Caddy ghi đè X-Forwarded-For bằng IP thật của peer)
   ```
4. Log Caddy:
   ```
   VPS$ $C logs --since 5m caddy | grep -o '"client_ip":"[^"]*"' | sort | uniq -c
   ```
   Phải thấy IP public của máy tính và IP 4G của điện thoại; **không** có `172.30.0.1` hay IP nội bộ khác (DEPLOYMENT §3).
**Đạt khi:** máy tính 429 trong khi điện thoại 404; XFF giả không gỡ được 429; log có 2 IP public khác nhau. Khi ghi bằng chứng vào tài liệu: viết "thấy 2 IP public khác nhau, không có 172.30.x" (hoặc che, vd `113.x.x.x`), **không** chép IP thật. Điện thoại cũng 429 → mọi khách đang chung một IP → **dừng launch**, xem bảng §11.

### 7.7 Giới hạn body của Caddy (CHẶN CỨNG)
Cấu hình (`infra/Caddyfile`): `/api/*` 11 MiB, riêng `/api/admin/orders/*/progress/*` 52 MiB, phần còn lại (frontend) 1 MiB (chặt hơn 11 MB, vẫn đạt "11MB toàn cục"). Phân biệt nguồn lỗi: lỗi do **Caddy** trả body text `413 Request Entity Too Large`; lỗi do backend là JSON Problem Details.
```
head -c 12000000 /dev/urandom > /tmp/12mb.bin
# 1) /api/* thường: mong đợi Caddy 413 (text)
curl -s -w '\n%{http_code}\n' -X POST -H 'Content-Type: application/octet-stream' --data-binary @/tmp/12mb.bin https://bk3dprints.com/api/admin/products/1/images
# 2) progress path: mong đợi KHÔNG phải Caddy 413 (thường là 401/403 JSON vì chưa đăng nhập/thiếu CSRF)
curl -s -w '\n%{http_code}\n' -X POST -H 'Content-Type: application/octet-stream' --data-binary @/tmp/12mb.bin https://bk3dprints.com/api/admin/orders/1/progress/1
```
Backend có thể trả 401/403 trước khi Caddy đọc hết body, khi đó lệnh 1 cũng ra 401/403 và phép thử này **không kết luận được** `[CHƯA KIỂM]` hành vi stream của `request_body` (https://caddyserver.com/docs/caddyfile/directives/request_body). Phép thử chắc chắn: đăng nhập admin trong trình duyệt → upload ảnh JPEG ~12 MB vào sản phẩm → DevTools Network thấy `413` với body text của Caddy; upload ảnh tiến trình 2–3 ảnh tổng > 11 MiB (mỗi ảnh ≤ 10 MB) → thành công. Ảnh tiến trình chỉ nhận cho đơn MADE_TO_ORDER ở `IN_PRODUCTION` (API_SPEC), nên phần này cần một đơn thử làm theo đơn đã qua cọc; chưa có thì ghi "chưa thử progress path" và làm sau.
**Đạt khi:** > 11 MiB bị Caddy chặn ngoài progress path; progress path nhận > 11 MiB. Xoá `/tmp/12mb.bin`.

### 7.8 `Cache-Control: no-store` cho `/api/orders/**` (CHẶN CỨNG)
```
curl -s -o /dev/null -D - https://bk3dprints.com/api/orders/rl-probe-cache | grep -iE '^(cache-control|referrer-policy|x-robots-tag):'
curl -s -o /dev/null -D - https://bk3dprints.com/orders/rl-probe-cache     | grep -iE '^(cache-control|referrer-policy|x-robots-tag):'
# cả hai: cache-control: private, no-store / referrer-policy: no-referrer / x-robots-tag: noindex
```
(Chạy sau khi bucket 60/phút của 7.6 đã hết hạn, nếu không sẽ thấy 429; header vẫn phải có.)
**Đạt khi:** cả hai đường dẫn có `no-store`.

### 7.9 `FORWARD_HEADERS_STRATEGY=native` + `TRUSTED_PROXIES`
```
VPS$ docker inspect bkstudio-backend-1 --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -E '^(FORWARD_HEADERS_STRATEGY|TRUSTED_PROXIES|SPRING_PROFILES_ACTIVE)='
# FORWARD_HEADERS_STRATEGY=native / TRUSTED_PROXIES=172.30.0.10/32 / SPRING_PROFILES_ACTIVE=prod
VPS$ docker inspect bkstudio-caddy-1 --format '{{range .NetworkSettings.Networks}}{{.IPAddress}} {{end}}'   # 172.30.0.10
```
Lệnh chỉ lọc 3 biến, không in secret. Backend đang `healthy` = `ProxyConfigValidator` đã chấp nhận giá trị (prod từ chối khởi động nếu sai).
**Đạt khi:** đúng 3 giá trị trên và Caddy có IP `172.30.0.10`. Bằng chứng hành vi là 7.6.

### 7.10 Log không chứa bí mật (CHẶN CỨNG)
Sau 7.4–7.6:
```
VPS$ $C logs --since 30m caddy | grep -cE '/orders/rl-probe|Idempotency-Key|"headers"'   # 0
VPS$ $C logs --since 30m caddy | grep -o '"uri":"/api/orders/[^"]*"' | sort -u | head    # chỉ "/api/orders/redacted"
VPS$ $C logs --since 30m backend | grep -ciE 'mail-tester|@gmail\.com'                     # 0 (không log email)
VPS$ grep '^APP_BASE_URL=' .env                                                            # https://bk3dprints.com
```
**Đạt khi:** cả 3 lệnh đếm/xem cho kết quả như chú thích.

### 7.11 CSP trong trình duyệt thật + Turnstile
Chrome (cửa sổ thường, không tiện ích chặn script), DevTools mở tab Console:
1. Trang chủ và trang sản phẩm thử: ảnh từ `cdn.bk3dprints.com` hiện; Console **không** có `Refused to ... Content Security Policy`.
2. Thêm sản phẩm thử vào giỏ → `/checkout`: widget Turnstile hiện và tự qua (Managed); Console không có lỗi CSP; đặt đơn thử thành công (dùng đơn ở 7.4 cũng được).
3. `/orders/lookup`: widget hiện; gửi mã đơn + email của đơn thử → thông báo chung (202); email link tra cứu tới.
4. Network: request `POST /api/orders` và `POST /api/orders/lookup` không trả `400 CAPTCHA_FAILED` hay `503`.
5. Admin đăng nhập (§8): DevTools → Application → Cookies → `https://bk3dprints.com`: chỉ nhìn các cột **Secure**, **HttpOnly**, **SameSite** của cookie session và `XSRF-TOKEN` (nợ T-042). Mong đợi: cả hai Secure; session HttpOnly + SameSite Lax. **Không** chụp màn hình, không copy cột **Value** (giá trị session = quyền admin).
   Thêm (không cần đăng nhập, giá trị bị che): `curl -s -o /dev/null -D - https://bk3dprints.com/api/admin/auth/me | grep -i '^set-cookie' | sed 's/=[^;]*/=<redacted>/'`. Endpoint này có trả `Set-Cookie` (`XSRF-TOKEN`) khi chưa đăng nhập hay không là `[CHƯA KIỂM]`; không in gì thì chỉ dùng cách DevTools ở trên. Cookie session chỉ có sau khi đăng nhập đủ mật khẩu + TOTP, nên kiểm bằng DevTools.
**Đạt khi:** không lỗi CSP ở cả 3 trang, widget hiện ở cả 2 trang, cookie có Secure.

Ghi mọi kết quả (ngày, điểm, output ngắn, không secret, không IP thật của khách/Khai) vào `docs/DEPLOYMENT.md` qua điều phối viên.

---

## 8. Admin đầu tiên, TOTP, điều khoản (15 phút)

### 8.1 Tạo admin (DEPLOYMENT §8)
Email admin là địa chỉ **riêng, không công khai** (không phải `bk3dprinls@gmail.com`).
```
VPS$ cd ~/bkstudio
VPS$ read -rs ADMIN_INITIAL_PASSWORD && export ADMIN_INITIAL_PASSWORD   # gõ mật khẩu ≥ 12 ký tự, không hiện
VPS$ $C run --rm -e ADMIN_INITIAL_PASSWORD backend --create-admin=<admin-email>
VPS$ unset ADMIN_INITIAL_PASSWORD
VPS$ history -c                                                        # phòng hờ
VPS$ docker ps -a --filter name=bkstudio-backend-run                   # phải trống (log của container này chứa secret TOTP)
```
Output in **một lần**: secret TOTP (Base32), URI `otpauth://`, 10 mã khôi phục.

> **⚠️ Output này là chìa khoá admin. KHÔNG BAO GIỜ dán vào chat (kể cả cho Claude), issue, tài liệu; không chụp màn hình; không quay/ghi màn hình hay chia sẻ màn hình lúc chạy lệnh.** Chỉ báo cho Claude một chữ: "OK" (hoặc thông báo lỗi, nếu exit khác 0).

1. Quét URI bằng app authenticator (hoặc nhập secret Base32).
2. Lưu secret TOTP + 10 mã khôi phục vào password manager.
3. **Đóng hẳn tab/cửa sổ terminal** (hoặc xoá scrollback của terminal), không chỉ gõ `clear` (`clear` vẫn để lại nội dung khi cuộn lên). Mở phiên SSH mới cho bước tiếp.
**Kiểm tra:** `https://bk3dprints.com/admin/login` → mật khẩu + mã TOTP → vào được console. Thử sai TOTP 1 lần → bị từ chối (đừng thử 5 lần: khoá 15 phút).

### 8.2 Cài đặt và đăng điều khoản (`/admin/settings`)
1. **Site settings** → Allowed payment domains: tên miền đăng ký được của link thanh toán Khai thật sự dùng (Artistree, Shopee). Không khai báo public suffix (vd `com.sg`). Social links (https).
2. **Terms and conditions**: làm theo phần đầu `docs/TERMS_DRAFT.md` (hết `[CẦN DUYỆT]`, version = ngày đăng `YYYY-MM-DD`, dán phần dưới dòng "COPY FROM THE NEXT LINE", **Publish new version**). Điều khoản bất biến: sai thì đăng bản mới.
3. Tạo 1 sản phẩm thử (IN_STOCK, có 1 ảnh, giá nhỏ) cho §7. Sau khi xong §7: ẩn hoặc xoá theo cách admin hỗ trợ.
**Kiểm tra:** `PC> curl.exe -s https://bk3dprints.com/api/terms/current` trả `{"version":"...","contentMd":"..."}` (không 404); trang `/policy` hiện điều khoản.

---

## 9. Giám sát và diễn tập rollback (T-045, 25 phút)

### 9.1 Uptime monitor
DEPLOYMENT §7 đề xuất UptimeRobot (cho phép dùng thương mại; email trên gói Free chưa xác nhận) hoặc HetrixTools. Khai đăng ký (miễn phí, không thẻ `[CHƯA KIỂM]`, https://uptimerobot.com/pricing/).
- Monitor HTTP(s): URL `https://bk3dprints.com/api/health`, chu kỳ 5 phút, cảnh báo email về hộp thư Khai đọc hằng ngày.
**Kiểm tra (tiêu chí T-045):**
```
VPS$ $C stop backend          # site trả 502
# đợi ≤ 10 phút → nhận email "down"
VPS$ $C up -d backend         # đợi healthy (~1–2 phút): $C ps
# nhận email "up"
```
Ghi ngày + thời gian từ lúc stop tới lúc nhận email vào DEPLOYMENT §7.

### 9.2 Diễn tập deploy tag thứ hai + rollback (tiêu chí T-045)
Cần tag thứ hai, vd `v0.9.1` trên commit mới nhất của `main` (có thể là chính commit của `v0.9.0` nếu chưa có gì mới; Khai quyết).
```
PC>  git checkout main; git pull; git tag v0.9.1; git push origin v0.9.1     # đợi "Release images" xanh
VPS$ git fetch --tags && git checkout v0.9.1 && bash infra/deploy.sh v0.9.1   # deploy: OK, v0.9.1 is live (previous: v0.9.0)
VPS$ git checkout v0.9.0 && bash infra/deploy.sh --rollback                   # deploy: OK, v0.9.0 is live (previous: v0.9.1)
VPS$ cat infra/.deployed-tag                                                  # v0.9.0 / v0.9.1
VPS$ curl -fsS https://bk3dprints.com/api/health                              # UP
```
Có thể thấy 502 khoảng 1 phút mỗi lần đổi image (bình thường). Muốn ở lại bản mới: `git checkout v0.9.1 && bash infra/deploy.sh v0.9.1`.
**Đạt khi:** cả 3 lệnh deploy in `deploy: OK`, health UP sau mỗi lần. Ghi vào DEPLOYMENT §6b mục 5.

---

## 10. Backup thật và diễn tập khôi phục (T-044 trên VPS, 20 phút)

1. healthchecks.io (free): tạo check lịch 1 ngày, grace 2 giờ, email Khai → copy ping URL vào `.env` (`BACKUP_HEALTHCHECK_URL`), rồi `VPS$ $C up -d backup`.
2. Backup tay:
   ```
   VPS$ $C run --rm backup /backup.sh pre-deploy     # dòng cuối: backup: OK
   ```
   **Kiểm tra:** object mới trong `pre-deploy/` trên R2; healthchecks.io hiện ping thành công.
3. Thử bucket lock (SECURITY §5): dùng **khoá backup trên VPS**, DELETE rồi PUT ghi đè object vừa tạo → cả hai phải bị từ chối (R2 trả 409 `ObjectLockedByBucketPolicy`, DEPLOYMENT §4). Repo chưa có lệnh sẵn cho việc này; cần một client S3 (vd `rclone` hoặc `aws` CLI trên máy Khai) `[CHƯA KIỂM]` cú pháp: https://developers.cloudflare.com/r2/examples/rclone/. Nhờ Claude soạn lệnh khi tới bước này. Khoá ghi backup chỉ đưa vào client qua biến môi trường đặt bằng `read -rs` trong shell đó (rồi `unset`); nếu buộc phải dùng file cấu hình (`rclone.conf`, `~/.aws/credentials`) thì **xoá file ngay sau phép thử**. Không bao giờ giữ khoá này trên máy Khai.
4. Diễn tập khôi phục **không đụng prod** (DEPLOYMENT §6 bước 4, phần "Diễn tập"):
   ```
   VPS$ $C exec postgres createdb -U bkstudio bkstudio_restore
   VPS$ read -rs R2_BACKUP_READ_ACCESS_KEY_ID && read -rs R2_BACKUP_READ_SECRET_ACCESS_KEY
   VPS$ export R2_BACKUP_READ_ACCESS_KEY_ID R2_BACKUP_READ_SECRET_ACCESS_KEY
   VPS$ time $C run --rm --no-deps -e R2_BACKUP_READ_ACCESS_KEY_ID -e R2_BACKUP_READ_SECRET_ACCESS_KEY backup \
          /restore.sh pre-deploy/<YYYY-MM-DDTHHMMSSZ>.dump.gpg postgresql://bkstudio@postgres:5432/bkstudio_restore
   VPS$ unset R2_BACKUP_READ_ACCESS_KEY_ID R2_BACKUP_READ_SECRET_ACCESS_KEY
   VPS$ $C exec postgres psql -U bkstudio -d bkstudio_restore -c 'select count(*) from product;'   # khớp DB thật
   VPS$ $C exec postgres dropdb -U bkstudio bkstudio_restore
   ```
   **Kiểm tra:** `restore: OK, ... in N s`; số dòng khớp. Ghi thời gian vào bảng "Thời gian khôi phục đã đo" (DEPLOYMENT §6).
5. Tiêu chí launch: backup tự chạy ≥ 3 đêm liên tiếp (xem healthchecks.io và `daily/` sau 3 ngày).

---

## 11. Rollback của chính runbook này + bảng lỗi thường gặp

### 11.1 Quay lui theo giai đoạn
| Đang ở | Hỏng | Quay lui |
|---|---|---|
| §2 SSH/ufw | Mất SSH | Web console (VNC/Recovery) của nhà cung cấp → đăng nhập bằng mật khẩu `deploy` → `sudo rm /etc/ssh/sshd_config.d/00-bkstudio.conf` hoặc `sudo ufw disable` → sửa → làm lại. Cách mở console tuỳ nhà cung cấp `[CHƯA KIỂM]` |
| §2–§3 | Cấu hình rối, chưa có dữ liệu | Rebuild server với Ubuntu 24.04 từ console (xoá sạch đĩa; **chỉ** khi chưa có đơn thật; Khai xác nhận) rồi làm lại từ §2 |
| §4–§5 DNS | Site/email sai | Xoá/sửa bản ghi vừa thêm trên Cloudflare; DNS cũ không ảnh hưởng gì vì domain chưa dùng |
| §6 deploy đầu | Không healthy | Không có tag trước để quay về: `$C ps`, `$C logs backend`, sửa `.env`, chạy lại `bash infra/deploy.sh v0.9.0` (idempotent) |
| Sau launch | Bản mới lỗi | `git checkout <tag trước> && bash infra/deploy.sh --rollback` (DEPLOYMENT §6b mục 4) |
| Bỏ hẳn VPS | Đổi nhà cung cấp | Restore theo DEPLOYMENT §6 lên VPS mới, đổi bản ghi A. Xoá VPS cũ (tốn/thôi tốn tiền) = Khai duyệt |

**Không bao giờ** chạy `docker compose ... down -v` trên VPS (xoá `pgdata` = mất đơn), không chạy compose chỉ với `infra/docker-compose.yml` (build image local thay image release).

### 11.2 Lỗi thường gặp
| Triệu chứng | Nguyên nhân hay gặp | Cách sửa |
|---|---|---|
| `deploy: .env not found` | Không đứng ở thư mục repo / `.env` ở chỗ khác | `cd ~/bkstudio`, `ls -l .env` |
| `required variable X is missing a value` | Thiếu biến trong `.env` | Thêm biến (bảng §3.3), `$C config -q` |
| `pull access denied` / `unauthorized` khi pull GHCR | Package chưa Public, hoặc tag chưa build xong | §6.3; xem run "Release images" |
| `exec format error` | VPS ARM | Đổi VPS x86_64 (§1) |
| `Pool overlaps with other one on this address space` | Subnet `172.30.0.0/24` trùng | Dừng, PR đổi subnet + `TRUSTED_PROXIES` cùng lúc |
| Backend không healthy, log báo validator prod | `TURNSTILE_SECRET_KEY` là key thử/trống, `R2_ENDPOINT` không `https://`, thiếu `RESEND_API_KEY`/`MAIL_FROM` | `$C logs backend | tail -50`, sửa `.env`, chạy lại deploy |
| Caddy không lấy được cert, trình duyệt báo lỗi TLS | Bản ghi A sai/chưa lan, cổng 80/443 bị chặn ở firewall nhà cung cấp, bật proxy cam | `Resolve-DnsName`, kiểm firewall, `$C logs caddy`; đợi trước khi thử lại (giới hạn Let's Encrypt) |
| 502 khoảng 1 phút sau deploy | Caddy/backend đang tạo lại | Bình thường; quá 3 phút xem `$C ps` |
| Widget Turnstile không hiện | Repository variable site key thiếu/sai lúc build, hostname widget không có `bk3dprints.com`, Console báo CSP | Sửa variable → tag mới → deploy; sửa hostname widget |
| `400 CAPTCHA_FAILED` dù widget qua | Secret key không cùng widget với site key | Copy lại secret của đúng widget, `$C up -d backend` |
| `503` khi đặt đơn/tra cứu | Backend không gọi được `challenges.cloudflare.com` | `$C exec backend` không có shell; kiểm DNS/egress của VPS, `$C logs backend` |
| `409 TERMS_OUTDATED` | Chưa đăng điều khoản | §8.2 |
| Ảnh vỡ | `CDN_ORIGIN` khác origin của `R2_PUBLIC_BASE_URL`, custom domain chưa Active | Sửa `.env` (cả hai `https://cdn.bk3dprints.com`), `$C up -d caddy backend` |
| Mọi khách chung một IP (điện thoại cũng 429 ở 7.6) | Proxy Cloudflare bật mà Caddy chưa tin Cloudflare, có bản ghi AAAA, hoặc cổng bị publish qua đường khác | Tắt proxy (DNS only), xoá AAAA, kiểm `docker ps` ports; thử lại 7.6 |
| Email không tới | Domain Resend chưa Verified, API key sai domain, chạm trần 80/ngày | Resend dashboard → Domains/Emails; log `Outbox daily cap reached` |
| mail-tester < 9 | DKIM/SPF chưa lan, thiếu DMARC | §5, đợi DNS, thử lại |
| `backup: FAILED` / deploy dừng ở bước backup | Sai tên bucket/khoá backup, token không đúng bucket | Kiểm `.env` phần backup, quyền token (§4.4) |
| `deploy: another deploy is running` | Deploy trước còn chạy hoặc bị kill | Đợi; chắc chắn không có deploy nào: kiểm `ps aux | grep deploy.sh` |
| `deploy: corrupt state` | `infra/.deployed-tag` hỏng | Sửa tay: dòng 1 tag đang chạy, dòng 2 tag trước |
| SSH bị từ chối sau khi bật fail2ban | IP của Khai bị ban | Console nhà cung cấp → `sudo fail2ban-client set sshd unbanip <IP>` |

---

## Nguồn đã đối chiếu (2026-11-21)
- Docker Engine trên Ubuntu: https://docs.docker.com/engine/install/ubuntu/
- ufw: https://ubuntu.com/server/docs/how-to/security/firewalls/
- unattended-upgrades: https://ubuntu.com/server/docs/how-to/software/automatic-updates/
- sshd_config: https://man.openbsd.org/sshd_config
- R2 custom domain / r2.dev: https://developers.cloudflare.com/r2/buckets/public-buckets/
- R2 lifecycle: https://developers.cloudflare.com/r2/buckets/object-lifecycles/
- Turnstile: https://developers.cloudflare.com/turnstile/get-started/ và https://developers.cloudflare.com/turnstile/get-started/widget-management/dashboard/
- Header IP của Cloudflare: https://developers.cloudflare.com/fundamentals/reference/http-headers/
- Resend DNS trên Cloudflare: https://resend.com/docs/knowledge-base/cloudflare · DMARC: https://resend.com/docs/dashboard/domains/dmarc
