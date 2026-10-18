# ADR-0009: Email giao dịch — Resend

- Trạng thái: Accepted
- Ngày: 2026-10-18

## Bối cảnh
Không có tài khoản khách → email là kênh duy nhất gửi link thanh toán, trạng thái đơn, nhắc hạn. Email vào spam = mất đơn.

## Quyết định
- **Resend**, gửi từ domain riêng (`orders@bkstudio.xxx`) với **SPF, DKIM, DMARC** cấu hình đầy đủ.
- Free tier (theo nguồn tổng hợp, cần xác nhận trên trang giá chính thức khi đăng ký): **3.000 email/tháng, 100 email/ngày, 1 domain**. Gói trả phí đầu tiên ~$20/tháng.
- Email gửi **bất đồng bộ** qua bảng `outbox` trong DB + job gửi lại khi lỗi → không mất email nếu Resend tạm lỗi, không gửi trùng.

## Phương án đã cân nhắc
| Phương án | Vì sao không chọn |
|---|---|
| Gmail SMTP | Giới hạn thấp, dễ bị chặn, trông không chuyên nghiệp |
| Brevo | Free tier tương đương (khoảng 300 email/ngày) — **là phương án dự phòng tốt** |
| Amazon SES | Rẻ nhất khi lớn, nhưng thủ tục thoát sandbox phức tạp |

## Hệ quả
- ✅ $0 ở quy mô hiện tại (ước tính < 500 email/tháng).
- Gửi qua interface `EmailSender` → đổi nhà cung cấp chỉ cần 1 class mới.
