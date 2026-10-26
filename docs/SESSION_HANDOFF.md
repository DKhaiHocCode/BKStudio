# Session Handoff

> Cập nhật: 2026-10-26

## Đang ở đâu
**M0 xong, đã merge hết vào `main`** (T-001..T-007; T-005 = PR #7, T-007 = PR #13). Branch protection đã bật; `localhost:3000` Khai đã kiểm bằng mắt (T-004 OK); `actionlint` 1.7.7 chạy trên `.github/` không báo lỗi. **T-010 xong và đã merge (PR #15).** **T-011 (pipeline ảnh) xong và đã merge (PR #16).** **Chế độ học đã bỏ (2026-10-26, Khai yêu cầu): subagent viết code cho mọi task, kể cả T-012.** **T-012 (xác thực admin) code xong, đã qua review, chờ Khai commit/PR** trên nhánh `feat/T-012-admin-auth`. Bằng chứng: `mvnw verify` do điều phối viên tự chạy (xoá report cũ) → Surefire 61/61, Failsafe 113/113, BUILD SUCCESS. security-reviewer: H1 (đoán song song vượt khoá, TOCTOU) → giữ chỗ lượt thử nguyên tử trong DB; H2 (biết email là khoá được admin) → khoá tài khoản chỉ tính sai TOTP/mã khôi phục, sai mật khẩu chỉ bị chặn theo IP; M1 (Argon2 không giới hạn) → Semaphore 4; bảng IP đầy → đẩy mục cũ nhất ra (không từ chối); re-review: không còn Critical/High. **Chưa kiểm:** `--create-admin` chưa chạy thật với Postgres dev (Khai chạy khi tạo admin: xem DEPLOYMENT §8; email đăng nhập `knguyenduc64@gmail.com`); TOTP chưa thử với app Authenticator thật (chỉ vector RFC 6238); cờ cookie Tomcat chỉ kiểm qua property, không qua server thật. **Nợ T-043 (bắt buộc trước production):** `server.forward-headers-strategy` sau Caddy (IP giới hạn + cờ Secure của XSRF-TOKEN), image/ENV `TOTP_ENCRYPTION_KEY`; email thông báo đăng nhập (`bk3dprinls@gmail.com`) chờ hạ tầng email; mọi POST công khai cần CSRF token (lưu ý T-022). Bằng chứng: `mvnw verify` do điều phối viên tự chạy (đã xoá report cũ) → Surefire 39/39, Failsafe 49/49 (`ImageServiceIT` 6 với MinIO thật qua Testcontainers), BUILD SUCCESS. security-reviewer: High H1 (WebP đầu vào qua libwebp native) → Khai chọn **bỏ WebP đầu vào** (ADR-0007 cập nhật); H2 (bom metadata PNG) đã sửa phòng thủ nhưng **không tái hiện được** trên JDK 25 (test bom pass cả khi tắt fix → không phải test hồi quy); M1/M3 và code-reviewer Medium đã sửa. Nợ sang task khác: giới hạn upload đồng thời + `multipart.max-file-size` (T-013); T-042/T-043: image Ubuntu glibc (không Alpine), `--enable-native-access=ALL-UNNAMED`, thử `java -jar` upload thật (SPI ImageIO trong fat jar chưa kiểm), memory limit container, R2 token chỉ Object R/W, `R2_PUBLIC_BASE_URL` bắt buộc ở prod; MinIO test dùng tag `latest`; thu nhỏ rất nhiều chưa step-down. Còn nợ T-010: Cache-Control/rate limit GET công khai; tách DTO khỏi `CatalogService` trước T-021/T-022; `estimatedStartMonth` điền ở T-021. Việc tiếp: T-012, rồi T-013.
**Dependabot:** 3 PR major (`typescript 7`, `eslint 10`, `@types/node 26`) Khai đã đóng; `maven-minor-patch` (có bump Spring Boot parent) xem CI rồi quyết.
**Quyết định (2026-10-26):** giữ `ErrorCode.BAD_REQUEST`; request id giữ UUID v4 bỏ gạch; log giữ ECS. Không ghi `actual` giờ.
**Quyết định T-010:** `estimatedStartMonth` = `null` tới T-021; phần tử danh sách = `slug, name, series, coverImage, fromPriceCents, inStock, madeToOrder`; sản phẩm ACTIVE không có biến thể active bị ẩn khỏi danh sách; `shipsWithinDays` = 7 (cấu hình); URL ảnh = `{R2_PUBLIC_BASE_URL}/{storage_key}/{400|1000|2000}.webp`.

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
1. Chuẩn bị máy (nếu chưa): **JDK 25 Temurin** làm `JAVA_HOME`; **Node 22 LTS**; Docker Desktop đang chạy (Testcontainers cần).
2. Ghi `actual` giờ cho T-002, T-004, T-006 trong `docs/tasks.py`.
3. Commit bộ multi-agent (nhánh `chore/multi-agent-setup`, PR riêng, trước T-003).
4. **T-003** trên nhánh `feat/T-003-flyway-testcontainers` — mở Claude Code trong repo, gõ **`/task T-003`**. Dự kiến chia việc:
   - `db-engineer`: dependency Flyway + PostgreSQL + Testcontainers (kiểm artifact đúng với Spring Boot 4.1.1), `V1__init.sql` từ `docs/schema-draft.sql` với **tên constraint tường minh**, datasource dev đọc biến môi trường, JPA `ddl-auto=validate`.
   - `test-engineer`: base class Testcontainers (`@ServiceConnection`), 17 test theo DATABASE.md §5 (test song song: 2 thread + `CountDownLatch`), sửa `HealthEndpointTest` để chạy với container.
   - `security-reviewer` ∥ `code-reviewer` soát diff → sửa → điều phối viên chạy `mvnw.cmd verify`.
5. Sau đó **T-005** CI bằng `/task T-005` (`devops`; Node 22 + JDK 25; file trong `.github/` có thể phải đặt tay).
