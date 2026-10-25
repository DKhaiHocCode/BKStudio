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
| T1 | Dò mật khẩu admin (brute force, credential stuffing) | **TOTP bắt buộc**; Argon2id; khoá tạm sau 5 lần sai / 15 phút (theo tài khoản và IP); đăng nhập thành công → email thông báo | P0 |
| T2 | Đánh cắp session admin | Cookie `HttpOnly; Secure; SameSite=Lax`; đổi session ID sau đăng nhập (chống session fixation); hết hạn sau 30 phút không hoạt động, tối đa 12 giờ | P0 |
| T3 | CSRF (trang lạ lừa trình duyệt admin gửi request) | CSRF token của Spring Security (cookie → header `X-XSRF-TOKEN`) cho mọi POST/PUT/PATCH/DELETE | P0 |
| T4 | XSS (chèn script) | React tự escape; **cấm** `dangerouslySetInnerHTML` (trừ mô tả sản phẩm đã qua sanitizer); Content-Security-Policy qua Caddy; ảnh phục vụ từ domain riêng `cdn.` | P0 |
| T5 | SQL injection | Chỉ dùng JPA / query có tham số; không ghép chuỗi SQL | P0 |
| T6 | Xem đơn của người khác (IDOR) | Token ngẫu nhiên 256 bit trong link, DB chỉ lưu hash; tra cứu bằng mã đơn + email chỉ **gửi link qua email**; token sai → 404 | P0 |
| T7 | Sửa giá / số lượng ở phía client | Backend luôn lấy giá từ DB; DTO riêng cho request (không bind thẳng vào entity → chống mass assignment) | P0 |
| T8 | Tráo / dán nhầm link thanh toán | Chỉ chấp nhận `https://` thuộc danh sách tên miền cho phép; DB CHECK `https://`; mọi thay đổi link ghi audit | P0 |
| T9 | Bot spam đơn để giữ hết hàng sẵn | Rate limit 5 đơn/IP/giờ; giữ hàng tự hết hạn; đơn làm theo đơn **không** giữ slot khi chưa cọc. P1: Cloudflare Turnstile | P0 / P1 |
| T10 | Upload file độc (chỉ admin) | Kiểm tra magic bytes, giới hạn dung lượng + kích thước pixel, mã hoá lại ảnh, key ngẫu nhiên, phục vụ từ domain khác | P0 |
| T11 | Lộ secret | Secret chỉ ở `.env` trên VPS (quyền 600) và GitHub Actions secrets; `.gitignore` chặn `.env`; quét secret trong CI (gitleaks); khoá TOTP mã hoá AES-GCM bằng khoá từ env | P0 |
| T12 | DB bị truy cập từ Internet | PostgreSQL **không mở port ra ngoài**, chỉ trong mạng Docker; firewall chỉ mở 22/80/443 | P0 |
| T13 | Chiếm VPS qua SSH | Chỉ SSH key, tắt đăng nhập mật khẩu và root; fail2ban; cập nhật bảo mật tự động | P0 |
| T14 | Thư viện có lỗ hổng | Dependabot cho Maven + npm + GitHub Actions (quét hằng tuần, gom minor/patch); xem PR hằng tuần, gộp hằng tháng | P1 |
| T15 | Email giả mạo shop (phishing khách) | SPF + DKIM + DMARC cho domain | P0 |
| T16 | Lộ dữ liệu qua log | Không log email, địa chỉ, token, mật khẩu; log chỉ có mã đơn + requestId | P0 |
| T17 | Mất dữ liệu (lỗi, ransomware, xoá nhầm) | Backup hằng ngày ra R2 (bucket riêng, khoá riêng chỉ có quyền ghi), mã hoá; diễn tập khôi phục | P0 |
| T19 | Người lạ đoán được link sản phẩm commission | Slug có hậu tố ngẫu nhiên; không hiện ở đâu công khai; `noindex`. Trang chỉ chứa ảnh/mô tả do admin đăng — **không** chứa thông tin cá nhân của khách commission | P0 |
| T18 | DoS | Cloudflare proxy trước domain (tuỳ chọn); rate limit ở Caddy/app | P1 |

## 3. Header bảo mật (Caddy)
`Strict-Transport-Security`, `Content-Security-Policy` (chỉ cho phép script từ chính domain; ảnh từ `cdn.`), `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`, `Permissions-Policy` tắt camera/mic/vị trí, `frame-ancestors 'none'`.

## 4. Xử lý lỗi & logging
- Backend: 1 `@RestControllerAdvice` chuyển mọi exception thành Problem Details (API_SPEC §1). Lỗi nghiệp vụ → 4xx với `code` rõ ràng; lỗi bất ngờ → 500 chung chung + log đầy đủ stack trace kèm `requestId`.
- Lỗi vi phạm constraint DB (vd hết hàng, trùng invoice) được dịch sang `code` nghiệp vụ theo tên constraint.
- Frontend: error boundary hiển thị thông báo thân thiện + `requestId` để khách gửi cho shop.
- Log JSON: `timestamp, level, requestId, logger, message, orderCode?` — không có dữ liệu cá nhân.
- **Audit log** (trong DB) tách biệt với log ứng dụng: ghi mọi thao tác admin và mọi chuyển trạng thái.

## 5. Checklist bảo mật trước launch (rút gọn — chi tiết ở TESTING/launch checklist Phase 4)
- [ ] Đăng nhập admin chỉ được khi có TOTP; thử khoá tạm hoạt động
- [ ] Không có secret nào trong git history
- [ ] Port 5432 không truy cập được từ Internet
- [ ] Header bảo mật có mặt (kiểm bằng securityheaders.com)
- [ ] Link đơn sai → 404; tra cứu bằng mã + email không hiện dữ liệu
- [ ] Upload file `.php` đổi tên thành `.jpg` bị từ chối
- [ ] SPF/DKIM/DMARC đạt (kiểm bằng mail-tester)
- [ ] Đã khôi phục thử 1 bản backup thành công
