# ADR-0008: Thanh toán — `PaymentRequest` thủ công, độc lập nền tảng

- Trạng thái: Accepted
- Ngày: 2026-10-18

## Bối cảnh
- PayPal của chủ shop đã bị khoá; Stripe không mở trực tiếp cho người bán tại Việt Nam.
- Artistree đang dùng được (tiền về thẻ MB Bank), nhưng **không tìm thấy API/webhook công khai**.
- Phần còn lại có thể thu qua Shopee + dịch vụ mua hộ.

## Quyết định
- Thực thể **`PaymentRequest`**: `order_id`, `type` (`DEPOSIT`/`BALANCE`/`FULL`), `amount_usd`, `channel` (`ARTISTREE`/`SHOPEE_PROXY`/`OTHER`), `payment_url`, `status` (`PENDING`/`PAID`/`EXPIRED`/`CANCELLED`), `due_at`, `confirmed_at`, `confirmed_by`, `external_ref`, `note`.
- Quy trình: admin tạo link trên nền tảng → dán vào → hệ thống email khách → **admin xác nhận đã nhận tiền** (bắt buộc nhập mã tham chiếu bên ngoài) → máy trạng thái chuyển đơn.
- Website **không bao giờ** nhận hay lưu thông tin thẻ.
- Mọi thao tác xác nhận/huỷ ghi **audit log** (ai, lúc nào, giá trị cũ/mới).
- Thiết kế interface `PaymentChannel` để sau này thêm cổng tự động (webhook) mà không sửa luồng đơn.

## Rủi ro quan trọng ⚠️
Các nguồn bên thứ ba mô tả Artistree là nền tảng cho **commission số**, và một trang review ghi "bán bản gốc có giao hàng vật lý thì không phù hợp". Mình **chưa đọc được điều khoản chính thức** (trang ToS không truy cập được từ đây). Nếu điều khoản cấm hàng vật lý, tài khoản có thể bị khoá → mất kênh thu tiền duy nhất.
**Việc cần làm (chủ shop):** đọc ToS/Acceptable Use của Artistree hoặc hỏi support trước khi launch. Thiết kế này giúp đổi kênh mà không phải sửa code.

## Phương án đã cân nhắc
| Phương án | Vì sao không chọn (cho MVP) |
|---|---|
| Stripe / PayPal tích hợp trực tiếp | Không khả dụng cho chủ shop |
| Cổng nội địa VN (VNPay, MoMo…) | Phục vụ khách nội địa, không phù hợp khách quốc tế trả USD |
| Merchant of Record (Lemon Squeezy, Paddle…) | Chủ yếu cho sản phẩm số/phần mềm |

## Hệ quả
- ✅ Chạy được ngay với tài khoản hiện có; bảo mật đơn giản vì không chạm vào tiền.
- ⚠️ Mỗi đơn cần thao tác tay (ổn với vài chục đơn/tháng).
- ⚠️ Không có bài học webhook/idempotency thật — bù lại bằng idempotency ở thao tác xác nhận của admin (bấm 2 lần không tạo 2 lần thanh toán).
