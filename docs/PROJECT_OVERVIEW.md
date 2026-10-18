# Project Overview — BKStudio

> Trạng thái: **Đã duyệt (v1)** · Cập nhật: 2026-10-18

## 1. Bối cảnh
BKStudio làm figure anime custom: in 3D từ file STL, lắp ráp và sơn tay hoàn thiện. Hiện bán qua Facebook page và nhận cọc qua Artistree. Website riêng là dự án để **học làm web full-stack** đồng thời **bán thật** sau khi launch.

## 2. Mục tiêu
| # | Mục tiêu | Đo bằng |
|---|---|---|
| G1 | Học full-stack qua một sản phẩm thật | Tự hiểu và giải thích được mọi phần của hệ thống |
| G2 | Có website bán hàng chạy thật | Nhận được đơn thật đầu tiên qua website |
| G3 | Giảm việc thủ công khi quản lý đơn / hàng đợi | Toàn bộ đơn và slot được theo dõi trong admin, không cần sổ tay ngoài |

## 3. Ràng buộc
| Ràng buộc | Giá trị |
|---|---|
| Thời gian | ~3 tháng × 10 giờ/tuần ≈ **130 giờ** |
| Nhân sự | 1 người (vừa dev vừa admin) |
| Ngân sách hạ tầng | Mục tiêu ~$5–15/tháng + tên miền (~$10–15/năm) — sẽ kiểm chứng ở Phase 2 |
| Kỹ năng sẵn có | Java, Python, Spring Boot, MySQL, Git, REST API |
| Thanh toán | Không có PayPal (bị khoá). Dùng Artistree (tiền về thẻ Mastercard MB Bank) |
| Tên miền | Chưa có |

## 4. Ngoài phạm vi (MVP)
- Hệ thống commission riêng (tiếp tục dùng Artistree).
- Tài khoản khách hàng, wishlist, review.
- Cổng thanh toán tự động / webhook.
- Tích hợp API vận chuyển, tính phí ship tự động.
- Thị trường Việt Nam, VND, giao diện tiếng Việt.

## 5. Risk register (ban đầu)
| ID | Rủi ro | Khả năng | Tác động | Giảm thiểu | Owner |
|---|---|---|---|---|---|
| R1 | Artistree đổi chính sách / khoá tài khoản / không cho dùng cho hàng vật lý → mất kênh thu tiền duy nhất. Nguồn bên thứ ba mô tả Artistree dành cho commission **số** | **Trung bình–Cao** | Cao | `PaymentRequest` độc lập nền tảng (ADR-0008); **chủ shop đọc ToS / hỏi support Artistree trước launch**; Shopee + mua hộ là kênh dự phòng | Khai |
| R2 | Giấy phép file STL và bản quyền nhân vật → khiếu nại, gỡ listing, ảnh hưởng tài khoản thanh toán | Trung bình | Cao | Chủ dự án tự quyết; ưu tiên STL có giấy phép thương mại | Khai |
| R3 | Scope creep vượt 130 giờ | Cao | Trung bình | P0/P1/P2 chặt; commission ngoài phạm vi | Khai + Claude |
| R4 | Thanh toán thủ công → nhầm trạng thái đơn, quên xác nhận | Trung bình | Trung bình | Máy trạng thái chặt, audit log, dashboard "việc cần làm" | Khai |
| R5 | Tranh chấp / chargeback về cọc không hoàn | Thấp–Trung bình | Trung bình | Khách phải tick đồng ý điều khoản; lưu thời điểm đồng ý; điều khoản hiển thị rõ | Khai |
| R6 | Lộ dữ liệu khách (email, địa chỉ) hoặc chiếm quyền admin | Thấp | Cao | 2FA admin, thu thập tối thiểu, threat model ở Phase 3 | Claude |
| R7 | Mất dữ liệu (DB, ảnh) | Thấp | Cao | Backup tự động + thử khôi phục trước launch | Khai |
| R9 | Hetzner từ chối đăng ký (xác minh danh tính) | Trung bình | Thấp | Dự phòng DigitalOcean / Vultr — cùng Docker Compose | Khai |
| R10 | Tự quản VPS → lỗ hổng do chưa vá / cấu hình sai | Trung bình | Cao | Checklist hardening, cập nhật tự động, chỉ mở 22/80/443 (ADR-0010) | Claude |
| R8 | Hàng vỡ khi ship (đặc biệt qua 2 chặng nếu dùng dịch vụ mua hộ) | Trung bình | Trung bình | Chính sách đóng gói / trách nhiệm ghi rõ trên trang Policy | Khai |
