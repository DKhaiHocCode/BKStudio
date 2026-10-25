# Session Handoff

> Cập nhật: 2026-10-25 (tối)

## Đang ở đâu
**M0 còn lại: T-003 và T-005.** T-001, T-002, T-004, T-006 đã merge vào `main` (T-004 và T-006 nằm chung một commit do `git add .` — không sửa lịch sử, chấp nhận). Task tiếp theo: **T-003** (Flyway V1 + Testcontainers + 17 test DB, 5 giờ).
Repo đã chuyển sang `D:\Project\Side_Project\BKStudio` (2026-10-25).
**Mới (2026-10-25): dựng multi-agent** — `/task T-xxx` + 7 subagent trong `.claude/agents/` (DEVELOPMENT.md §12). Chưa commit: nhánh `chore/multi-agent-setup`.

## Đã làm
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
