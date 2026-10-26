# Single source of truth for TASKS.md / ROADMAP.md numbers.
# Run: python3 docs/tasks.py  → rewrites the generated sections and prints totals.
# h = estimated hours INCLUDING learning time for a first-timer with that tech.
# launch = True  → must be done before launch; False → P0 rule but can ship shortly after launch.
# Progress tracking (add to a task dict when you work on it):
#   status="doing" | "done"   (default "todo")
#   actual=6.5                (real hours spent — compared with h at each milestone review)

TASKS = [
# ---------------- M0 Setup ----------------
dict(id="T-001", m="M0", t="Repo structure & local Postgres", h=3, deps=[], p="P0", launch=True, status="done", actual=3,
  scope="Thư mục `backend/ frontend/ infra/`; `.editorconfig`; `.env.example`; `infra/docker-compose.dev.yml` chạy PostgreSQL 16 + MinIO (giả lập R2).",
  files="infra/docker-compose.dev.yml, .env.example",
  ac="`docker compose -f infra/docker-compose.dev.yml up` chạy được Postgres + MinIO; README có hướng dẫn chạy local.",
  test="Kết nối psql thành công.", risk="Docker Desktop trên Windows cần WSL2."),
dict(id="T-002", m="M0", t="Spring Boot skeleton", h=4, deps=["T-001"], p="P0", launch=True, status="done",
  scope="Maven, Java 25 (ADR-0011), package theo module (catalog, order, queue, payment, notification, admin, shared); `/api/health`; `@RestControllerAdvice` → ProblemDetail có `code` + `requestId`; filter gắn `X-Request-Id`; log JSON.",
  files="backend/pom.xml, backend/src/main/java/.../shared/*",
  ac="`./mvnw verify` xanh; `GET /api/health` trả `{status:UP}`; lỗi trả đúng định dạng API_SPEC §1.",
  test="Unit test cho exception handler; MockMvc test health.", risk="Chọn đúng bản Spring Boot ổn định mới nhất."),
dict(id="T-003", m="M0", t="Flyway V1 + Testcontainers + DB tests", h=5, deps=["T-002"], p="P0", launch=True, status="done",
  scope="Chuyển `docs/schema-draft.sql` thành `V1__init.sql` (đặt tên constraint tường minh); base class integration test với Testcontainers; viết lại 17 tình huống trong DATABASE.md §5 thành test.",
  files="backend/src/main/resources/db/migration/V1__init.sql, backend/src/test/.../DbConstraintsIT.java",
  ac="17 test DB xanh trên PostgreSQL thật trong container.",
  test="Integration test (Testcontainers).", risk="Testcontainers trên Windows cần Docker chạy."),
dict(id="T-004", m="M0", t="Next.js skeleton", h=4, deps=["T-001"], p="P0", launch=True, status="done",
  scope="Next.js App Router + TypeScript + Tailwind; layout chung; API client gọi `/api` (rewrite sang backend khi dev); error boundary hiển thị requestId; ESLint + Prettier.",
  files="frontend/*",
  ac="`npm run build` thành công; trang chủ gọi được `/api/health` qua proxy.",
  test="`npm run lint` + build.", risk="Học App Router (server vs client component)."),
dict(id="T-005", m="M0", t="CI (GitHub Actions)", h=3, deps=["T-003", "T-004"], p="P0", launch=True, status="done",
  scope="Workflow: backend `mvn verify` (có Testcontainers), frontend lint + build, gitleaks quét secret; Dependabot cho Maven + npm.",
  files=".github/workflows/ci.yml, .github/dependabot.yml",
  ac="PR mở ra thì CI chạy và xanh; commit thử 1 secret giả bị gitleaks bắt.",
  test="Chính CI.", risk="—"),
dict(id="T-007", m="M0", t="Hardening build: Spotless + checksum Maven wrapper", h=1, deps=["T-005"], p="P1", launch=False, status="done",
  scope="Thêm Spotless (palantir-java-format), `spotless:check` gắn vào phase `process-sources` nên `verify` (và CI) thực thi, `ci.yml` không đổi (DEVELOPMENT.md §4); thêm `distributionSha256Sum` vào `backend/.mvn/wrapper/maven-wrapper.properties` (từ review T-005).",
  files="backend/pom.xml, backend/.mvn/wrapper/maven-wrapper.properties",
  ac="CI đỏ khi code Java sai định dạng; `./mvnw` từ chối bản Maven sai checksum.",
  test="Chính CI.", risk="Spotless sẽ định dạng lại code đã có (diff lớn một lần)."),

# ---------------- M1 Catalog & admin auth ----------------
dict(id="T-006", m="M1", t="Thiết kế giao diện (mockup + design tokens)", h=5, deps=[], p="P0", launch=True, status="done",
  scope="Định hướng thương hiệu từ logo capybara; mockup trang chủ (desktop + mobile), sản phẩm, Commission, giỏ hàng, checkout, trạng thái đơn trên canvas thiết kế; chọn font Rubik; ghi design tokens (màu, font, viền, bóng, bo góc) vào `docs/DESIGN.md` + `docs/design/tokens.css`.",
  files="docs/DESIGN.md, docs/design/*",
  ac="Chủ dự án duyệt hướng thiết kế và font; tokens có trong repo.",
  test="Duyệt bằng mắt trên canvas.", risk="Tên hiển thị đã chốt: BKStudio. Ảnh sản phẩm cần chụp lại trước launch."),
dict(id="T-010", m="M1", t="Catalog API công khai", h=5, deps=["T-003"], p="P0", launch=True, status="done",
  scope="Entity + repository product/variant/image; `GET /api/products` (chỉ ACTIVE), `GET /api/products/{slug}` (ACTIVE + UNLISTED, cờ `unlisted`); tình trạng có sẵn / làm theo đơn (tôn trọng quota); không lộ số tồn kho.",
  files="backend/.../catalog/*",
  ac="Đúng hợp đồng API_SPEC §2; UNLISTED không bao giờ xuất hiện trong danh sách.",
  test="Integration test cho list/detail/UNLISTED/quota hết.",
  risk="`estimatedStartMonth` = null tới T-021; `R2_PUBLIC_BASE_URL` phải đặt ở prod (T-043), mặc định là MinIO local; thiếu Cache-Control/rate limit cho GET công khai (Caddy/Cloudflare); CatalogService (domain) import DTO của api — tách trước T-021/T-022."),
dict(id="T-011", m="M1", t="Pipeline xử lý & lưu ảnh", h=6, deps=["T-010"], p="P0", launch=True, status="done",
  scope="Kiểm tra magic bytes, ≤10 MB, ≤8000×8000 và ≤40 MP; decode → WebP 400/1000/2000 (mất EXIF, xoay theo orientation); upload R2 (S3 SDK; MinIO khi dev); key ngẫu nhiên; xoá ảnh. Chỉ là service, endpoint upload ở T-013.",
  files="backend/.../catalog/image/*",
  ac="Ảnh JPEG/PNG hợp lệ → 3 file WebP trên storage (đầu vào WebP bị từ chối — rủi ro libwebp native, ADR-0007); file `.php` đổi đuôi `.jpg` bị từ chối; ảnh có GPS EXIF → output không còn EXIF.",
  test="Unit test với file mẫu (hợp lệ, giả mạo, quá lớn); integration test với MinIO.",
  risk="Thư viện `gotson/webp-imageio` 0.2.2 không còn bảo trì, native glibc x86_64/aarch64 → image Docker phải Ubuntu, không Alpine (T-042). Cần: cờ `--enable-native-access=ALL-UNNAMED` trong Dockerfile; thử `java -jar` upload thật (SPI ImageIO trong fat jar chưa kiểm); giới hạn upload đồng thời + `multipart.max-file-size` + heap/container memory (T-013, T-042); R2 token chỉ Object Read/Write; ảnh chưa step-down khi thu nhỏ rất nhiều (hơi răng cưa)."),
dict(id="T-012", m="M1", t="Xác thực admin (session + TOTP)", h=8, deps=["T-002"], p="P0", launch=True, status="done",
  scope="Spring Security: login 2 bước (mật khẩu Argon2id → TOTP), đổi session ID, CSRF cookie→header, timeout 30 phút/12 giờ, khoá tạm 5 lần sai/15 phút (theo IP: mọi lần sai; theo tài khoản: chỉ lần sai TOTP/mã khôi phục, để kẻ biết email không khoá được admin), 10 mã khôi phục, secret TOTP mã hoá AES-GCM; lệnh CLI `--create-admin` tạo admin đầu tiên. Đã bỏ chế độ học (Khai, 2026-10-26): subagent viết code.",
  files="backend/.../admin/auth/*",
  ac="Không có TOTP thì không vào được `/api/admin/**`; sai 5 lần (TOTP/mã khôi phục) bị khoá, IP sai 5 lần bị chặn; request thiếu CSRF bị 403; mã khôi phục dùng 1 lần.",
  test="Integration test cho từng tiêu chí trên.", risk="Nợ chấp nhận, xử lý ở T-043: session/bộ chặn IP/guard TOTP trong bộ nhớ (mất khi restart); IP = getRemoteAddr nên sau Caddy phải cấu hình `server.forward-headers-strategy` (cũng ảnh hưởng cờ Secure của cookie XSRF-TOKEN) — BẮT BUỘC trước khi mở production, nếu không 5 request rác từ ai cũng chặn cả admin 15 phút; email thông báo đăng nhập tới bk3dprinls@gmail.com chờ hạ tầng email; `--create-admin` chưa chạy thật end-to-end (Khai chạy khi tạo admin); mọi POST công khai (checkout T-022) cần CSRF token; route admin mới phải nằm dưới /api/admin/** (mặc định ngoài đó là permitAll); AES-GCM chưa có AAD gắn admin id."),
dict(id="T-013", m="M1", t="Admin API sản phẩm", h=5, deps=["T-011", "T-012"], p="P0", launch=True,
  scope="CRUD product/variant (giá, tồn kho, bật làm theo đơn, quota, `version`); UNLISTED tự gắn hậu tố slug ngẫu nhiên; upload/sắp xếp/sửa alt/xoá ảnh.",
  files="backend/.../catalog/admin/*",
  ac="Đúng API_SPEC §3 Sản phẩm; sửa với `version` cũ → 409 VERSION_CONFLICT.",
  test="Integration test.", risk="—"),
dict(id="T-014", m="M1", t="Trang catalog & sản phẩm (frontend)", h=7, deps=["T-004", "T-006", "T-010"], p="P0", launch=True,
  scope="Áp dụng design tokens (`docs/DESIGN.md` §5: Rubik qua `next/font`, khối `@theme` của Tailwind, bỏ dark mode mặc định, header/footer theo mockup). Trang danh sách (lọc series), trang chi tiết (gallery `srcset`, chọn tỉ lệ, có sẵn / làm theo đơn + tháng dự kiến); render trên server; thẻ Open Graph; `noindex` cho UNLISTED; sitemap chỉ ACTIVE.",
  files="frontend/app/(shop)/*",
  ac="Dán link sản phẩm vào trình kiểm tra OG (vd Facebook Sharing Debugger hoặc opengraph.xyz) thấy ảnh + tên + giá.",
  test="Kiểm tra thủ công + build.", risk="—"),
dict(id="T-015", m="M1", t="Khung admin UI + đăng nhập", h=5, deps=["T-004", "T-006", "T-012"], p="P0", launch=True,
  scope="Trang login + nhập TOTP + mã khôi phục; layout admin; chặn route khi chưa đăng nhập; xử lý CSRF trong API client.",
  files="frontend/app/admin/*",
  ac="Đăng nhập đủ 2 bước vào được admin; hết session tự về trang login.",
  test="Kiểm tra thủ công; E2E ở T-041.", risk="—"),
dict(id="T-016", m="M1", t="Admin UI quản lý sản phẩm", h=7, deps=["T-013", "T-015"], p="P0", launch=True,
  scope="Danh sách + form sản phẩm, biến thể, upload ảnh kéo-thả + sắp xếp, chọn trạng thái (DRAFT/ACTIVE/UNLISTED/ARCHIVED), nút \"Sao chép link\" cho commission.",
  files="frontend/app/admin/products/*",
  ac="Tạo được sản phẩm thật từ đầu đến khi hiện trên catalog; tạo được sản phẩm commission và copy link.",
  test="Kiểm tra thủ công.", risk="UI upload ảnh tốn thời gian hơn dự kiến."),

# ---------------- M2 Orders, payments, queue ----------------
dict(id="T-020", m="M2", t="Settings, điều khoản, link MXH", h=3, deps=["T-003"], p="P0", launch=True,
  scope="`app_setting` (sức chứa mặc định, tên miền link cho phép, link MXH…), `terms_document`; `GET /api/terms/current`, `GET /api/site-info`; admin GET/PUT settings, đăng điều khoản mới.",
  files="backend/.../shared/settings/*",
  ac="Đúng API_SPEC.", test="Integration test.", risk="—"),
dict(id="T-021", m="M2", t="Module hàng đợi slot", h=4, deps=["T-003"], p="P0", launch=True,
  scope="Tự tạo `production_month`; ước tính tháng; cấp slot (`FOR UPDATE SKIP LOCKED`) và trả slot; admin xem/sửa sức chứa (≥ allocated).",
  files="backend/.../queue/*",
  ac="2 lần cấp slot song song không bao giờ vượt capacity; giảm capacity dưới allocated bị từ chối.",
  test="Integration test có chạy song song (2 thread).", risk="—"),
dict(id="T-022", m="M2", t="API checkout (tạo đơn)", h=7, deps=["T-010", "T-020", "T-021"], p="P0", launch=True,
  scope="`POST /api/orders`: validate, 1 loại/đơn, giá lấy từ DB, trừ kho/quota có điều kiện, `Idempotency-Key`, sinh `public_code` + token (lưu hash), lưu bằng chứng điều khoản; rate limit 5 đơn/IP/giờ.",
  files="backend/.../order/checkout/*",
  ac="Gửi giá sai từ client không ảnh hưởng; retry cùng key → cùng đơn; 2 người mua món cuối → 1 người nhận OUT_OF_STOCK.",
  test="Integration test gồm test song song.", risk="Thiết kế transaction đúng là trọng tâm học."),
dict(id="T-023", m="M2", t="Máy trạng thái đơn + audit log", h=5, deps=["T-022"], p="P0", launch=True,
  scope="`OrderStateMachine` với bảng chuyển hợp lệ cho 2 loại đơn; optimistic lock; ghi audit; hook tạo email outbox trong cùng transaction.",
  files="backend/.../order/state/*",
  ac="Mọi cặp (trạng thái, hành động) không hợp lệ → INVALID_TRANSITION.",
  test="Unit test dạng bảng cho toàn bộ cặp chuyển trạng thái.", risk="—"),
dict(id="T-024", m="M2", t="Email outbox + Resend", h=6, deps=["T-003"], p="P0", launch=True,
  scope="Bảng outbox + `dedupe_key`; job gửi mỗi phút, backoff, tối đa 8 lần; interface `EmailSender` (Resend + bản log-only khi dev); template: nhận đơn, link thanh toán, xác nhận, hoàn thiện, nhắc hạn, mất cọc, đã gửi hàng, link tra cứu.",
  files="backend/.../notification/*",
  ac="Resend lỗi tạm thời → email vẫn được gửi sau; cùng `dedupe_key` không gửi 2 lần.",
  test="Integration test với `EmailSender` giả.", risk="Template email HTML hiển thị khác nhau giữa Gmail/Outlook."),
dict(id="T-025", m="M2", t="Payment requests", h=5, deps=["T-021", "T-023", "T-024"], p="P0", launch=True,
  scope="Tạo yêu cầu (kiểm tra tên miền cho phép, `https`), hạn 48 giờ; xác nhận idempotent (bắt buộc mã tham chiếu; cọc → cấp slot; FULL/BALANCE → PAID_IN_FULL); huỷ yêu cầu.",
  files="backend/.../payment/*",
  ac="Bấm xác nhận 2 lần chỉ có 1 hiệu ứng; mã invoice trùng → lỗi rõ ràng; link ngoài danh sách → 400.",
  test="Integration test.", risk="—"),
dict(id="T-026", m="M2", t="Admin API hành động đơn hàng", h=5, deps=["T-025"], p="P0", launch=True,
  scope="Danh sách/lọc/chi tiết (kèm `allowedActions`); start-production, mark-finished (tạo BALANCE), ship, complete, cancel-by-shop (+ trả slot/kho/quota), ghi chú.",
  files="backend/.../order/admin/*",
  ac="Đúng API_SPEC §3 Đơn hàng.", test="Integration test cho từng hành động.", risk="—"),
dict(id="T-027", m="M2", t="Job hết hạn / nhắc hạn / mất cọc", h=4, deps=["T-026"], p="P0", launch=True,
  scope="Hết hạn thanh toán (nhả kho/quota/huỷ đơn), nhắc trả nốt 7 ngày & 1 ngày, FORFEITED sau 30 ngày; mỗi đơn 1 transaction; chạy lại an toàn.",
  files="backend/.../order/jobs/*",
  ac="Chạy job 2 lần liên tiếp không tạo thay đổi/email trùng.",
  test="Integration test với đồng hồ giả (`Clock` inject được).", risk="—"),
dict(id="T-028", m="M2", t="API đơn hàng phía khách", h=3, deps=["T-023", "T-024"], p="P0", launch=True,
  scope="`GET /api/orders/{token}`, `POST …/cancel` theo quy tắc, `POST /api/orders/lookup` (luôn 202, gửi link qua email).",
  files="backend/.../order/customer/*",
  ac="Token sai → 404; lookup không bao giờ trả dữ liệu đơn trực tiếp.", test="Integration test.", risk="—"),

# ---------------- M3 Frontend flows ----------------
dict(id="T-030", m="M3", t="Giỏ hàng + checkout (frontend)", h=7, deps=["T-014", "T-022"], p="P0", launch=True,
  scope="Giỏ hàng lưu localStorage; tách giỏ trộn 2 loại; form khách + địa chỉ (chọn quốc gia); checkbox điều khoản (hiện rõ cọc không hoàn); idempotency key; màn hình thành công.",
  files="frontend/app/(shop)/cart, checkout",
  ac="Đặt được đơn hàng sẵn và đơn làm theo đơn; bấm \"Đặt hàng\" 2 lần chỉ tạo 1 đơn.",
  test="E2E ở T-041.", risk="—"),
dict(id="T-031", m="M3", t="Trang trạng thái đơn + tra cứu", h=4, deps=["T-028", "T-030"], p="P0", launch=True,
  scope="Trang `/orders/{token}`: trạng thái, link thanh toán đang mở, tháng sản xuất, vận đơn, nút huỷ; trang tra cứu bằng mã + email.",
  files="frontend/app/(shop)/orders/*",
  ac="Khách thấy đúng link thanh toán và hạn; huỷ ở QUEUED có hộp xác nhận mất cọc.",
  test="E2E ở T-041.", risk="—"),
dict(id="T-032", m="M3", t="Trang tĩnh: Commission, Policy, FAQ", h=3, deps=["T-014", "T-020"], p="P0", launch=True,
  scope="Trang Commission (quy trình + link Reddit/FB/Instagram/X), Terms/Policy (lấy từ API), FAQ, footer có link MXH, trang Privacy.",
  files="frontend/app/(shop)/commission, policy, faq",
  ac="Link MXH lấy từ settings, đổi trong admin là đổi trên web.", test="Kiểm tra thủ công.", risk="Nội dung điều khoản do chủ shop viết (O-2)."),
dict(id="T-033", m="M3", t="Admin UI đơn hàng", h=8, deps=["T-015", "T-026"], p="P0", launch=True,
  scope="Danh sách + lọc + tìm; chi tiết đơn với lịch sử audit; nút hành động theo `allowedActions`; form tạo yêu cầu thanh toán, xác nhận (nhập mã), gửi hàng.",
  files="frontend/app/admin/orders/*",
  ac="Đi hết vòng đời 1 đơn làm theo đơn chỉ bằng UI admin.", test="E2E ở T-041.", risk="Màn hình phức tạp nhất phía frontend."),
dict(id="T-034", m="M3", t="Admin UI dashboard, hàng đợi, cài đặt", h=5, deps=["T-033", "T-027"], p="P0", launch=True,
  scope="Dashboard việc cần làm (chờ gửi link, chờ xác nhận, sắp quá hạn, email lỗi); trang hàng đợi theo tháng + sửa sức chứa; trang cài đặt (link MXH, tên miền cho phép, điều khoản).",
  files="frontend/app/admin/(dashboard|queue|settings)",
  ac="Mọi đơn cần hành động đều xuất hiện trên dashboard.", test="Kiểm tra thủ công.", risk="—"),
dict(id="T-035", m="M3", t="Responsive + accessibility cơ bản", h=4, deps=["T-031", "T-032", "T-034"], p="P0", launch=True,
  scope="Kiểm tra mọi trang ở 375px / 768px / 1280px; focus bàn phím; alt ảnh; tương phản màu.",
  files="frontend/*", ac="Không có cuộn ngang ở 375px; Lighthouse Accessibility ≥ 90 trang chủ + sản phẩm.",
  test="Lighthouse.", risk="—"),

# ---------------- M4 Hardening & launch ----------------
dict(id="T-040", m="M4", t="Rà soát bảo mật", h=4, deps=["T-035"], p="P0", launch=True,
  scope="Đi hết checklist SECURITY.md §5; CSP + header qua Caddy; kiểm tra rate limit; xem lại log không chứa dữ liệu cá nhân.",
  files="infra/Caddyfile, docs/SECURITY.md", ac="Mọi mục checklist ✅ có bằng chứng.", test="Kiểm tra thủ công + securityheaders.com.", risk="—"),
dict(id="T-041", m="M4", t="E2E test các luồng chính (Playwright)", h=6, deps=["T-035"], p="P0", launch=True,
  scope="3 luồng: mua hàng sẵn → ship; làm theo đơn: cọc → slot → hoàn thiện → trả nốt → ship; commission UNLISTED quota 1. Chạy trong CI với docker compose.",
  files="e2e/*", ac="3 luồng xanh trong CI.", test="Chính nó.", risk="E2E trong CI dễ chập chờn."),
dict(id="T-042", m="M4", t="Dockerfile + compose production + Caddy", h=4, deps=["T-002", "T-004"], p="P0", launch=True,
  scope="Dockerfile nhiều tầng cho backend & frontend (Next.js standalone); `infra/docker-compose.yml` (caddy, frontend, backend, postgres, mạng nội bộ, giới hạn log); Caddyfile.",
  files="backend/Dockerfile, frontend/Dockerfile, infra/*", ac="`docker compose up` trên máy local chạy được toàn bộ hệ thống như production.", test="Chạy thử local.", risk="—"),
dict(id="T-043", m="M4", t="Dựng server & tên miền ⚠️ tốn tiền", h=5, deps=["T-042"], p="P0", launch=True,
  scope="(Cần chủ dự án duyệt chi phí) Thuê VPS theo quy tắc ADR-0005; SSH key, tắt mật khẩu/root, firewall, fail2ban, auto update; mua domain; DNS Cloudflare; bucket R2 + subdomain cdn; domain Resend + SPF/DKIM/DMARC.",
  files="docs/DEPLOYMENT.md", ac="HTTPS hoạt động; mail-tester ≥ 9/10; port 5432 không truy cập được từ ngoài.", test="Kiểm tra thủ công.", risk="Hetzner từ chối xác minh (R9); giá Singapore vượt ngân sách."),
dict(id="T-044", m="M4", t="Backup + diễn tập khôi phục", h=4, deps=["T-043"], p="P0", launch=True,
  scope="Script pg_dump → mã hoá → R2 bucket riêng, xoay vòng 14 ngày + 8 tuần; cron 03:00 SGT; email cảnh báo khi lỗi; khôi phục thử sang máy khác và ghi thời gian.",
  files="infra/backup/*, docs/DEPLOYMENT.md", ac="Khôi phục thành công 1 bản backup, ghi lại thời gian thực tế.", test="Diễn tập.", risk="—"),
dict(id="T-045", m="M4", t="Pipeline deploy + giám sát", h=3, deps=["T-005", "T-043"], p="P0", launch=True,
  scope="CI build image → GHCR; script deploy (`pull && up -d`); uptime monitor `/api/health`; rollback về tag trước.",
  files=".github/workflows/deploy.yml, infra/deploy.sh", ac="Deploy 1 lệnh; rollback thử thành công; tắt backend → nhận email cảnh báo.", test="Thử thật.", risk="—"),
dict(id="T-046", m="M4", t="Launch", h=4, deps=["T-040", "T-041", "T-044", "T-045"], p="P0", launch=True,
  scope="Nhập sản phẩm thật, đặt sức chứa, đăng điều khoản; đặt thử 1 đơn thật trọn vòng (giá nhỏ); chạy launch checklist (ROADMAP §4); mở bán.",
  files="—", ac="Mọi tiêu chí launch ✅.", test="Đơn thật thử nghiệm.", risk="—"),

# ---------------- Can ship shortly after launch ----------------
dict(id="T-050", m="M5", t="Job tự đóng đơn + ẩn danh dữ liệu cũ", h=2, deps=["T-027"], p="P0", launch=False,
  scope="SHIPPED > 30 ngày → COMPLETED; đơn kết thúc > 2 năm → ẩn danh.",
  files="backend/.../order/jobs/*", ac="Chạy lại an toàn.", test="Integration test với đồng hồ giả.", risk="Không gấp: dữ liệu cần ẩn danh sớm nhất sau 2 năm."),
dict(id="T-051", m="M5", t="Nút \"Chuyển thành hàng sẵn\" cho đơn FORFEITED", h=1, deps=["T-033"], p="P0", launch=False,
  scope="Nút trên chi tiết đơn → `stock_qty + 1`.", files="backend + frontend admin", ac="Hoạt động 1 lần duy nhất mỗi đơn.", test="Integration test.",
  risk="Không gấp: đơn FORFEITED đầu tiên xuất hiện sớm nhất ~1 tháng sau launch; tạm thời sửa tồn kho tay."),

# ---------------- P1 (only if time remains) ----------------
dict(id="T-060", m="P1", t="Cloudflare Turnstile chống bot ở checkout", h=2, deps=["T-030"], p="P1", launch=False, scope="", files="", ac="", test="", risk=""),
dict(id="T-061", m="P1", t="Sentry (lỗi backend + frontend)", h=2, deps=["T-045"], p="P1", launch=False, scope="", files="", ac="", test="", risk=""),
dict(id="T-062", m="P1", t="Mã giảm giá đơn giản", h=6, deps=["T-022", "T-033"], p="P1", launch=False, scope="", files="", ac="", test="", risk=""),
dict(id="T-063", m="P1", t="Dời slot sang tháng khác", h=3, deps=["T-034"], p="P1", launch=False, scope="", files="", ac="", test="", risk=""),
]

OWNER = [
("O-1", "Đọc ToS / hỏi support Artistree về hàng vật lý (rủi ro R1)", "Trước T-046"),
("O-2", "Viết nội dung Terms, Policy (ship, cọc không hoàn, thời gian chờ), Privacy, FAQ", "Trước T-032"),
("O-3", "Chụp ảnh + viết mô tả cho các sản phẩm đầu tiên", "Trước T-046"),
("O-4", "Xem giá VPS Singapore, chọn tên miền, duyệt chi phí", "Trước T-043"),
("O-5", "Cung cấp 4 link mạng xã hội", "Trước T-032 (hoặc tự nhập trong admin)"),
]

if __name__ == "__main__":
    import sys
    byid = {t["id"]: t for t in TASKS}
    for t in TASKS:
        for d in t["deps"]:
            assert d in byid, (t["id"], d)
    # topological order + longest path (critical path) over launch tasks
    from functools import lru_cache
    launch = [t for t in TASKS if t["launch"]]
    lset = {t["id"] for t in launch}
    for t in launch:
        for d in t["deps"]:
            assert d in lset, f"launch task {t['id']} depends on non-launch {d}"
    @lru_cache(None)
    def longest(i):
        t = byid[i]
        best = (0, [])
        for d in t["deps"]:
            l = longest(d)
            if l[0] > best[0]:
                best = l
        return (best[0] + t["h"], best[1] + [i])
    end = max(lset, key=lambda i: longest(i)[0])
    cp_h, cp = longest(end)
    tot = lambda f: sum(t["h"] for t in TASKS if f(t))
    print("launch hours:", tot(lambda t: t["launch"]))
    print("post-launch P0 hours:", tot(lambda t: t["p"] == "P0" and not t["launch"]))
    print("P1 hours:", tot(lambda t: t["p"] == "P1"))
    for m in ["M0", "M1", "M2", "M3", "M4", "M5", "P1"]:
        print(m, tot(lambda t, m=m: t["m"] == m), [t["id"] for t in TASKS if t["m"] == m])
    print("critical path:", cp_h, "h", " → ".join(cp))
    print("tasks:", len(TASKS), "max task h:", max(t["h"] for t in TASKS))
    done = [t for t in TASKS if t.get("status") == "done"]
    timed = [t for t in done if "actual" in t]
    est_t = sum(t["h"] for t in timed); act_t = sum(t["actual"] for t in timed)
    print(f"progress: {len(done)}/{len(TASKS)} tasks done"
          + (f"; on tasks with actual hours: estimated {est_t}h vs actual {act_t}h ({(act_t/est_t-1)*100:+.0f}%)" if est_t else "")
          + (f"; missing actual: {', '.join(t['id'] for t in done if 'actual' not in t)}" if len(timed) < len(done) else ""))

def render():
    byid = {t["id"]: t for t in TASKS}
    from functools import lru_cache
    @lru_cache(None)
    def longest(i):
        t = byid[i]; best = (0, [])
        for d in t["deps"]:
            l = longest(d)
            if l[0] > best[0]: best = l
        return (best[0] + t["h"], best[1] + [i])
    lset = [t["id"] for t in TASKS if t["launch"]]
    cp_h, cp = longest(max(lset, key=lambda i: longest(i)[0]))
    tot = lambda f: sum(t["h"] for t in TASKS if f(t))
    names = {"M0": "M0 — Setup", "M1": "M1 — Catalog & đăng nhập admin", "M2": "M2 — Đơn hàng, thanh toán, hàng đợi",
             "M3": "M3 — Giao diện khách & admin", "M4": "M4 — Bảo mật, deploy, launch", "M5": "M5 — Làm ngay sau launch", "P1": "P1 — Chỉ làm nếu còn thời gian"}
    out = []
    out.append("# Tasks — BKStudio\n")
    out.append("> **File này được sinh tự động** từ `docs/tasks.py` (`python3 docs/tasks.py --render`). Sửa dữ liệu trong `tasks.py`, không sửa tay file này.")
    out.append("> Trạng thái task theo dõi bằng cột *Status*: `todo` → `doing` → `done` (cập nhật trong `tasks.py`).\n")
    out.append("## Tổng quan\n")
    out.append("| Nhóm | Giờ | Task |\n|---|---|---|")
    for m in names:
        ids = [t["id"] for t in TASKS if t["m"] == m]
        out.append(f"| {names[m]} | {tot(lambda t, m=m: t['m'] == m)} | {', '.join(ids)} |")
    out.append(f"| **Cần để launch** | **{tot(lambda t: t['launch'])}** | M0–M4 |\n")
    out.append(f"**Critical path** (chuỗi phụ thuộc dài nhất, {cp_h} giờ): " + " → ".join(cp) + "\n")
    out.append("## Sơ đồ phụ thuộc (task cần để launch)\n")
    out.append("```mermaid\nflowchart LR")
    for t in TASKS:
        if not t["launch"]: continue
        out.append(f'  {t["id"].replace("-","")}["{t["id"]}<br/>{t["t"].replace(chr(34),"")}<br/>{t["h"]}h"]')
    for t in TASKS:
        if not t["launch"]: continue
        for d in t["deps"]:
            out.append(f'  {d.replace("-","")} --> {t["id"].replace("-","")}')
    out.append("  classDef crit fill:#fde68a,stroke:#b45309,color:#111")
    out.append("  class " + ",".join(i.replace("-","") for i in cp) + " crit")
    out.append("```")
    out.append("Ô màu vàng = critical path. Vì chỉ có 1 người làm, các task **không** chạy song song thật sự; \"song song được\" nghĩa là **đổi thứ tự thoải mái** (vd đang kẹt backend thì chuyển sang task frontend không phụ thuộc).\n")
    out.append("## Chi tiết task\n")
    for m in names:
        ts = [t for t in TASKS if t["m"] == m]
        out.append(f"### {names[m]}\n")
        if m == "P1":
            out.append("| ID | Task | Giờ | Phụ thuộc |\n|---|---|---|---|")
            for t in ts: out.append(f"| {t['id']} | {t['t']} | {t['h']} | {', '.join(t['deps'])} |")
            out.append("")
            continue
        for t in ts:
            out.append(f"#### {t['id']} · {t['t']}")
            out.append(f"- **Ưu tiên:** {t['p']}{'' if t['launch'] else ' (làm sau launch được)'} · **Ước lượng:** {t['h']} giờ · **Phụ thuộc:** {', '.join(t['deps']) or '—'} · **Status:** {t.get('status','todo')}" + (f" · **Thực tế:** {t['actual']} giờ" if 'actual' in t else ""))
            out.append(f"- **Phạm vi:** {t['scope']}")
            out.append(f"- **File / module:** {t['files']}")
            out.append(f"- **Nghiệm thu:** {t['ac']}")
            out.append(f"- **Test:** {t['test']}")
            out.append(f"- **Rủi ro / câu hỏi:** {t['risk']}\n")
    out.append("## Việc của chủ shop (không tính giờ code)\n")
    out.append("| ID | Việc | Hạn |\n|---|---|---|")
    for o in OWNER: out.append(f"| {o[0]} | {o[1]} | {o[2]} |")
    open("docs/TASKS.md", "w", encoding="utf-8").write("\n".join(out) + "\n")

if __name__ == "__main__" and "--render" in __import__("sys").argv:
    render(); print("TASKS.md rendered")
