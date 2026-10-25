# Development Workflow — BKStudio

> Trạng thái: **Phase 5** · Cập nhật: 2026-10-25 (thêm §12 multi-agent)
> Quy trình làm việc hằng ngày. Quy trình nghiệp vụ (đơn hàng, thanh toán…) nằm ở `WORKFLOWS.md`; chiến lược test ở `TESTING.md`.

## 1. Môi trường máy của bạn (Windows)
| Công cụ | Ghi chú |
|---|---|
| JDK 25 LTS (Eclipse Temurin) | `java -version` phải ra 25; không cần gỡ JDK khác (ADR-0011) |
| Node.js LTS | Cho Next.js |
| Docker Desktop (bật WSL2) | Cho Postgres/MinIO local và Testcontainers |
| Git + Git Bash | Chạy script `.sh`; repo dùng line ending LF (`.gitattributes`) |
| IntelliJ IDEA / VS Code | Tuỳ chọn |
Lệnh Maven trên Windows: `mvnw.cmd …` (PowerShell/CMD) hoặc `./mvnw …` (Git Bash).

## 2. Cấu trúc repo
```
BKStudio/
├── backend/                 Spring Boot
│   └── src/main/java/…/
│       ├── catalog/  order/  queue/  payment/  notification/  admin/
│       └── shared/          lỗi, cấu hình, Money, Clock, requestId
├── frontend/                Next.js
│   └── app/(shop)/  app/admin/  lib/api/  components/
├── e2e/                     Playwright
├── infra/                   docker-compose*.yml, Caddyfile, backup/, deploy.sh
├── docs/                    tài liệu + ADR + tasks.py
└── .github/                 CI, Dependabot, template PR/issue
```
Quy tắc module backend: mỗi module có `api/` (controller + DTO), `domain/` (entity, logic), `infra/` (repository, client ngoài). Module khác **chỉ gọi service public**, không truy cập thẳng repository của module khác.

## 3. Git
**Nhánh**
- `main` luôn chạy được và deploy được. Không commit thẳng vào `main`.
- Mỗi task 1 nhánh: `feat/T-022-checkout-api`, `fix/T-022-idempotency-race`, `docs/…`, `chore/…`.
- Mở Pull Request vào `main` (kể cả làm 1 mình) → CI phải xanh → **squash merge** → xoá nhánh.

**Commit message** — [Conventional Commits](https://www.conventionalcommits.org/) + mã task:
```
feat(order): create checkout endpoint with idempotency key [T-022]
fix(payment): reject confirm when external ref reused [T-025]
test(queue): concurrent slot allocation never exceeds capacity [T-021]
docs: update API_SPEC for order lookup [T-028]
```
Loại: `feat` · `fix` · `test` · `refactor` · `docs` · `chore` · `ci`. Commit nhỏ, mỗi commit build được.

**Bật bảo vệ nhánh `main` trên GitHub**: yêu cầu PR + CI xanh trước khi merge.

## 4. Quy ước code
**Backend (Java)**
- DTO là `record`; **không bao giờ** trả entity JPA ra API hoặc bind request vào entity.
- Inject qua constructor; không `@Autowired` trên field.
- `@Transactional` đặt ở service, không ở controller.
- Tiền: kiểu `long` cent (hoặc value object `Money`), không `double`.
- Thời gian: inject `java.time.Clock` (để test giả được thời gian); lưu `Instant`.
- Lỗi nghiệp vụ: ném exception có `code` (vd `OutOfStockException`) → handler chung chuyển thành ProblemDetail.
- Định dạng code tự động bằng Spotless (palantir-java-format) — CI kiểm tra.
- Log: không ghi email, địa chỉ, token, mật khẩu.

**Frontend (TypeScript)**
- `strict: true`; không dùng `any`.
- Mặc định **server component**; chỉ thêm `"use client"` khi cần state/sự kiện.
- Gọi API qua `lib/api/` có kiểu dữ liệu; validate form bằng zod.
- Không `dangerouslySetInnerHTML`.
- ESLint + Prettier — CI kiểm tra.

## 5. Database migration (Flyway)
- File: `backend/src/main/resources/db/migration/V{số}__{mo_ta}.sql`.
- **Không bao giờ sửa migration đã merge vào `main`** — muốn đổi thì tạo migration mới.
- Thay đổi phá vỡ tương thích (xoá/đổi tên cột) làm 2 bước qua 2 lần deploy: thêm cái mới → chuyển code → xoá cái cũ. Giúp rollback an toàn.
- Mọi migration có test (chạy trong Testcontainers ở CI).
- Cập nhật `DATABASE.md` khi schema đổi.

## 6. Cấu hình & secret
- Mọi cấu hình đọc từ biến môi trường; giá trị mặc định cho dev nằm trong `application-dev.yml` (không chứa secret thật).
- `.env` không bao giờ commit; `.env.example` liệt kê đủ biến (DEPLOYMENT.md §2).
- Lộ secret (kể cả trong commit đã xoá) → **thu hồi và tạo khoá mới ngay**, rồi mới dọn git.

## 7. Quy trình 1 task (với Claude Code)
**Trước khi code**
1. Đọc task trong `TASKS.md`, tài liệu liên quan và code hiện có.
2. Kiểm tra task phụ thuộc đã `done`.
3. Claude đề xuất cách làm + danh sách file sẽ đổi + giải thích khái niệm mới → **Khai duyệt**.
4. Task có thay đổi kiến trúc / bảo mật / dữ liệu thật → phải duyệt rõ ràng trước khi làm.
5. Tạo nhánh, đổi `status="doing"` trong `tasks.py`.

**Khi code**
- Bước nhỏ, commit thường xuyên. Viết test cùng lúc với code (với bug: viết test tái hiện lỗi **trước**).
- **Chế độ học**: với các task cốt lõi — **T-012** (auth), **T-022** (checkout), **T-023** (máy trạng thái), **T-025** (thanh toán) — Khai tự viết phần logic chính trước, Claude review và giải thích. Các task khác Claude có thể viết, nhưng Khai phải đọc và giải thích lại được trước khi merge.

**Sau khi code**
1. Chạy test liên quan + toàn bộ test. **Báo kết quả thật** — không nói "pass" khi chưa chạy.
2. Tự review theo checklist PR (`.github/pull_request_template.md`).
3. Cập nhật tài liệu (xem §9), `tasks.py` (`status="done"`, `actual=…`), `CHANGELOG.md`, `SESSION_HANDOFF.md`.
4. Mở PR → CI xanh → merge.
5. Đề xuất task tiếp theo.

## 8. Definition of Done
Một task chỉ là **done** khi:
- [ ] Mọi tiêu chí nghiệm thu trong `TASKS.md` đạt, có bằng chứng (output test, ảnh chụp, lệnh kiểm tra)
- [ ] Test mới được viết và **cả bộ test** xanh trên máy và trên CI
- [ ] Không có cảnh báo lint/format mới
- [ ] Không có secret, không log dữ liệu cá nhân
- [ ] Giao diện (nếu có) đã kiểm tra ở màn hình 375px
- [ ] Tài liệu liên quan đã cập nhật
- [ ] `tasks.py` có `status="done"` và `actual`; đã chạy `--render`
- [ ] `CHANGELOG.md` có dòng mới; `SESSION_HANDOFF.md` đã cập nhật
- [ ] Khai giải thích lại được code chính của task

## 9. Khi nào cập nhật tài liệu nào
| Thay đổi | Cập nhật |
|---|---|
| API mới / đổi API | `API_SPEC.md` |
| Đổi schema | `DATABASE.md` (+ migration mới) |
| Đổi luồng nghiệp vụ / trạng thái | `WORKFLOWS.md` (+ `REQUIREMENTS.md` nếu đổi quy tắc) |
| Quyết định kiến trúc mới / đổi quyết định cũ | ADR mới trong `DECISIONS/` |
| Biến môi trường / hạ tầng | `DEPLOYMENT.md`, `.env.example` |
| Mối đe doạ mới | `SECURITY.md` |
| Xong task / mốc | `tasks.py` → `TASKS.md`, `CHANGELOG.md`, `SESSION_HANDOFF.md` |

## 10. Báo lỗi & debug
- Mỗi bug: 1 GitHub Issue theo template (`.github/ISSUE_TEMPLATE/bug_report.md`): bước tái hiện, kết quả mong đợi / thực tế, `requestId`, log liên quan, mức độ.
- Mức độ: **S1** mất tiền / lộ dữ liệu / không đặt hàng được → xử lý ngay, bỏ task đang làm · **S2** sai chức năng có cách tránh · **S3** giao diện / nhỏ.
- Quy trình: tái hiện → tìm log theo `requestId` → **viết test thất bại** → sửa → test xanh → ghi vào `CHANGELOG.md`.
- Sự cố production (S1): ghi chú ngắn vào `CHANGELOG.md` mục *Incidents*: chuyện gì xảy ra, ảnh hưởng, nguyên nhân, cách tránh lặp lại.

## 11. Giữ ngữ cảnh giữa các phiên Claude Code
- `CLAUDE.md` (gốc repo) được Claude Code tự đọc mỗi phiên — chứa quy tắc cố định.
- `docs/SESSION_HANDOFF.md` là "biên bản bàn giao": đang ở task nào, làm tới đâu, vướng gì, bước tiếp theo. **Cập nhật cuối mỗi phiên**, kể cả khi task chưa xong.
- Repo + tài liệu là nguồn sự thật duy nhất; không dựa vào trí nhớ của cuộc trò chuyện trước.
- Phiên mới bắt đầu bằng: *"Đọc SESSION_HANDOFF và tiếp tục."*

## 12. Multi-agent (điều phối viên + chuyên gia)
**Mô hình.** Phiên Claude Code chính là *điều phối viên*. Subagent là các "chuyên gia", mỗi con một context riêng, nhận một việc rồi trả báo cáo. Subagent **không gọi được subagent khác**, nên điều phối luôn nằm ở phiên chính.

| Agent | Vai trò | Quyền |
|---|---|---|
| `db-engineer` | Flyway migration, ràng buộc, cấu hình datasource, khoá/race ở DB | Viết |
| `backend-dev` | Service, controller, DTO, domain trong các module | Viết |
| `frontend-dev` | Trang, component Next.js, áp design tokens | Viết |
| `test-engineer` | Test JUnit/Testcontainers/Playwright, tái hiện bug | Viết |
| `devops` | CI, Docker, compose, Caddy, backup (không deploy, không tiêu tiền) | Viết |
| `security-reviewer` | Soát diff theo `SECURITY.md` T1–T19, kết luận BLOCK/OK | Chỉ đọc |
| `code-reviewer` | Đúng/sai, quy ước, Definition of Done | Chỉ đọc |

**Luồng một task** (`/task T-xxx`, file `.claude/commands/task.md`):
1. Điều phối viên đọc task → lập kế hoạch chia việc → **Khai duyệt**.
2. Agent viết code chạy **lần lượt** trên cùng nhánh, để không sửa đè file của nhau.
3. Sau mỗi báo cáo, điều phối viên **tự kiểm lại**: mở file, chạy lại test.
4. Cổng review: `security-reviewer` và `code-reviewer` chạy **song song**. Lỗi Must fix / Critical / High quay lại agent viết code để sửa.
5. Điều phối viên chạy toàn bộ test, giải thích code cho Khai, cập nhật tài liệu điều phối, đưa lệnh `git add <file cụ thể>` + commit. Khai tự push và tạo PR.

**Luật chống xung đột**
- Chỉ điều phối viên sửa `tasks.py`, `TASKS.md`, `CHANGELOG.md`, `SESSION_HANDOFF.md`.
- Mỗi thời điểm chỉ một task có migration Flyway mới.
- Không bao giờ `git add .`.

**Chế độ học** (T-012, T-022, T-023, T-025): agent viết code chỉ dựng khung và viết test. Khai viết logic chính; reviewer soát và giải thích.

**Song song nhiều task** (khi có task độc lập, vd T-010 ∥ T-014): mở mỗi task một phiên bằng `claude --worktree <tên>`. Worktree nằm ở `.claude/worktrees/` (đã gitignore), `.env` được chép vào qua `.worktreeinclude`. Khi chạy app thật, mỗi worktree dùng cổng riêng. Đổi tên nhánh theo quy ước trước khi push.

**Chi phí.** Mỗi subagent tốn token riêng. Task nhỏ (≤ 2 giờ) có thể làm thẳng trong phiên chính, chỉ gọi reviewer.

