# API Spec — BKStudio

> Trạng thái: **Đã duyệt (Phase 3)** · Cập nhật: 2026-10-18
> Phase 4 sẽ sinh tài liệu OpenAPI tự động từ code (springdoc); file này là hợp đồng thiết kế.

## 1. Quy ước chung
| Mục | Quy ước |
|---|---|
| Base path | `/api` (cùng domain với frontend — ADR-0001) |
| Định dạng | JSON, tên trường `camelCase` |
| Tiền | Số nguyên cent: `"priceCents": 25000` = $250.00 |
| Thời gian | ISO-8601 UTC: `"dueAt": "2026-11-20T08:00:00Z"` |
| Phân trang | `?page=0&size=20` → `{ "items": [...], "page": 0, "size": 20, "total": 57 }` |
| Lỗi | **RFC 9457 Problem Details** (`ProblemDetail` có sẵn trong Spring) |
| Xác thực admin | Cookie session + header `X-XSRF-TOKEN` cho mọi request thay đổi dữ liệu |
| Request ID | Mọi response có header `X-Request-Id` (in kèm trong lỗi để tra log). Server sinh UUID v4 bỏ dấu gạch (32 ký tự hex); chấp nhận id do client gửi nếu khớp `[A-Za-z0-9_-]{8,64}` |

### Mẫu lỗi
```json
{
  "type": "https://bkstudio.xxx/errors/out-of-stock",
  "title": "Out of stock",
  "status": 409,
  "code": "OUT_OF_STOCK",
  "detail": "Pneuma 1/7 has only 0 ready units left.",
  "requestId": "3f2b8c1e9a4d4c6f8e7a1b2c3d4e5f60"
}
```
| `code` | HTTP | Khi nào |
|---|---|---|
| `VALIDATION_FAILED` | 400 | Dữ liệu sai (kèm `errors: [{field, message}]`) |
| `BAD_REQUEST` | 4xx | Lỗi client không có mã riêng (405, 415, …) — dự phòng do Spring MVC sinh ra |
| `UNAUTHENTICATED` | 401 | Chưa đăng nhập admin |
| `FORBIDDEN` | 403 | Thiếu quyền / sai CSRF |
| `NOT_FOUND` | 404 | Không có tài nguyên (kể cả token đơn sai — không tiết lộ đơn có tồn tại hay không) |
| `OUT_OF_STOCK` | 409 | Hết hàng sẵn |
| `INVALID_TRANSITION` | 409 | Chuyển trạng thái không hợp lệ |
| `VERSION_CONFLICT` | 409 | Dữ liệu đã bị sửa ở tab khác — tải lại |
| `TERMS_OUTDATED` | 409 | Khách đồng ý phiên bản điều khoản cũ |
| `RATE_LIMITED` | 429 | Gọi quá nhiều (kèm `Retry-After`) |
| `INTERNAL_ERROR` | 500 | Lỗi không mong muốn — không lộ chi tiết, chỉ `requestId` |

## 2. API công khai (khách)

### Catalog
| Method | Path | Mô tả |
|---|---|---|
| GET | `/api/products?series=&page=&size=` | Danh sách sản phẩm `ACTIVE` (không bao giờ có `UNLISTED`) |
| GET | `/api/products/{slug}` | Chi tiết + ảnh + biến thể + tình trạng. Trả được cả `ACTIVE` và `UNLISTED` (link trực tiếp); có trường `"unlisted": true` để frontend gắn `noindex` |
| GET | `/api/queue/estimate?qty=1` | Tháng bắt đầu dự kiến cho hàng làm theo đơn |
| GET | `/api/terms/current` | Điều khoản hiện hành `{version, contentMd}` |
| GET | `/api/site-info` | Link mạng xã hội `{reddit, facebook, instagram, x}` cho footer và trang Commission |

`GET /api/products/{slug}` →
```json
{
  "slug": "pneuma", "name": "Pneuma", "series": "Xenoblade Chronicles 2",
  "description": "…", "unlisted": false,
  "images": [{ "url": "https://cdn.bkstudio.xxx/products/9f…/1000.webp", "srcset": {"400": "…", "1000": "…", "2000": "…"}, "alt": "Front view", "width": 2000, "height": 2667 }],
  "variants": [{
    "id": 12, "scale": "1/7", "priceCents": 25000,
    "inStock": { "available": true, "shipsWithinDays": 7 },
    "madeToOrder": { "available": true, "estimatedStartMonth": "2027-01" }   // available=false khi quota đã hết
  }]
}
```
(Không trả số lượng tồn kho chính xác — chỉ có/không.)
`estimatedStartMonth` là `null` cho tới khi module hàng đợi (T-021) điền vào. Chỉ trả biến thể `active`.

`GET /api/products` → mỗi phần tử trong `items`:
```json
{ "slug": "pneuma", "name": "Pneuma", "series": "Xenoblade Chronicles 2",
  "coverImage": { "url": "…", "alt": "Front view", "width": 2000, "height": 2667 },
  "fromPriceCents": 25000, "inStock": true, "madeToOrder": true }
```
`fromPriceCents` = giá thấp nhất trong các biến thể `active`; `coverImage` = ảnh đầu theo `sort_order` (`null` nếu chưa có ảnh). Sản phẩm `ACTIVE` chưa có biến thể `active` nào bị ẩn khỏi danh sách (vẫn mở được bằng slug). `size` mặc định 20, tối đa 50; sắp mới nhất trước.

### Đặt hàng & tra cứu
| Method | Path | Mô tả |
|---|---|---|
| POST | `/api/orders` | Tạo đơn. Header `Idempotency-Key: <uuid>` bắt buộc |
| GET | `/api/orders/{token}` | Xem đơn qua link bí mật |
| POST | `/api/orders/{token}/cancel` | Khách huỷ (theo quy tắc WORKFLOWS §7) |
| POST | `/api/orders/lookup` | `{code, email}` → luôn trả 202, gửi link qua email nếu khớp |

`POST /api/orders` — request:
```json
{
  "fulfillmentType": "MADE_TO_ORDER",
  "items": [{ "variantId": 12, "quantity": 1 }],
  "customer": { "name": "Alex Tan", "email": "alex@example.com", "phone": "+65…" },
  "shippingAddress": { "line1": "1 Orchard Rd", "line2": null, "city": "Singapore", "region": null, "postalCode": "238823", "country": "SG" },
  "acceptedTermsVersion": "2026-11-01"
}
```
Response `201`:
```json
{ "code": "BK-7F3K9Q", "status": "AWAITING_DEPOSIT", "orderUrl": "https://bkstudio.xxx/orders/Qm9…", "subtotalCents": 25000, "depositCents": 12500 }
```
Rate limit: 5 đơn / IP / giờ. (P1: Cloudflare Turnstile chống bot.)

`GET /api/orders/{token}` → trạng thái, sản phẩm, các `paymentRequests` đang mở (`type, amountCents, paymentUrl, dueAt`), tháng sản xuất, vận đơn, lịch sử trạng thái rút gọn. **Không** trả `admin_note`, IP.

## 3. API admin (`/api/admin/**`, yêu cầu đăng nhập + TOTP)

### Xác thực
| Method | Path | Mô tả |
|---|---|---|
| POST | `/api/admin/auth/login` | `{email, password}` → `200 {totpRequired: true}` (session ở trạng thái "chờ TOTP") |
| POST | `/api/admin/auth/totp` | `{code}` hoặc `{recoveryCode}` → đăng nhập đầy đủ, **đổi session ID** |
| POST | `/api/admin/auth/logout` | Huỷ session |
| GET | `/api/admin/auth/me` | Thông tin admin hiện tại |
Giới hạn: 5 lần sai / 15 phút / tài khoản và / IP → khoá tạm.

### Sản phẩm
| Method | Path | Mô tả |
|---|---|---|
| GET | `/api/admin/products?status=&q=` | Danh sách (mọi trạng thái) |
| POST | `/api/admin/products` | Tạo (mặc định `DRAFT`; `UNLISTED` cho commission → slug tự gắn hậu tố ngẫu nhiên) |
| PUT | `/api/admin/products/{id}` | Sửa (kèm `version`) |
| POST | `/api/admin/products/{id}/variants` | Thêm tỉ lệ |
| PUT | `/api/admin/variants/{id}` | Sửa giá / tồn kho / bật làm theo đơn / quota (kèm `version`) |
| POST | `/api/admin/products/{id}/images` | Upload ảnh (multipart, ≤ 10 MB) |
| PUT | `/api/admin/products/{id}/images/order` | Sắp xếp `[imageId…]` |
| PATCH | `/api/admin/images/{id}` | Sửa alt text |
| DELETE | `/api/admin/images/{id}` | Xoá ảnh |

### Đơn hàng — mỗi hành động là 1 endpoint riêng (dễ kiểm tra quyền & điều kiện)
| Method | Path | Body | Chuyển trạng thái |
|---|---|---|---|
| GET | `/api/admin/orders?status=&q=&page=` | — | — |
| GET | `/api/admin/orders/{id}` | — | (kèm `allowedActions` cho UI) |
| POST | `/api/admin/orders/{id}/payment-requests` | `{type, amountCents, shippingCents?, channel, paymentUrl}` | — (gửi email link) |
| POST | `/api/admin/payment-requests/{id}/confirm` | `{externalRef}` | `→ QUEUED` / `PAID_IN_FULL` |
| POST | `/api/admin/payment-requests/{id}/cancel` | `{reason}` | — |
| POST | `/api/admin/orders/{id}/start-production` | `{version}` | `QUEUED → IN_PRODUCTION` |
| POST | `/api/admin/orders/{id}/mark-finished` | `{version, photoImageIds?}` | `→ AWAITING_BALANCE` (kèm tạo yêu cầu BALANCE) |
| POST | `/api/admin/orders/{id}/ship` | `{version, carrier, trackingNumber}` | `PAID_IN_FULL → SHIPPED` |
| POST | `/api/admin/orders/{id}/complete` | `{version}` | `SHIPPED → COMPLETED` |
| POST | `/api/admin/orders/{id}/cancel-by-shop` | `{version, reason, refundRef?}` | `→ CANCELLED / CANCELLED_REFUNDED` |
| POST | `/api/admin/orders/{id}/forfeited-to-stock` | — | `stock_qty + 1` cho biến thể |
| PATCH | `/api/admin/orders/{id}/note` | `{adminNote}` | — |

`confirm` gọi 2 lần → lần 2 trả `200` với trạng thái hiện tại (idempotent), không tạo thay đổi mới.

### Hàng đợi, dashboard, cài đặt, audit
| Method | Path | Mô tả |
|---|---|---|
| GET | `/api/admin/production-months?from=&to=` | Sức chứa / đã dùng theo tháng |
| PUT | `/api/admin/production-months/{yyyy-mm}` | `{capacity}` (≥ đã dùng) |
| GET | `/api/admin/queue?month=` | Danh sách slot của tháng |
| GET | `/api/admin/dashboard` | Số việc cần làm theo nhóm |
| GET/PUT | `/api/admin/settings` | Sức chứa mặc định, tên miền link cho phép, link mạng xã hội… |
| POST | `/api/admin/terms` | Đăng phiên bản điều khoản mới |
| GET | `/api/admin/audit-log?entityType=&entityId=` | Lịch sử thao tác |

## 4. Health
| Method | Path | Mô tả |
|---|---|---|
| GET | `/api/health` | Công khai, chỉ trả `{"status":"UP"}` |
| — | `/actuator/**` | Chỉ trong mạng Docker nội bộ, không public |
