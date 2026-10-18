# Requirements — BKStudio

> Trạng thái: **Đã duyệt (v1.1)** — bổ sung từ Phase 3: F-02, F-15, F-16 (commission), Q6–Q9 · Cập nhật: 2026-10-18
> Ký hiệu: ✅ đã xác nhận · ❓ còn mở

## 1. Sự thật đã xác nhận
- ✅ Bán thật sau khi launch, **chỉ khách quốc tế**, giá **USD**, giao diện **tiếng Anh**.
- ✅ Loại hàng: **hàng có sẵn** và **hàng làm theo đơn**.
- ✅ **Commission** (cập nhật 2026-10-18): khách liên hệ qua mạng xã hội (Reddit, Facebook, Instagram, X); trao đổi ngoài web. Admin tạo **sản phẩm ẩn (UNLISTED)** riêng cho khách đó và gửi link; khách đặt và cọc theo đúng luồng hàng làm theo đơn.
- ✅ Biến thể duy nhất: **tỉ lệ** (vd 1/7, 1/6). Mọi figure đều sơn hoàn thiện.
- ✅ Giới hạn **slot/tháng**, **1 figure = 1 slot**. Hết slot vẫn cho đặt, xếp vào hàng đợi, thời gian chờ dài hơn.
- ✅ Hàng làm theo đơn: **cọc 50%** khi đặt, qua Artistree.
- ✅ Phần còn lại: qua Artistree, hoặc listing Shopee + dịch vụ mua hộ (vd Com&buy). Chưa chốt.
- ✅ **Khách tự trả phí ship.**
- ✅ Không có tài khoản khách. **Admin duy nhất** là chủ shop.

## 2. Quy tắc nghiệp vụ

### 2.1 Thanh toán — mô hình `PaymentRequest`
Mỗi lần thu tiền là một *yêu cầu thanh toán*: số tiền, loại (`DEPOSIT` / `BALANCE` / `FULL`), nền tảng (`ARTISTREE` / `SHOPEE_PROXY` / …), link thanh toán, trạng thái (`PENDING` → `PAID` / `EXPIRED` / `CANCELLED`), hạn thanh toán.
Quy trình: admin tạo link trên nền tảng → dán vào đơn → hệ thống email cho khách → admin xác nhận đã nhận tiền. Không có webhook (Artistree không có API công khai).

### 2.2 Hàng có sẵn
✅ **Trả 100% một lần** (1 `PaymentRequest` loại `FULL`), không cọc.
Lý do: hàng đã có, không có thời gian chờ để cọc phát huy tác dụng; chia 2 lần chỉ làm khách trả 2 lần phí Artistree và thêm việc cho bạn. Code vẫn dùng chung luồng — chỉ khác số `PaymentRequest`.
✅ Khi khách đặt, hàng được **giữ (reserve) 48 giờ** chờ thanh toán; quá hạn thì tự nhả về kho.

### 2.3 Hàng đợi slot (hàng làm theo đơn)
- Admin đặt **sức chứa mỗi tháng** (N figure), sửa được theo từng tháng.
- ✅ Vị trí trong hàng đợi tính theo **thời điểm cọc được xác nhận**, không phải lúc đặt (đơn chưa cọc không giữ slot).
- ✅ Thời gian chờ dự kiến hiển thị cho khách trước khi đặt = tháng mà slot của họ rơi vào, theo dạng "Dự kiến bắt đầu làm: tháng 1/2027".
- Hai khách cùng đặt slot cuối của tháng: hệ thống phải xếp đúng thứ tự, không ai bị trùng slot (bài toán đồng thời — thiết kế ở Phase 3).

### 2.4 Cọc, huỷ và quá hạn
✅ Đã chốt:
1. **Cọc không hoàn lại khi khách huỷ.** Khách phải **tick đồng ý điều khoản** ở checkout; hệ thống lưu thời điểm đồng ý và phiên bản điều khoản.
2. **Ngoại lệ: shop huỷ** (không làm được, hỏng in, ngừng nhận) → **hoàn 100% cọc**. Đây là điều bắt buộc để công bằng và tránh tranh chấp.
3. **Hạn trả nốt: 30 ngày** sau khi báo figure hoàn thiện. Email nhắc **7 ngày** và **1 ngày** trước hạn.
4. Quá hạn → **mất cọc**, đơn chuyển `FORFEITED`, figure có thể chuyển thành **hàng có sẵn** để bán lại.

Lưu ý: khách trả bằng thẻ vẫn có thể mở dispute với ngân hàng. Điều khoản rõ ràng + bằng chứng đồng ý là cách phòng vệ tốt nhất bạn có.

### 2.5 Trạng thái đơn hàng (tóm tắt — bản chi tiết ở `WORKFLOWS.md` §1)
```
Làm theo đơn:
AWAITING_DEPOSIT → QUEUED → IN_PRODUCTION → AWAITING_BALANCE → PAID_IN_FULL → SHIPPED → COMPLETED
Hàng có sẵn:
AWAITING_PAYMENT → PAID_IN_FULL → SHIPPED → COMPLETED

Nhánh phụ:
AWAITING_DEPOSIT / AWAITING_PAYMENT ──(quá hạn / khách huỷ)──▶ CANCELLED
QUEUED / IN_PRODUCTION ──(shop huỷ)──▶ CANCELLED_REFUNDED
QUEUED ──(khách huỷ)──▶ CANCELLED_DEPOSIT_KEPT
AWAITING_BALANCE ──(quá hạn)──▶ FORFEITED
```

### 2.6 Vận chuyển
- Khách tự trả phí ship. ✅ Hệ thống hỗ trợ cả hai kênh thu phần còn lại: (a) Artistree — admin nhập phí ship, cộng vào `BALANCE`/`FULL`; (b) Shopee + mua hộ — bên mua hộ thu ship, phí ship trên đơn = 0.
- Admin nhập **mã vận đơn + hãng** khi gửi; hệ thống email cho khách.

## 3. Yêu cầu chức năng theo ưu tiên

### P0 — bắt buộc để launch
| ID | Yêu cầu |
|---|---|
| F-01 | Catalog: danh sách + trang chi tiết sản phẩm (ảnh, mô tả, nhân vật/series, giá USD theo tỉ lệ) |
| F-02 | Mỗi biến thể tỉ lệ có thể **vừa có hàng sẵn** (số lượng) **vừa nhận làm theo đơn**; khách chọn 1 trong 2 khi thêm vào giỏ. Một đơn chỉ chứa 1 loại *(cập nhật ở Phase 3 — xem DATABASE.md)* |
| F-03 | Hiển thị thời gian chờ dự kiến cho hàng làm theo đơn |
| F-04 | Giỏ hàng + checkout không cần tài khoản (email, tên, địa chỉ giao hàng, đồng ý điều khoản) |
| F-05 | Tạo đơn + `PaymentRequest` tương ứng; giữ hàng có sẵn trong thời hạn thanh toán |
| F-06 | Hàng đợi slot theo tháng, sức chứa admin cấu hình |
| F-07 | Máy trạng thái đơn hàng; chỉ cho phép chuyển trạng thái hợp lệ; ghi audit log |
| F-08 | Email giao dịch: xác nhận đơn, link thanh toán, nhắc hạn, hoàn thiện, đã gửi hàng |
| F-09 | Tra cứu đơn bằng mã đơn + email (hoặc link bí mật trong email) |
| F-10 | Admin: đăng nhập + 2FA |
| F-11 | Admin: CRUD sản phẩm, biến thể, upload ảnh (giới hạn định dạng/dung lượng) |
| F-12 | Admin: quản lý đơn, tạo/xác nhận `PaymentRequest`, nhập vận đơn, xem hàng đợi |
| F-13 | Trang Policy / Terms: cọc không hoàn, thời gian chờ, ship, trách nhiệm hư hỏng |
| F-14 | Giao diện responsive (mobile + desktop) |
| F-15 | **Sản phẩm ẩn (UNLISTED)** cho commission: chỉ vào được bằng link trực tiếp, không hiện trong catalog / tìm kiếm / sitemap, có `noindex`. Giới hạn số suất làm theo đơn (vd 1 suất) để chỉ đúng 1 khách đặt được |
| F-16 | **Trang Commission** + link mạng xã hội (Reddit, Facebook, Instagram, X) ở trang Commission và footer; admin sửa link trong Cài đặt |

### P1 — nên có
| ID | Yêu cầu |
|---|---|
| F-21 | Dashboard admin "việc cần làm" (đơn chờ xác nhận tiền, sắp quá hạn) |
| F-22 | Mã giảm giá đơn giản |
| F-23 | Backup tự động DB + ảnh |

### P2 — sau launch
Cổng thanh toán tự động · hệ thống commission riêng (form yêu cầu, báo giá, duyệt bản nháp trên web) · tích hợp API ship · tài khoản khách · wishlist · đa ngôn ngữ.

## 4. Yêu cầu phi chức năng
| ID | Yêu cầu |
|---|---|
| N-01 | Bảo mật: HTTPS, 2FA admin, validate input, chống OWASP Top 10 cơ bản, secret không nằm trong repo |
| N-02 | Riêng tư: chỉ thu dữ liệu cần thiết (email, tên, địa chỉ giao hàng); có chính sách quyền riêng tư |
| N-03 | Toàn vẹn: không bán quá số hàng có sẵn; không trùng slot khi đặt đồng thời |
| N-04 | Hiệu năng: trang sản phẩm tải nhanh trên mobile; ảnh được tối ưu |
| N-05 | Chi phí vận hành nằm trong ngân sách mục tiêu |
| N-06 | Bảo trì: code có test cho các luồng tiền và trạng thái đơn; tài liệu luôn khớp code |
| N-07 | Khôi phục: backup định kỳ và đã thử khôi phục ít nhất 1 lần trước launch |

## 5. Câu hỏi còn mở
| # | Câu hỏi | Ảnh hưởng | Trạng thái |
|---|---|---|---|
| Q1 | Hàng có sẵn: trả 100% hay cọc 50%? | Số `PaymentRequest` | ✅ 100% |
| Q2 | Hạn trả nốt bao nhiêu ngày? | Job nhắc hạn | ✅ 30 ngày |
| Q3 | Phần còn lại: Artistree hay Shopee + mua hộ? | Cách thu phí ship | ✅ Hỗ trợ cả hai |
| Q4 | Sức chứa slot ban đầu mỗi tháng? | Dữ liệu cấu hình (admin nhập, không ảnh hưởng thiết kế) | ❓ |
| Q5 | ToS Artistree có cho dùng thanh toán hàng vật lý không? | R1 | ❓ Xem ADR-0008 |
| Q6 | Hạn thanh toán link cọc / hàng sẵn: 48 giờ **kể từ lúc gửi link** | Job hết hạn | ✅ |
| Q7 | Một đơn chỉ chứa 1 loại (toàn hàng sẵn hoặc toàn làm theo đơn) | Giỏ hàng, checkout | ✅ |
| Q8 | Ẩn danh thông tin liên hệ/địa chỉ sau 2 năm kể từ khi đơn kết thúc | Privacy, job dọn dữ liệu | ✅ |
| Q9 | Tháng sản xuất hiển thị lúc đặt chỉ là ước tính; tháng chính thức báo khi xác nhận cọc | Nội dung điều khoản, UI | ✅ |
| Q10 | Commission có chiếm slot trong hàng đợi tháng như hàng làm theo đơn không? | Hàng đợi | Mặc định: **có** (1 figure = 1 slot) |
