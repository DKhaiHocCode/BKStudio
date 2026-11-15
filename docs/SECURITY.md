# Security — BKStudio

> Trạng thái: **Đã duyệt (Phase 3)** · Cập nhật: 2026-10-18

## 1. Tài sản cần bảo vệ
| Tài sản | Vì sao quan trọng |
|---|---|
| Dữ liệu khách (tên, email, địa chỉ, SĐT) | Lộ = mất uy tín, có thể vi phạm luật bảo vệ dữ liệu (SG: PDPA; AU: Privacy Act) |
| Tài khoản admin | Chiếm được = sửa đơn, đổi link thanh toán sang link của kẻ gian, xem toàn bộ dữ liệu khách |
| Tính đúng của đơn / tiền / slot | Sai = mất tiền hoặc mất khách |
| Link thanh toán gửi cho khách | Bị tráo = khách trả tiền cho kẻ gian |
| Secret (mật khẩu DB, khoá R2, khoá Resend, khoá mã hoá TOTP) | Lộ = mất quyền kiểm soát hệ thống |

## 2. Threat model
| # | Mối đe doạ | Biện pháp | Ưu tiên |
|---|---|---|---|
| T1 | Dò mật khẩu admin (brute force, credential stuffing) | **TOTP bắt buộc**; Argon2id; khoá tạm sau 5 lần sai / 15 phút: theo IP (mọi lần sai) và theo tài khoản (chỉ lần sai TOTP/mã khôi phục, sau khi mật khẩu đúng — để kẻ chỉ biết email không khoá được admin); giới hạn Argon2 chạy đồng thời (4); đăng nhập thành công → email thông báo tới `bk3dprinls@gmail.com` (hoãn tới khi có hạ tầng email; email đăng nhập admin là địa chỉ khác, không công khai) | P0 |
| T2 | Đánh cắp session admin | Cookie `HttpOnly; Secure; SameSite=Lax`; đổi session ID sau đăng nhập (chống session fixation); hết hạn sau 30 phút không hoạt động, tối đa 12 giờ | P0 |
| T3 | CSRF (trang lạ lừa trình duyệt admin gửi request) | CSRF token của Spring Security (cookie → header `X-XSRF-TOKEN`) cho mọi POST/PUT/PATCH/DELETE (kể cả endpoint công khai). Nợ chấp nhận ở T-012 (xử lý ở T-043): session, bộ chặn IP và guard chống dùng lại TOTP nằm trong bộ nhớ (mất khi restart, 1 instance); IP khách thật sau Caddy được cấu hình bằng `FORWARD_HEADERS_STRATEGY` / `TRUSTED_PROXIES` (xem DEPLOYMENT.md) và kiểm tra khi khởi động profile prod: trống, regex hoặc CIDR rộng hơn /24 (IPv4) hay /64 (IPv6) → không khởi động (T-047; /24 chỉ là sàn an toàn: khai /24 nghĩa là tin **mọi** máy trong subnet đó — kể cả container frontend — được tự đặt `X-Forwarded-For`; giá trị đi kèm compose là `172.30.0.10/32`, chỉ Caddy) (cũng ảnh hưởng cờ `Secure` của cookie `XSRF-TOKEN`); `anyRequest().permitAll()` là mặc định cho đường dẫn ngoài `/api/admin/**` nên route admin mới bắt buộc nằm dưới tiền tố này | P0 |
| T4 | XSS (chèn script) | React tự escape; **cấm** `dangerouslySetInnerHTML` (trừ mô tả sản phẩm đã qua sanitizer), ESLint chặn trong `frontend/` (T-047; ngoại lệ phải `eslint-disable` kèm lý do); Content-Security-Policy qua Caddy; ảnh phục vụ từ domain riêng `cdn.` | P0 |
| T5 | SQL injection | Chỉ dùng JPA / query có tham số; không ghép chuỗi SQL | P0 |
| T6 | Xem đơn của người khác (IDOR) | Token ngẫu nhiên 256 bit trong link, DB chỉ lưu hash; tra cứu bằng mã đơn + email chỉ **gửi link qua email** và **đổi token** (ADR-0012: link cũ mất hiệu lực; tối đa 3 lần/giờ/đơn, 10 lần/giờ/IP, luôn trả 202 như nhau); token sai hoặc sai định dạng → 404 như nhau; `GET /api/orders/{token}` trả `Cache-Control: no-store` + `Referrer-Policy: no-referrer`, giới hạn 60 lần/phút/IP (T-028); hủy đơn đua với tạo payment request được chặn bằng kiểm tra lại request PENDING sau khi khóa đơn (409) | P0 |
| T7 | Sửa giá / số lượng ở phía client | Backend luôn lấy giá từ DB; DTO riêng cho request (không bind thẳng vào entity → chống mass assignment) | P0 |
| T8 | Tráo / dán nhầm link thanh toán | Chỉ chấp nhận `https://` thuộc danh sách tên miền cho phép (T-025: scheme đúng `https`, không userinfo/cổng/IP, host trùng hẳn mục trong danh sách hoặc kết thúc bằng `.`+mục, ≤ 500 ký tự, không khoảng trắng/ký tự điều khiển; danh sách rỗng = từ chối hết; admin phải khai báo tên miền đăng ký được như `artistree.io`, KHÔNG khai báo public suffix như `com.sg` vì sẽ cho phép mọi host con); DB CHECK `https://`; mọi thay đổi link ghi audit | P0 |
| T9 | Bot spam đơn để giữ hết hàng sẵn / dội email vào hộp thư người khác | Rate limit 5 đơn/IP/giờ và 3 đơn/email/giờ (T-047, cùng `429`, không lộ giới hạn nào bị chạm; email đếm theo dạng chuẩn hoá: chữ thường, bỏ `+tag`). **Rủi ro đã biết của giới hạn theo email:** ai biết email của một khách đều có thể đặt 3 đơn rác bằng email đó và chặn khách thật khỏi checkout trong suốt cửa sổ (1 giờ) — chấp nhận tạm, cách sửa gốc là Turnstile (P1); quyết định chấp nhận đang chờ Khai xác nhận. Outbox gửi tối đa 80 email/24 giờ cuốn chiếu (`app.notification.outbox.daily-cap`, giữ quota Resend cho email quan trọng; vượt trần thì `PENDING` + log `WARN`, không mất email). Khi quota hạn chế, outbox gửi nhóm template nghiệp vụ quan trọng (danh sách trong `OutboxSender`, vd link thanh toán) **trước** `ORDER_RECEIVED`; trong mỗi nhóm gửi theo thứ tự đến trước (FIFO) — nên khi bị spam đơn, email xác nhận đơn mới có thể bị trễ; giữ hàng tự hết hạn **chỉ sau khi admin gửi link thanh toán** (đơn IN_STOCK chưa có link giữ hàng tới khi admin gửi link hoặc hủy — rủi ro chấp nhận, T-027; giảm bằng dashboard "đơn chờ gửi link" ở T-034 và Turnstile P1); đơn làm theo đơn **không** giữ slot khi chưa cọc. P1: Cloudflare Turnstile | P0 / P1 |
| T10 | Upload file độc (chỉ admin) | Kiểm tra magic bytes, giới hạn dung lượng + kích thước pixel, mã hoá lại ảnh, key ngẫu nhiên, phục vụ từ domain khác. Đã triển khai (T-011/T-013): chỉ nhận JPEG/PNG, ≤ 10 MB, ≤ 40 MP, tối đa 2 ảnh giải mã cùng lúc, 20 ảnh/sản phẩm; mọi thao tác ghi sản phẩm/ảnh ghi `audit_log` (không chứa dữ liệu cá nhân). Nợ: file mồ côi trên storage nếu xoá/ghi DB lỗi giữa chừng (chưa có job dọn); slug UNLISTED là bí mật duy nhất của link commission nên cần giới hạn tốc độ GET công khai trước production | P0 |
| T11 | Lộ secret | Secret chỉ ở `.env` trên VPS (quyền 600) và GitHub Actions secrets; `.gitignore` chặn `.env`; quét secret trong CI (gitleaks); khoá TOTP mã hoá AES-GCM bằng khoá từ env | P0 |
| T12 | DB bị truy cập từ Internet | PostgreSQL **không mở port ra ngoài**, chỉ trong mạng Docker; firewall chỉ mở 22/80/443 | P0 |
| T13 | Chiếm VPS qua SSH | Chỉ SSH key, tắt đăng nhập mật khẩu và root; fail2ban; cập nhật bảo mật tự động | P0 |
| T14 | Thư viện có lỗ hổng | Dependabot cho Maven + npm + GitHub Actions + Docker base image (quét hằng tuần, gom minor/patch); xem PR hằng tuần, gộp hằng tháng | P1 |
| T15 | Email giả mạo shop (phishing khách) | SPF + DKIM + DMARC cho domain | P0 |
| T16 | Lộ dữ liệu qua log | Không log email, địa chỉ, token, mật khẩu; log chỉ có mã đơn + requestId. Log Caddy (T-047, cả log truy cập lẫn log mặc định/lỗi): che token đơn, bỏ phần sau `/products/` (slug UNLISTED, cả query), **không ghi header nào** (cả request lẫn response: `Next-Url`/`Referer` có thể chứa token, `Set-Cookie`/`Location` có thể chứa secret); chỉ giữ status, kích thước và thời gian xử lý | P0 |
| T17 | Mất dữ liệu (lỗi, ransomware, xoá nhầm) | Backup hằng ngày ra R2, mã hoá gpg AES256 (T-044). Bucket riêng, private; khoá riêng Object Read & Write **chỉ cho bucket đó** (R2 không có token chỉ-ghi) + bucket lock để khoá trên VPS không xoá/ghi đè được bản gần đây (ADR-0013); diễn tập khôi phục | P0 |
| T19 | Người lạ đoán được link sản phẩm commission | Slug có hậu tố ngẫu nhiên; không hiện ở đâu công khai; `noindex`. Trang chỉ chứa ảnh/mô tả do admin đăng — **không** chứa thông tin cá nhân của khách commission | P0 |
| T18 | DoS | Cloudflare proxy trước domain (tuỳ chọn); rate limit ở Caddy/app | P1 |

## 3. Header bảo mật (Caddy)
`Strict-Transport-Security`, `Content-Security-Policy` (chỉ cho phép script từ chính domain; ảnh từ `cdn.`), `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`, `Permissions-Policy` tắt camera/mic/vị trí, `frame-ancestors 'none'`.

**Rủi ro chấp nhận:** CSP `script-src` có `'unsafe-inline'` (Next chèn script hydration inline và layout có script theme inline; nonce buộc render động, làm hỏng `cacheComponents`/PPR; SRI của Next không cho phép script inline). Giảm thiểu: không có `unsafe-eval`, không nguồn script bên ngoài, React tự escape và cấm `dangerouslySetInnerHTML`, `object-src 'none'`, `base-uri 'self'`, `form-action 'self'`, `frame-ancestors 'none'`. Cấu hình ở `infra/Caddyfile`.

## 4. Xử lý lỗi & logging
- Backend: 1 `@RestControllerAdvice` chuyển mọi exception thành Problem Details (API_SPEC §1). Lỗi nghiệp vụ → 4xx với `code` rõ ràng; lỗi bất ngờ → 500 chung chung + log đầy đủ stack trace kèm `requestId`.
- Lỗi vi phạm constraint DB (vd hết hàng, trùng invoice) được dịch sang `code` nghiệp vụ theo tên constraint.
- Frontend: error boundary hiển thị thông báo thân thiện + `requestId` để khách gửi cho shop.
- Log JSON: `timestamp, level, requestId, logger, message, orderCode?` — không có dữ liệu cá nhân.
- **Audit log** (trong DB) tách biệt với log ứng dụng: ghi mọi thao tác admin và mọi chuyển trạng thái.
- **Outbox email (T-024):** `email_outbox.payload` giữ link đơn (token) cho tới khi email được gửi (`SENT`) hoặc tối đa 7 ngày, sau đó job xoá `orderUrl`/`paymentUrl` khỏi payload. `to_email` vẫn được giữ lại (job dọn dữ liệu cá nhân là nợ kỹ thuật cho sau). Không log người nhận, nội dung, API key; `last_error` chỉ chứa mã HTTP + tên lỗi Resend.

## 5. Checklist bảo mật trước launch (rút gọn — chi tiết ở TESTING/launch checklist Phase 4)
- [ ] Đăng nhập admin chỉ được khi có TOTP; thử khoá tạm hoạt động
- [ ] Không có secret nào trong git history
- [ ] Port 5432 không truy cập được từ Internet
- [ ] Header bảo mật có mặt (kiểm bằng securityheaders.com)
- [ ] Link đơn sai → 404; tra cứu bằng mã + email không hiện dữ liệu
- [ ] Upload file `.php` đổi tên thành `.jpg` bị từ chối
- [ ] SPF/DKIM/DMARC đạt (kiểm bằng mail-tester)
- [ ] Đã khôi phục thử 1 bản backup thành công
- [ ] Dùng chính khoá backup trên VPS, thử DELETE và PUT ghi đè một object vừa upload: cả hai phải bị từ chối (bucket lock). Ghi kết quả vào DEPLOYMENT.md §4
