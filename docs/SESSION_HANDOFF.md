# Session Handoff

> Cập nhật: 2026-10-29

## Đang ở đâu
**Xong và đã merge vào `main`:** M0, T-010..T-017, T-020 (PR #23), T-021 (PR #24). **T-022 (API checkout, backend) code xong, đã đóng, CHỜ KHAI commit/push/mở PR** trên nhánh `feat/T-022-checkout`. Bằng chứng: `mvnw verify` điều phối viên tự chạy → Surefire 139/139, Failsafe 594/594 (checkout: CheckoutApiIT 69, CheckoutConcurrencyIT 12, CheckoutRateLimitIT 1 + unit), BUILD SUCCESS. Reviewer: code-reviewer 0 Must fix; security-reviewer 1 High (proxy IP, nợ T-043, đã ghi thành chặn cứng) — Medium đã sửa (NUL/ký tự điều khiển, chặn IP trước transaction, IPv6 /64, log PII khi lỗi DB).
**Quyết định T-022:** gửi lại cùng `Idempotency-Key` → 200, `orderUrl = null` (DB chỉ lưu hash token), chỉ so loại đơn + email (khác → 409), không so danh sách hàng; IP đã chạm giới hạn bị 429 kể cả khi gửi lại key cũ; cọc `(subtotal+1)/2`; ship_country chỉ `SG`/`AU`; `APP_BASE_URL` (đã có trong DEPLOYMENT.md) tạo `orderUrl`; không cấp slot ở checkout (T-025 cấp khi xác nhận cọc); chưa ghi audit/outbox. `CheckoutService.hashToken` = SHA-256 của token ASCII: T-023/T-028 dùng đúng cách này để tra đơn.
**Nợ T-022:** (1) T-043 CHẶN CỨNG trước khi mở public: tin đúng header IP từ Caddy, nếu không cả thế giới dùng chung 5 đơn/giờ; (2) T-024 phải đưa email "Đã nhận đơn" (link token) vào outbox trong transaction checkout vì replay không trả link; (3) giữ hàng chưa tự hết hạn tới T-027 (bot có thể giữ tối đa 250 chiếc/giờ/IP) — cân nhắc trần số chiếc mỗi đơn và Turnstile (P1); (4) `allocate` slot chỉ gọi 1 lần cho mỗi item; (5) rate limiter trong bộ nhớ, đếm cả lần thất bại; (6) cọc làm tròn: T-025 phải dùng đúng `deposit = (subtotal+1)/2`, balance = subtotal − deposit.
**Quyết định T-021:** `production_month` không có cột `version` nên PUT capacity chỉ nhận `{capacity}` (0..1000, tháng ≥ hiện tại), dùng `UPDATE … WHERE allocated <= capacity mới` nguyên tử → 409 `CAPACITY_BELOW_ALLOCATED`; cửa sổ 24 tháng theo UTC; `allocate(orderItemId, units)` khoá `FOR UPDATE SKIP LOCKED` tháng sớm nhất còn chỗ, thử lại 5×20 ms rồi một lần chờ khoá (lock_timeout 2s); hết chỗ / deadlock / hết timeout → 409 `QUEUE_FULL` và rollback cả lời gọi; `release(orderItemId)` huỷ entry mở, trả slot; `estimate(qty)` chỉ đọc (qty>1 tìm MỘT tháng đủ qty chỗ, còn allocate có thể chia qua nhiều tháng); `CatalogService.detail` điền `estimatedStartMonth` chỉ khi có biến thể làm theo đơn.
**Nợ/lưu ý T-021 cho T-022/T-025:** `allocate` chỉ được gọi 1 lần cho mỗi item (lần 2 đụng `uq_queue_entry_item_unit` → 500; xác nhận cọc phải idempotent); `orderItemId` phải do server tạo; `QUEUE_FULL` có thể do deadlock nhiều chiếc nên caller cân nhắc retry; đường chờ khoá và WARN khi `release` chạm 0 chưa có test riêng; estimate công khai chưa cache phía server (Caddy/Cloudflare rate limit ở T-043); admin có thể đặt capacity cho tháng xa tuỳ ý (chỉ chặn quá khứ).
**Quyết định T-020:** `payment_allowed_domains` lưu chuỗi nối dấu phẩy trong `app_setting`; social URL chỉ https + đúng host nền tảng + không cổng; điều khoản bất biến, `GET /api/terms/current` 404 tới khi đăng bản đầu → **Khai (hoặc UI admin T-034) phải đăng bản điều khoản đầu tiên trước T-022** (nội dung điều khoản là O-2). `SettingsService.defaultCapacity()` là nguồn duy nhất của giá trị mặc định 5. Audit settings/terms dùng `entityId = 0`. Test dọn dữ liệu bằng `TRUNCATE terms_document CASCADE` (xoá cả `customer_order`): các IT đơn hàng ở T-022 phải tự dọn và chạy tuần tự.
**Nợ T-020:** DB chưa có trigger chặn UPDATE/DELETE `terms_document`; chưa giới hạn kích thước body JSON cho `/api/admin/**` (T-040); T-025 phải so khớp host chính xác với danh sách tên miền (xem `risk` của T-025); T-032 phải render markdown có lọc.
**Nợ T-014/T-017 (giữ):** soft 404 slug lạ; CSP/header ở Caddy (hash cho script theme); `NEXT_PUBLIC_SITE_URL` build arg ở T-042; lưới shop 50 sản phẩm; link `#`/`/commission`/`/track`/`/cart` 404 tới các task sau; đừng chạy `npm run build` khi Khai đang chạy `npm run dev`.
**Việc kế tiếp:** Khai commit/push/PR T-022, merge, `git checkout main && git pull`, rồi `/task T-023` (máy trạng thái đơn + audit log) — hoặc T-024 (email outbox) nếu muốn có email sớm; T-025 cần cả hai. Trước khi mở web thật phải đăng bản điều khoản đầu tiên (blocker O-2).

### Cách làm việc đã chốt với Khai (đọc trước khi làm)
- Gọi Khai là **"đại ca"**, nói tiếng Việt; code/commit/comment tiếng Anh. Phiên có hook caveman (ngắn gọn, đáp án trước) + ponytail (thay đổi nhỏ nhất; cuối câu trả lời nêu cái chưa kiểm).
- **Đã bỏ chế độ học** (2026-10-26): subagent viết code cho mọi task. Khai không cần ghi `actual` giờ.
- Quy trình `/task T-xxx`: điều phối viên (phiên chính) lập kế hoạch + chờ Khai duyệt (Khai thường trả lời "duyệt theo đề xuất") → giao `backend-dev` / `test-engineer` lần lượt (brief tự đủ ngữ cảnh, subagent không thấy chat) → tự chạy lại `./mvnw verify` (xoá `target/surefire-reports` + `target/failsafe-reports` trước; đếm test bằng awk, KHÔNG dùng `python -` chờ stdin) → `security-reviewer` ∥ `code-reviewer` → sửa Must fix/Critical/High, chạy lại reviewer → đóng task: `tasks.py` (`status="done"`, chạy `PYTHONUTF8=1 python docs/tasks.py --render`), `CHANGELOG`, `SESSION_HANDOFF`, `API_SPEC`/`SECURITY`/`DEPLOYMENT` nếu đổi. Chỉ điều phối viên sửa 4 file điều phối. Không push/merge; đưa Khai lệnh `git add <danh sách file>` + commit (Conventional Commits `[T-xxx]`, kết thúc `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`) + push.
- Đang ở nhánh `main` thì hỏi Khai trước khi tạo nhánh `feat/T-xxx-...`.
- Quyết định bảo mật đã chốt: email đăng nhập admin `knguyenduc64@gmail.com`; email nhận thông báo `bk3dprinls@gmail.com` (gửi khi có hạ tầng email, chưa làm). Khai đã tạo admin thật bằng `--create-admin`; **chưa thử TOTP với app Authenticator thật** (chỉ vector RFC 6238). Khoá tài khoản chỉ tính sai TOTP/mã khôi phục (5/15 phút, giữ chỗ lượt thử nguyên tử); sai mật khẩu chỉ bị chặn theo IP.
- Gitleaks quét cả lịch sử: chuỗi test RFC 6238 đã được cho phép trong `.gitleaks.toml`; đừng đưa secret giả trông như thật vào test.

### Nợ kỹ thuật cần nhớ (chi tiết ở `tasks.py` mục `risk` của từng task)
- **T-043 (bắt buộc trước production):** `server.forward-headers-strategy` sau Caddy (IP limiter + cờ Secure của cookie XSRF-TOKEN; nếu không 5 request rác chặn cả admin), image Docker Ubuntu glibc (không Alpine) + `--enable-native-access=ALL-UNNAMED`, thử `java -jar` upload ảnh thật (SPI ImageIO trong fat jar chưa kiểm), `TOTP_ENCRYPTION_KEY`, `R2_PUBLIC_BASE_URL`, R2 token chỉ Object R/W, Caddy cho body ≥ 11 MB trên `/api/admin`, `java.io.tmpdir` ghi được.
- **T-022:** trừ `stock_qty`/quota phải kèm `version = version + 1`; mọi POST công khai cần CSRF token (GET trước để lấy cookie XSRF-TOKEN, gửi header `X-XSRF-TOKEN`).
- Rate limit/Cache-Control cho GET công khai (slug UNLISTED là bí mật duy nhất của link commission); tách DTO khỏi `CatalogService` trước T-021/T-022; `estimatedStartMonth` điền ở T-021; file ảnh mồ côi chưa có job dọn; job ẩn danh PII sau 2 năm; `payment_url` regex + allow-list ở T-025; áp design tokens vào frontend ở T-014.
- Session/IP limiter/guard TOTP nằm trong bộ nhớ (mất khi restart, 1 instance). MinIO test dùng tag `latest`. Test bom metadata PNG không phải test hồi quy thật.
- Máy Khai: Node 20.17 (nên nâng 22 LTS). Windows: dùng `python`, `PYTHONUTF8=1`. Backend chỉ đọc biến môi trường của shell, không đọc `.env` (nạp `.env` vào PowerShell trước khi `spring-boot:run`).

### Quyết định cũ
**Quyết định (2026-10-26):** giữ `ErrorCode.BAD_REQUEST`; request id UUID v4 bỏ gạch; log ECS.
**T-010:** `estimatedStartMonth` = `null` tới T-021; phần tử danh sách = `slug, name, series, coverImage, fromPriceCents, inStock, madeToOrder`; ACTIVE không có biến thể active bị ẩn; `shipsWithinDays` = 7; URL ảnh = `{R2_PUBLIC_BASE_URL}/{storage_key}/{400|1000|2000}.webp`.
**T-013:** thêm `GET /api/admin/products/{id}`; mã lỗi `CONFLICT` (409), `PAYLOAD_TOO_LARGE` (413); slug bất biến, UNLISTED luôn có hậu tố ngẫu nhiên 10 ký tự (kể cả khi đổi sang UNLISTED lúc sửa); ghi bằng JdbcTemplate + `version`; `AuditLogWriter` (`shared/audit`) dùng lại cho các task sau; `JacksonConfig` từ chối số cho enum; tối đa 20 ảnh/sản phẩm, 2 ảnh xử lý cùng lúc (cấu hình `app.admin.upload.*`).


## Đã làm
- T-007: `distributionSha256Sum` cho Maven 3.10.0 trong `maven-wrapper.properties` (sha256 do ta tự tính từ zip, đối chiếu sha512 Apache công bố — Apache không công bố sha256); Spotless 3.10.4 + palantir-java-format 2.102.0, `check` gắn `process-sources` nên `verify`/CI thực thi (`ci.yml` không đổi); `spotless:apply` định dạng lại 3 file Java (chỉ định dạng).
  - **Bằng chứng:** `mvnw.cmd verify` → Surefire 11/11, Failsafe 20/20, BUILD SUCCESS (điều phối viên tự chạy). `spotless:check` đỏ khi cố tình sai định dạng, wrapper báo "Failed to validate Maven distribution SHA-256" khi checksum sai (devops thử trên bản sao, đã hoàn nguyên). Reviewer: không Must fix/Critical/High. **Chưa kiểm chứng:** lần chạy CI trên GitHub với Spotless.
  - Còn lưu ý: Dependabot có thể không bump phiên bản palantir lồng trong `<configuration>` của plugin → tự nâng thỉnh thoảng.
- T-005: CI 3 job (`backend`, `frontend`, `gitleaks`) xanh trên GitHub, gitleaks bắt được secret giả (Khai xác nhận). Lỗi đầu tiên: `typecheck` thiếu `LayoutProps` vì `.next/types` chưa sinh trên checkout mới → `typecheck` giờ chạy `next typegen && tsc --noEmit`. Mọi action ghim SHA, `contents: read`, `persist-credentials: false`. Chưa chạy `actionlint`.
- T-003: `V1__init.sql` (14 bảng, constraint/index đặt tên), Testcontainers PostgreSQL 16 + `@ServiceConnection`, `DbConstraintsIT` = 17 tình huống §5 + 3 test siết thêm. Chưa có JPA (T-010 thêm, `ddl-auto=validate`).
  - **Bằng chứng:** `mvnw.cmd verify` → Surefire 11/11, Failsafe 20/20, 0 skipped, BUILD SUCCESS (điều phối viên tự chạy). Reviewer: không Critical/High/Must fix.
  - Mục đã quyết: siết hash 32 byte, TOTP bật phải có secret, `UNIQUE (lower(email))` cho admin (V1 chưa merge nên sửa tại chỗ). **Bỏ** siết regex `payment_url` vì chưa chốt cổng thanh toán → làm ở T-025 cùng allow-list tên miền.
  - Email đăng nhập admin đầu tiên: `knguyenduc64@gmail.com` (đổi 2026-10-26; dùng cho lệnh CLI tạo admin ở T-012, không seed vào V1). Email nhận thông báo (đăng nhập thành công, cảnh báo…): `bk3dprinls@gmail.com`, gửi khi có hạ tầng email (task thông báo).
  - Ghi nhận cho sau: job ẩn danh PII sau 2 năm (DATABASE.md §6); audit_log/email_outbox payload không được chứa token, email, địa chỉ; profile mặc định chưa có datasource (cấu hình prod thuộc T-043); test #15 chỉ chứng minh SQL của chính nó — T-010 phải test lại trên repository thật; image test `postgres:16-alpine` chưa pin digest.
- T-001 (3h thực tế), T-002 (merge PR #2).
- T-004: `frontend/` Next.js 16.4.2 (App Router, TS 5, Tailwind 4, `src/`), layout chung, `src/lib/api.ts` (`apiFetch`, `ApiError` mang `code/status/requestId`), `rewrites` `/api/*` → `BACKEND_URL` (mặc định `localhost:8080`), `error.tsx` + `global-error.tsx` + `not-found.tsx` dùng chung `ErrorView` (hiện requestId hoặc digest), trang chủ gọi `/api/health` từ trình duyệt, Prettier + `eslint-config-prettier`, scripts `format`, `format:check`, `typecheck`.
  - **Bằng chứng:** `npm run lint`, `typecheck`, `format:check`, `build` đều thành công. Chạy thật backend + `npm run dev`: `GET localhost:3000/api/health` qua proxy → 200 `status=UP` + `X-Request-Id`; trang chủ 200; `/nope` 404. `ErrorView` render đúng "Reference: <requestId>" (kiểm bằng trang tạm, đã xoá).
  - **Chưa kiểm chứng:** hành vi trong trình duyệt thật (máy này không có công cụ trình duyệt): chữ "Backend status: UP" trên trang chủ do client gọi, và error boundary bắt lỗi thật. Khai mở `http://localhost:3000` kiểm tra bằng mắt.

## Lưu ý / quyết định nhỏ
- **T-006 (thiết kế) xong** ngoài code: mockup + tokens trong `docs/DESIGN.md`, `docs/design/tokens.css`, logo `docs/design/logo-512.png`. **Chưa áp vào `frontend/`** để không lẫn vào PR T-004 — việc áp dụng nằm trong T-014 (DESIGN.md §5). Tên hiển thị đã chốt: **BKStudio** (2026-10-26). Khai quyết định **không cần ghi `actual` giờ** cho các task đã xong (2026-10-26) — dòng "missing actual" của `tasks.py` chỉ là thông tin.
- Next 16.4 có khác biệt so với tài liệu cũ: `error.tsx` nhận `{ error, retry }` (không còn `reset`); `cacheComponents` + `partialPrefetching` bật sẵn trong `next.config.ts` (dữ liệu động phải nằm trong Suspense hoặc client component). `frontend/AGENTS.md` do Next sinh, yêu cầu đọc `node_modules/next/dist/docs/` trước khi viết code — giữ nguyên.
- Node trên máy là 20.17: `npm install` báo EBADENGINE (eslint-visitor-keys cần ^20.19 || ^22.13 || >=24) nhưng lint/build vẫn chạy. Khuyến nghị nâng lên Node 22 LTS trước khi có CI (T-005 phải dùng cùng phiên bản).
- `npm audit`: 5 lỗi "high" (braces → micromatch → fast-glob → `eslint-config-next`), chỉ ở dependency lint lúc dev, không vào bundle. `npm audit fix --force` sẽ hạ eslint-config-next xuống 14 → **không chạy**. Theo dõi khi Next ra bản vá.
- Lỗi từ Server Component chỉ chuyển `digest` sang client (không có requestId của backend); requestId chỉ hiện với lỗi `ApiError` ném ở client component.
- Máy Khai chưa có JDK 25 (xem ghi chú T-002); tôi dùng bản Temurin giải nén trong thư mục tạm của phiên.
- `ErrorCode.BAD_REQUEST`, request id UUID và định dạng log ECS (T-002) vẫn chờ Khai quyết định.
- Windows: `python3` là alias Store, dùng `python`; `PYTHONUTF8=1 python docs/tasks.py --render`.

## Đang chặn
- (Trước T-032) O-2 nội dung điều khoản; O-5 link mạng xã hội.
- (Trước T-043) O-4 chọn VPS + tên miền, duyệt chi phí.
- (Trước launch) O-1 ToS Artistree.


## Việc tiếp theo
1. Khai: commit/push/PR T-013 → CI xanh → squash merge → `git checkout main && git pull`.
2. `/task T-015` (frontend-dev: trang login + TOTP + mã khôi phục, layout admin, chặn route khi chưa đăng nhập, CSRF trong `apiFetch`; API đăng nhập ở `docs/API_SPEC.md` §3 'Xác thực'; Next 16.4 khác tài liệu cũ, đọc `frontend/AGENTS.md`). Rồi `/task T-016`.
3. Dọn: merge Dependabot `maven-minor-patch`; nâng Node 22.
