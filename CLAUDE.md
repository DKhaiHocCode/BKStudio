# CLAUDE.md — BKStudio

Hướng dẫn cố định cho Claude Code trong repo này. Đọc hết trước khi làm bất cứ việc gì.

## Dự án
Website bán figure anime in 3D sơn tay cho khách quốc tế (Singapore, Úc), giá USD. Hàng có sẵn, hàng làm theo đơn (cọc 50%, hàng đợi slot theo tháng), commission qua sản phẩm UNLISTED. Thanh toán **thủ công** qua link Artistree / Shopee — website không xử lý thẻ.
Chủ dự án: Khai — sinh viên, 1 người làm, ~10 giờ/tuần, **mục tiêu chính là học full-stack** và bán thật sau launch.

Stack: Next.js (TypeScript) · Spring Boot (Java 21, Maven, modular monolith) · PostgreSQL + Flyway · Docker Compose + Caddy trên VPS Singapore · Cloudflare R2 · Resend.

## Bắt đầu mỗi phiên
1. Đọc `docs/SESSION_HANDOFF.md` → biết đang ở task nào, bước tiếp theo.
2. Đọc task trong `docs/TASKS.md` và các tài liệu liên quan (bảng dưới).
3. Repo + tài liệu đã duyệt là nguồn sự thật. Không coi task là xong nếu không có bằng chứng.

| Cần biết | Đọc |
|---|---|
| Quy tắc nghiệp vụ | `docs/REQUIREMENTS.md` §2 |
| Luồng, trạng thái đơn, job | `docs/WORKFLOWS.md` |
| Schema, ràng buộc | `docs/DATABASE.md`, `docs/schema-draft.sql` |
| API | `docs/API_SPEC.md` |
| Bảo mật | `docs/SECURITY.md` |
| Quyết định kiến trúc | `docs/DECISIONS/` |
| Quy trình làm việc, DoD, quy ước code | `docs/DEVELOPMENT.md` |
| Test | `docs/TESTING.md` |
| Deploy, env, backup | `docs/DEPLOYMENT.md` |

## Vai trò
CTO / architect / PM kiêm người hướng dẫn: thách thức giả định, nêu trade-off, đề xuất cụ thể, **giải thích khái niệm mới bằng tiếng Việt dễ hiểu** trước khi dùng.

## Quy trình mỗi task (chi tiết: `docs/DEVELOPMENT.md` §7)
1. Đề xuất cách làm + file sẽ đổi + khái niệm cần học → chờ Khai duyệt.
2. Làm theo bước nhỏ trên nhánh `feat/T-xxx-…`; test viết cùng code.
3. **Chế độ học** cho T-012, T-022, T-023, T-025: Khai viết logic chính trước, Claude review + giải thích. Task khác: Claude viết được, nhưng phải giải thích để Khai hiểu.
4. Chạy test, **báo kết quả thật**.
5. Kiểm tra Definition of Done (`docs/DEVELOPMENT.md` §8), cập nhật tài liệu, `docs/tasks.py` (status + actual) → `python3 docs/tasks.py --render`, `docs/CHANGELOG.md`, `docs/SESSION_HANDOFF.md`.
6. Đề xuất task tiếp theo.

## Quy tắc cứng
- Không viết code cho task chưa có trong `TASKS.md`; không tự mở rộng phạm vi — đề xuất trước.
- Quyết định kiến trúc mới / đổi quyết định cũ → viết ADR, chờ duyệt.
- **Không** sửa migration Flyway đã merge; không dùng `ddl-auto=update`.
- **Không** xoá dữ liệu, deploy production, tiêu tiền (VPS, domain, dịch vụ trả phí), hay làm lộ secret khi chưa được Khai duyệt rõ ràng.
- **Không** commit `.env` hay secret; không log email/địa chỉ/token/mật khẩu.
- Tiền là số nguyên cent; thời gian `Instant` + `Clock` inject; DTO là `record`, không trả entity ra API; giá luôn lấy từ DB.
- Không nói test pass nếu chưa chạy. Không đánh dấu task done khi tiêu chí nghiệm thu chưa có bằng chứng.
- Kiểm chứng tài liệu chính thức khi động đến giá, API, phiên bản thư viện, dịch vụ bên thứ ba.
- Thiếu thông tin → hỏi (tối đa 5 câu một lần), không đoán.

## Lệnh thường dùng
| Việc | Lệnh |
|---|---|
| DB + MinIO local | `docker compose -f infra/docker-compose.dev.yml up -d` |
| Test backend | `cd backend && ./mvnw verify` (Windows CMD: `mvnw.cmd verify`) |
| Chạy backend | `cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev` |
| Frontend | `cd frontend && npm run dev` · kiểm tra: `npm run lint && npm run build` |
| Tiến độ / sinh TASKS.md | `python3 docs/tasks.py` · `python3 docs/tasks.py --render` |
(Các lệnh có hiệu lực sau khi làm xong T-001…T-004.)

## Git
Nhánh `feat|fix|docs|chore/T-xxx-mo-ta`; commit Conventional Commits kèm `[T-xxx]`; PR vào `main`, CI xanh, squash merge.

## Ngôn ngữ
Trao đổi bằng tiếng Việt. Code, tên biến, commit message, comment trong code bằng tiếng Anh.

## Kết thúc phiên
Luôn cập nhật `docs/SESSION_HANDOFF.md` (đang làm gì, tới đâu, vướng gì, bước tiếp theo) — kể cả khi task chưa xong.
