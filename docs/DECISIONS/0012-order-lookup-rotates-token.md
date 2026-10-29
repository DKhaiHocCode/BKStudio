# ADR-0012: Tra cứu đơn gửi link mới bằng cách đổi token

- Trạng thái: Accepted
- Ngày: 2026-10-29

## Bối cảnh
- Khách xem đơn qua link bí mật `/orders/{token}`. DB chỉ lưu `SHA-256(token)` (`customer_order.access_token_hash`), không lưu token gốc (SECURITY.md).
- Khách mất email có thể nhập mã đơn + email vào form tra cứu (`POST /api/orders/lookup`, luôn trả 202) và nhận lại link qua email (WORKFLOWS §7).
- Vì token gốc không còn, hệ thống không thể "gửi lại link cũ".

## Quyết định
- Khi `code` + `email` khớp, `lookup` **tạo token mới**, thay `access_token_hash` trong cùng transaction rồi xếp email `ORDER_LOOKUP_LINK` chứa link mới. Link cũ ngừng hoạt động.
- Giới hạn tần suất: tối đa 3 lần đổi token mỗi giờ cho mỗi đơn, và giới hạn theo IP; vượt giới hạn vẫn trả 202 nhưng không đổi token, không gửi email.
- Phản hồi luôn là `202` cùng nội dung dù khớp hay không, để không dò được mã đơn hay email.

## Phương án đã cân nhắc
| Phương án | Vì sao không chọn |
|---|---|
| Lưu token gốc (hoặc mã hoá) để gửi lại | Lộ link của mọi đơn nếu DB bị rò, trái nguyên tắc chỉ lưu hash |
| Thêm cột token thứ hai riêng cho tra cứu | Cần migration, thêm một bí mật cần bảo vệ và một đường vào đơn |

## Hệ quả
- ✅ Không lưu thêm bí mật, không cần migration.
- ⚠️ Người biết cả mã đơn lẫn email của khách có thể làm link cũ của khách mất hiệu lực; khách lấy lại được bằng tra cứu. Giới hạn 3 lần/giờ/đơn hạn chế việc spam.
- ⚠️ Rủi ro chấp nhận: lookup khớp ghi thêm UPDATE + outbox + audit nên thời gian phản hồi chênh nhẹ so với lookup trượt (CPU băm token như nhau); kẻ tấn công cần cả mã đơn lẫn email, bị giới hạn 10 lần/giờ/IP, và mỗi lần trúng đều gây email ồn cho khách.
- ⚠️ Email cũ chứa link hết hiệu lực; trang 404 của `/orders/{token}` nên gợi ý dùng form tra cứu (T-031).
- Giới hạn tần suất giữ trong bộ nhớ (1 instance), mất khi khởi động lại.
