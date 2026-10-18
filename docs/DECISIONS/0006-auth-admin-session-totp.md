# ADR-0006: Xác thực — Admin dùng session + TOTP 2FA; khách tra cứu đơn bằng token

- Trạng thái: Proposed
- Ngày: 2026-10-18

## Bối cảnh
Chỉ có 1 tài khoản admin. Khách không có tài khoản nhưng cần xem trạng thái đơn và link thanh toán. Admin nắm toàn bộ dữ liệu khách → là mục tiêu tấn công chính.

## Quyết định
**Admin**
- Spring Security, **session phía server + cookie** (`HttpOnly`, `Secure`, `SameSite=Lax`), không dùng JWT.
- Mật khẩu băm bằng **Argon2id** (hoặc BCrypt).
- **TOTP 2FA bắt buộc** (Google Authenticator / Aegis), kèm mã khôi phục dùng 1 lần.
- Giới hạn số lần đăng nhập sai (rate limit + khoá tạm thời), CSRF token cho mọi request thay đổi dữ liệu.
- Đường dẫn admin không quảng bá; mọi endpoint `/api/admin/**` yêu cầu `ROLE_ADMIN`.

**Khách**
- Mỗi đơn có **token truy cập ngẫu nhiên ≥ 128 bit**, gửi trong link email: `/orders/{token}`.
- Dự phòng: tra cứu bằng mã đơn + email (có rate limit).

## Phương án đã cân nhắc
| Phương án | Vì sao không chọn |
|---|---|
| JWT lưu localStorage | Dễ bị đánh cắp qua XSS; thu hồi phức tạp; không cần vì chung domain |
| Dịch vụ auth bên ngoài (Auth0, Clerk…) | Thừa cho 1 người dùng; thêm phụ thuộc; không học được cách làm auth |
| Magic link qua email cho admin | Email trở thành điểm yếu duy nhất |

## Hệ quả
- ✅ Mô hình đơn giản, an toàn, chuẩn của Spring Security.
- ⚠️ Mất điện thoại TOTP → dùng mã khôi phục (cất offline).
