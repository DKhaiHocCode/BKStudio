# Session Handoff

> Cập nhật: 2026-10-26

## Đang ở đâu
**M0 xong về code.** T-007 làm xong trên nhánh `chore/T-007-build-hardening`, **chưa commit/push** (chờ Khai chạy lệnh git, mở PR, xem CI xanh). T-001..T-006 đã merge vào `main` (T-005 = PR #7). Task tiếp theo đề xuất: **T-010** (Catalog API công khai).
Việc Khai còn nợ: bật branch protection trên GitHub (required checks `backend`, `frontend`, `gitleaks`); ghi `actual` giờ cho T-002..T-007 trong `docs/tasks.py` (còn thiếu).

## Đã làm
- T-007: `distributionSha256Sum` cho Maven 3.10.0 trong `maven-wrapper.properties` (sha256 do ta tự tính từ zip, đối chiếu sha512 Apache công bố — Apache không công bố sha256); Spotless 3.10.4 + palantir-java-format 2.102.0, `check` gắn `process-sources` nên `verify`/CI thực thi (`ci.yml` không đổi); `spotless:apply` định dạng lại 3 file Java (chỉ định dạng).
  - **Bằng chứng:** `mvnw.cmd verify` → Surefire 11/11, Failsafe 20/20, BUILD SUCCESS (điều phối viên tự chạy). `spotless:check` đỏ khi cố tình sai định dạng, wrapper báo "Failed to validate Maven distribution SHA-256" khi checksum sai (devops thử trên bản sao, đã hoàn nguyên). Reviewer: không Must fix/Critical/High. **Chưa kiểm chứng:** lần chạy CI trên GitHub với Spotless.
  - Còn lưu ý: Dependabot có thể không bump phiên bản palantir lồng trong `<configuration>` của plugin → tự nâng thỉnh thoảng.
- T-005: CI 3 job (`backend`, `frontend`, `gitleaks`) xanh trên GitHub, gitleaks bắt được secret giả (Khai xác nhận). Lỗi đầu tiên: `typecheck` thiếu `LayoutProps` vì `.next/types` chưa sinh trên checkout mới → `typecheck` giờ chạy `next typegen && tsc --noEmit`. Mọi action ghim SHA, `contents: read`, `persist-credentials: false`. Chưa chạy `actionlint`.
- T-003: `V1__init.sql` (14 bảng, constraint/index đặt tên), Testcontainers PostgreSQL 16 + `@ServiceConnection`, `DbConstraintsIT` = 17 tình huống §5 + 3 test siết thêm. Chưa có JPA (T-010 thêm, `ddl-auto=validate`).
  - **Bằng chứng:** `mvnw.cmd verify` → Surefire 11/11, Failsafe 20/20, 0 skipped, BUILD SUCCESS (điều phối viên tự chạy). Reviewer: không Critical/High/Must fix.
  - Mục đã quyết: siết hash 32 byte, TOTP bật phải có secret, `UNIQUE (lower(email))` cho admin (V1 chưa merge nên sửa tại chỗ). **Bỏ** siết regex `payment_url` vì chưa chốt cổng thanh toán → làm ở T-025 cùng allow-list tên miền.
  - Email admin đầu tiên: `bk3dprinls@gmail.com` (dùng cho lệnh CLI tạo admin ở T-012, không seed vào V1).
  - Ghi nhận cho sau: job ẩn danh PII sau 2 năm (DATABASE.md §6); audit_log/email_outbox payload không được chứa token, email, địa chỉ; profile mặc định chưa có datasource (cấu hình prod thuộc T-043); test #15 chỉ chứng minh SQL của chính nó — T-010 phải test lại trên repository thật; image test `postgres:16-alpine` chưa pin digest.
- T-001 (3h thực tế), T-002 (merge PR #2).
- T-004: `frontend/` Next.js 16.4.2 (App Router, TS 5, Tailwind 4, `src/`), layout chung, `src/lib/api.ts` (`apiFetch`, `ApiError` mang `code/status/requestId`), `rewrites` `/api/*` → `BACKEND_URL` (mặc định `localhost:8080`), `error.tsx` + `global-error.tsx` + `not-found.tsx` dùng chung `ErrorView` (hiện requestId hoặc digest), trang chủ gọi `/api/health` từ trình duyệt, Prettier + `eslint-config-prettier`, scripts `format`, `format:check`, `typecheck`.
  - **Bằng chứng:** `npm run lint`, `typecheck`, `format:check`, `build` đều thành công. Chạy thật backend + `npm run dev`: `GET localhost:3000/api/health` qua proxy → 200 `status=UP` + `X-Request-Id`; trang chủ 200; `/nope` 404. `ErrorView` render đúng "Reference: <requestId>" (kiểm bằng trang tạm, đã xoá).
  - **Chưa kiểm chứng:** hành vi trong trình duyệt thật (máy này không có công cụ trình duyệt): chữ "Backend status: UP" trên trang chủ do client gọi, và error boundary bắt lỗi thật. Khai mở `http://localhost:3000` kiểm tra bằng mắt.

## Lưu ý / quyết định nhỏ
- **T-006 (thiết kế) xong** ngoài code: mockup + tokens trong `docs/DESIGN.md`, `docs/design/tokens.css`, logo `docs/design/logo-512.png`. **Chưa áp vào `frontend/`** để không lẫn vào PR T-004 — việc áp dụng nằm trong T-014 (DESIGN.md §5). Còn mở: tên hiển thị BKStudio hay BK3D; thiếu `actual` của T-006.
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
