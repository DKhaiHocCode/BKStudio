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
| `CONFLICT` | 409 | Trùng dữ liệu duy nhất (slug do client đặt, tỉ lệ của sản phẩm) hoặc vượt giới hạn số lượng (20 ảnh/sản phẩm) |
| `PAYLOAD_TOO_LARGE` | 413 | Body/file vượt giới hạn (ảnh > 10 MB) |
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
| GET | `/api/orders/{token}/progress` | Tiến độ sản xuất 6 giai đoạn (chỉ MADE_TO_ORDER, T-029) |
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

Chi tiết đã triển khai (T-022):
- Header bắt buộc `Idempotency-Key: <uuid>` (thiếu/sai → `400 VALIDATION_FAILED`) và CSRF như mọi POST. Response thành công có `Cache-Control: no-store`.
- Validate: 1–10 dòng, `quantity` 1–5, không trùng `variantId`; `name` ≤120 (khớp CHECK DB), `email` ≤254, `phone` ≤40, `line1/line2` ≤200, `city/region` ≤100, `postalCode` ≤20; `country` chỉ `SG` hoặc `AU`; từ chối ký tự điều khiển, định dạng (bidi override, zero-width) và dấu ngắt dòng/đoạn Unicode (kể cả NUL, xuống dòng, U+2028) ở mọi trường văn bản, email và `acceptedTermsVersion`. Server chỉ `trim` name/email/địa chỉ khi lưu.
- Giá lấy từ DB. Variant không tồn tại / không active / sản phẩm DRAFT hoặc ARCHIVED → `404 NOT_FOUND`. Hết hàng (IN_STOCK), variant không bật làm theo đơn hoặc hết quota (MADE_TO_ORDER) → `409 OUT_OF_STOCK` (rollback cả đơn). Chưa cấp slot sản xuất ở bước này (T-025).
- `acceptedTermsVersion` phải bằng bản điều khoản mới nhất, nếu không (hoặc chưa có bản nào) → `409 TERMS_OUTDATED`.
- `status`: `AWAITING_PAYMENT` (IN_STOCK, `depositCents` = 0) / `AWAITING_DEPOSIT` (MADE_TO_ORDER, `depositCents` = `(subtotalCents + 1) / 2`). `orderUrl` = `${APP_BASE_URL}/orders/{token}`.
- Idempotency: gửi lại cùng key, cùng `fulfillmentType` và email (không phân biệt hoa thường) → `200` với `{code, status, subtotalCents, depositCents}` và `orderUrl = null` (token chỉ lưu dạng hash nên không khôi phục được). Cùng key nhưng khác `fulfillmentType` hoặc email → `409 CONFLICT`; ngoài hai trường đó nội dung (kể cả `items`) không được so sánh: server trả lại đơn đã lưu. Request song song cùng key được tuần tự hoá, không có 500.
- Rate limit theo IP (`getRemoteAddr`): mặc định 5 đơn/giờ (`app.checkout.rate-limit-max`, `app.checkout.rate-limit-window`); vượt → `429 RATE_LIMITED` + `Retry-After`. Replay không tiêu hao lượt, nhưng IP đã hết lượt bị chặn ngay ở controller (trước khi mở transaction/DB), kể cả với replay. IPv6 được gom theo prefix /64.

`GET /api/orders/{token}` → trạng thái, sản phẩm, các `paymentRequests` đang mở (`type, amountCents, paymentUrl, dueAt`), tháng sản xuất, vận đơn, lịch sử trạng thái rút gọn. **Không** trả `admin_note`, IP.

Chi tiết đã triển khai (T-028, API công khai, không cần đăng nhập; mọi POST vẫn cần CSRF):
- `GET /api/orders/{token}` → `200` (header `Cache-Control: no-store`, `Referrer-Policy: no-referrer`):
  `{publicCode, fulfillmentType, status, subtotalCents, depositCents, shippingCents, items[{productName, variantLabel, quantity, unitPriceCents}], paymentRequests[{type, amountCents, paymentUrl, dueAt}], estimatedProductionMonth, carrier, trackingNumber, history[{status, at}], createdAt, canCancel, cancelWarning}`.
  `depositCents` = 0 với đơn IN_STOCK. `paymentRequests` chỉ gồm yêu cầu `PENDING`. `estimatedProductionMonth` ("YYYY-MM", tháng sớm nhất còn slot `QUEUED`/`IN_PRODUCTION`) chỉ có với MADE_TO_ORDER đã được cấp slot, ngược lại `null`. `carrier`/`trackingNumber` chỉ có khi `SHIPPED`/`COMPLETED`. `history` lấy từ audit `ORDER_TRANSITION` (chỉ trạng thái + thời điểm; đơn mới tạo có `history` rỗng, dùng `createdAt`). `canCancel` = trạng thái `AWAITING_PAYMENT`/`AWAITING_DEPOSIT`/`QUEUED`; `cancelWarning` = `true` khi huỷ sẽ mất cọc (`QUEUED`). **Không** trả: tên/email/SĐT/địa chỉ khách, `admin_note`, `cancel_reason`, IP, hash token, id nội bộ, `refundRef`. Chưa có ảnh bìa sản phẩm (để sau).
  Token sai định dạng hoặc không tồn tại → cùng một `404 NOT_FOUND`. Đơn ở mọi trạng thái đều xem được.
- `POST /api/orders/{token}/cancel`, body `{acknowledgeDepositLost?: boolean}` (có thể bỏ trống) → `200` cùng dạng `GET` (đơn đã huỷ). `AWAITING_PAYMENT`/`AWAITING_DEPOSIT` → `CANCELLED` (trả lại tồn kho/quota/slot, huỷ yêu cầu thanh toán đang mở). `QUEUED` → cần `acknowledgeDepositLost=true`, thiếu → `400 VALIDATION_FAILED` (`errors[].field = acknowledgeDepositLost`); đủ → `CANCELLED_DEPOSIT_KEPT`, nhả slot nhưng **không** trả quota. `IN_PRODUCTION` trở đi hoặc trạng thái khác → `409 INVALID_TRANSITION` ("liên hệ shop"). Phiên bản đơn đọc trong transaction (client không gửi), nên thao tác đồng thời của admin cho kết quả `409`, không mất cập nhật. Thứ tự khoá: `payment_request` rồi `customer_order`.
- `POST /api/orders/lookup`, body `{code, email}` → **luôn** `202 {"message": "..."}` cùng nội dung dù khớp hay không (thiếu trường/JSON hỏng vẫn `400`). Khi khớp (email không phân biệt hoa thường): tạo token mới, thay `access_token_hash`, tăng `version`, xếp email `ORDER_LOOKUP_LINK` (ADR-0012); link cũ ngừng hoạt động. Vượt giới hạn vẫn `202` nhưng không làm gì.
- Giới hạn (trong bộ nhớ, IPv6 gom /64): `GET` + `cancel` 60 lần/phút/IP → `429 RATE_LIMITED` + `Retry-After`; `lookup` 10 lần/giờ/IP; đổi token tối đa 3 lần/giờ/đơn và không đổi hai lần trong 2 phút (request gửi lại không làm hỏng link vừa gửi).
- T-028: có 34+ IT (`CustomerOrder*IT`).

## 3. API admin (`/api/admin/**`, yêu cầu đăng nhập + TOTP)

### Xác thực
| Method | Path | Mô tả |
|---|---|---|
| POST | `/api/admin/auth/login` | `{email, password}` → `200 {totpRequired: true}` (session ở trạng thái "chờ TOTP") |
| POST | `/api/admin/auth/totp` | `{code}` hoặc `{recoveryCode}` → đăng nhập đầy đủ, **đổi session ID** |
| POST | `/api/admin/auth/logout` | Huỷ session |
| GET | `/api/admin/auth/me` | Thông tin admin hiện tại |
Giới hạn: 5 lần sai / 15 phút / tài khoản và / IP → khoá tạm.

Chi tiết đã triển khai (T-012):
- `login` đúng → `200 {"totpRequired": true}`; session ở trạng thái chờ TOTP, **hết hạn sau 5 phút** và chưa được truy cập `/api/admin/**`. `totp` đúng → `200 {"email": "..."}` (cùng dạng với `me`), session ID đổi. `logout` → `204`.
- Sai mật khẩu, email không tồn tại, sai TOTP/mã khôi phục → cùng một `401 UNAUTHENTICATED` ("Invalid credentials."), không cho biết sai ở đâu.
- Giới hạn: **theo IP**, 5 lần sai (mật khẩu, TOTP hoặc mã khôi phục) / 15 phút; **theo tài khoản**, 5 lần sai TOTP/mã khôi phục / 15 phút (chỉ tính khi mật khẩu đã đúng, để người chỉ biết email không khoá được admin). Vượt giới hạn → `429 RATE_LIMITED` kèm header `Retry-After` (giây). Hết thời gian khoá thì bộ đếm của tài khoản về 0.
- Session: hết hạn sau 30 phút không hoạt động hoặc 12 giờ kể từ lúc đăng nhập đủ 2 bước.
- CSRF áp dụng cho **mọi** POST/PUT/PATCH/DELETE (cả endpoint công khai, vd checkout ở T-022): client phải gọi một GET bất kỳ để nhận cookie `XSRF-TOKEN`, rồi gửi giá trị đó trong header `X-XSRF-TOKEN`. Thiếu hoặc sai → `403 FORBIDDEN`.
- `totp` nhận đúng một trong `code` hoặc `recoveryCode` (không phân biệt hoa thường, có hoặc không có dấu gạch).

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
| GET | `/api/admin/products/{id}` | Chi tiết đầy đủ (thêm ở T-013 cho form sửa): mô tả, mọi biến thể kể cả `active=false` với `stockQty`/`madeToOrderQuota`, ảnh |

Chi tiết đã triển khai (T-013):
- **Danh sách:** `page`, `size` (tối đa 50, `page` ≤ 10000), `status`, `q` (chứa trong tên hoặc slug, không phân biệt hoa thường; `%` `_` `\` coi là ký tự thường). Trả `{items, page, size, total}`; mỗi phần tử `{id, slug, name, series, status, version, updatedAt, variantCount, imageCount, coverImage}`, sắp theo `updatedAt` giảm dần.
- **Chi tiết** `{id, slug, name, series, description, status, version, createdAt, updatedAt, variants[], images[]}`; biến thể `{id, scale, priceCents, stockQty, madeToOrderEnabled, madeToOrderQuota, active, version}`; ảnh `{id, sortOrder, altText, urls{400,1000,2000}, width, height}`.
- **Slug:** server tự sinh từ tên (chữ thường, gạch nối), hoặc nhận `slug` hợp lệ từ client (tối đa 80 ký tự khi nhập); trùng slug do client đặt → `409 CONFLICT`, slug tự sinh tự thêm `-2`, `-3`. Sản phẩm `UNLISTED` **luôn** có hậu tố ngẫu nhiên, kể cả khi đổi sang `UNLISTED` lúc sửa (slug được ghi lại). Slug không đổi được ngoài trường hợp đó.
- **Sửa:** `PUT` cần `version` đã đọc; sai → `409 VERSION_CONFLICT`; thành công trả bản mới với `version + 1`. Trạng thái chuyển tự do. `status` phải là chữ (`"ACTIVE"`), số bị từ chối `400`.
- **Biến thể:** không có endpoint xoá, ẩn bằng `active=false`; trùng `scale` trong cùng sản phẩm → `409 CONFLICT`. `scale` không đổi sau khi tạo.
- **Ảnh:** multipart, phần `file` (JPEG/PNG; WebP bị từ chối) và `altText` tuỳ chọn; tối đa 20 ảnh/sản phẩm (ảnh thứ 21 → `409 CONFLICT`); file > 10 MB → `413 PAYLOAD_TOO_LARGE`; tối đa 2 ảnh xử lý cùng lúc, quá thì chờ tối đa 5 giây rồi `429 RATE_LIMITED` kèm `Retry-After`. `PUT .../images/order` nhận `{"imageIds":[...]}` phải đúng bằng tập ảnh hiện có (tối đa 20), nếu không `400`. Xoá ảnh xoá cả 3 file trên storage (lỗi storage không làm lỗi request).
- Mỗi thao tác ghi ghi 1 dòng `audit_log` trong cùng transaction.

### Đơn hàng — mỗi hành động là 1 endpoint riêng (dễ kiểm tra quyền & điều kiện)
| Method | Path | Body | Chuyển trạng thái |
|---|---|---|---|
| GET | `/api/admin/orders?status=&q=&page=` | — | — |
| GET | `/api/admin/orders/{id}` | — | (kèm `allowedActions` cho UI) |
| POST | `/api/admin/orders/{id}/payment-requests` | `{type, amountCents, shippingCents?, channel, paymentUrl}` | — (gửi email link; chi tiết ngay dưới bảng) |
| POST | `/api/admin/payment-requests/{id}/confirm` | `{externalRef}` | `→ QUEUED` / `PAID_IN_FULL` |
| POST | `/api/admin/payment-requests/{id}/cancel` | `{reason}` | — |
| POST | `/api/admin/orders/{id}/start-production` | `{version}` | `QUEUED → IN_PRODUCTION` |
| POST | `/api/admin/orders/{id}/mark-finished` | `{version, photoImageIds?}` | `→ AWAITING_BALANCE` (không tự tạo yêu cầu BALANCE, xem T-026) |
| POST | `/api/admin/orders/{id}/progress/{stage}` | multipart: `note?`, `files?` (≤ 5 ảnh); `stage` là RAW_PRINT, PREP hoặc PAINT | — (duyệt giai đoạn sản xuất, đơn `IN_PRODUCTION`; không đổi `status`/`version`; xem T-029) |
| POST | `/api/admin/orders/{id}/ship` | `{version, carrier, trackingNumber}` | `PAID_IN_FULL → SHIPPED` |
| POST | `/api/admin/orders/{id}/complete` | `{version}` | `SHIPPED → COMPLETED` |
| POST | `/api/admin/orders/{id}/cancel-by-shop` | `{version, reason, refundRef?}` | `→ CANCELLED / CANCELLED_REFUNDED` |
| POST | `/api/admin/orders/{id}/forfeited-to-stock` | `{version}` | `FORFEITED` giữ nguyên; `stock_qty + quantity` cho từng biến thể (1 lần duy nhất) |
| PATCH | `/api/admin/orders/{id}/note` | `{adminNote}` | — |

`confirm` gọi 2 lần → lần 2 trả `200` với trạng thái hiện tại (idempotent), không tạo thay đổi mới.

**Yêu cầu thanh toán (T-025)**
- `POST /api/admin/orders/{id}/payment-requests` → `201 {id, orderId, type, amountCents, channel, paymentUrl, status:"PENDING", dueAt}`. `type` phải khớp trạng thái đơn: `DEPOSIT` (MADE_TO_ORDER, `AWAITING_DEPOSIT`), `FULL` (IN_STOCK, `AWAITING_PAYMENT`), `BALANCE` (MADE_TO_ORDER, `AWAITING_BALANCE`), sai → `409 INVALID_TRANSITION`; đã có yêu cầu `PENDING` cùng loại → `409 CONFLICT`; đơn không có → `404`.
- Server tính số tiền, `amountCents` phải bằng đúng: `DEPOSIT = (subtotal+1)/2`; `FULL = subtotal + shippingCents`; `BALANCE = subtotal − deposit + shippingCents`. Sai → `400 VALIDATION_FAILED`, `errors[].field = "amountCents"`. `shippingCents` (mặc định 0) chỉ > 0 khi `channel = ARTISTREE` và `type` là `FULL`/`BALANCE`; được ghi vào `customer_order.shipping_cents` (version + 1). `dueAt` = +48 giờ (DEPOSIT, FULL) hoặc +30 ngày (BALANCE). Email `ORDER_PAYMENT_LINK` vào outbox cùng transaction.
- `paymentUrl` sai hoặc ngoài `paymentAllowedDomains` → `400`, `errors[].field = "paymentUrl"` (quy tắc: SECURITY.md, T8).
- Tạo yêu cầu **ghi đè** `customer_order.shipping_cents` bằng `shippingCents` của request (kể cả 0). Yêu cầu `PENDING` đã quá `dueAt` vẫn xác nhận được cho tới khi job hết hạn (T-027) đặt `EXPIRED` (chủ đích: tiền thật về muộn vẫn được tính).
- `POST /api/admin/payment-requests/{id}/confirm` → `200 {id, orderId, type, status:"PAID", orderStatus, confirmedAt}`. `externalRef` 1–100 ký tự, không ký tự điều khiển. Gọi lại với cùng `externalRef` → 200 như cũ, không tác dụng phụ; `externalRef` khác, hoặc đã dùng cho yêu cầu khác cùng `channel` → `409 CONFLICT`; yêu cầu `EXPIRED`/`CANCELLED` hoặc đơn không còn ở trạng thái hợp lệ → `409 INVALID_TRANSITION`. `DEPOSIT` cấp slot hàng đợi cho từng order_item trong cùng transaction; hết slot → `409 QUEUE_FULL`, hoàn tác toàn bộ (yêu cầu vẫn `PENDING`). Client không cần gửi `version`.
- `POST /api/admin/payment-requests/{id}/cancel` → `200` trả yêu cầu (`status:"CANCELLED"`). `reason` 1–500 ký tự. Chỉ `PENDING → CANCELLED`; đã `CANCELLED` → 200 idempotent; `PAID`/`EXPIRED` → `409 INVALID_TRANSITION`. Không đổi trạng thái đơn.

**Chi tiết đã triển khai (T-026)**
- `GET /api/admin/orders?status=&q=&page=` → `200 {items:[{id, publicCode, status, fulfillmentType, subtotalCents, shippingCents, version, createdAt}], page, size:20, total}`. `page` bắt đầu từ 0 (ngoài 0..10000 → `400`); `status` sai giá trị → `400`; `q` khớp **chính xác** `publicCode`, không phân biệt hoa thường. Danh sách **không** có email/địa chỉ/tên khách. Mới nhất trước.
- `GET /api/admin/orders/{id}` → `200` gồm `id, publicCode, status, fulfillmentType, version, subtotalCents, shippingCents, currency, customer{name,email,phone,line1,line2,city,region,postalCode,country}` (chỉ admin), `items[{id,variantId,productName,scale,unitPriceCents,quantity}]`, `paymentRequests[]` (cùng dạng `PaymentRequestView`), `carrier, trackingNumber, cancelReason, adminNote, createdAt, updatedAt, shippedAt, completedAt`, `allowedActions` (tính từ bảng chuyển trạng thái: `start-production`, `mark-finished`, `ship`, `complete`, `cancel-by-shop`, `create-payment-request`). Không bao giờ trả token/hash. Không có → `404 NOT_FOUND`.
- Các hành động trả `200 {id, status, version}` (version mới). Body **bắt buộc** `version` (thiếu → `400 VALIDATION_FAILED`); version cũ → `409 VERSION_CONFLICT`; sai trạng thái → `409 INVALID_TRANSITION`; đơn không có → `404`.
  - `start-production {version}`: `QUEUED → IN_PRODUCTION`.
  - `mark-finished {version, photoImageIds?}`: `IN_PRODUCTION → AWAITING_BALANCE`. **Không** tự tạo yêu cầu BALANCE: admin tạo qua `POST /orders/{id}/payment-requests` (`type: BALANCE`). `photoImageIds` **không dùng** (bị bỏ qua; ảnh tiến độ đi qua `POST /orders/{id}/progress/{stage}`, T-029). `mark-finished` không đòi các giai đoạn đã duyệt.
  - `ship {version, carrier, trackingNumber}`: `PAID_IN_FULL → SHIPPED`; `carrier` 1–50, `trackingNumber` 1–100 ký tự, không ký tự điều khiển (sai → `400`, `errors[].field`).
  - `complete {version}`: `SHIPPED → COMPLETED`.
  - `cancel-by-shop {version, reason, refundRef?}`: `reason` 1–500 ký tự. `AWAITING_DEPOSIT`/`AWAITING_PAYMENT → CANCELLED` (`refundRef` bị bỏ qua). `QUEUED`/`IN_PRODUCTION` (làm theo đơn) và `PAID_IN_FULL` (hàng có sẵn) `→ CANCELLED_REFUNDED`, **bắt buộc** `refundRef` 1–100 ký tự (thiếu → `400`, `errors[].field = "refundRef"`). Trạng thái khác (kể cả `PAID_IN_FULL` làm theo đơn) → `409 INVALID_TRANSITION`. `CANCEL_KEEP_DEPOSIT` không dùng ở đây (khách tự huỷ: T-028). Trong cùng 1 transaction: huỷ mọi yêu cầu thanh toán `PENDING` của đơn (khoá `payment_request` trước `customer_order`), chuyển trạng thái, rồi nhả slot hàng đợi của từng item (làm theo đơn; chưa có slot trước khi cọc → bỏ qua). Hoàn kho chỉ khi huỷ **trước khi thanh toán** (`CANCELLED`): hàng có sẵn `stock_qty += quantity`, làm theo đơn `made_to_order_quota += quantity` nếu biến thể có quota. Huỷ kèm hoàn tiền (`CANCELLED_REFUNDED`) **không** tự cộng lại kho/quota (hàng đã trả có thể hỏng, commission không làm được không được mở bán lại): admin tự chỉnh qua `PUT /variants/{id}`. `refundRef` chỉ ghi vào `audit_log` (`ORDER_CANCELLED_BY_SHOP`), không trả ra DTO nào. Audit không lưu văn bản tự do: `after` chứa `reasonLength` (độ dài `reason`) thay vì nội dung; `reason` chỉ vào `customer_order.cancel_reason`. Email `ORDER_CANCELLED` vào outbox.
  - `forfeited-to-stock`: xem khối T-027 dưới.
- `PATCH /api/admin/orders/{id}/note {adminNote}` → `200 {id, adminNote}`; ≤ 2000 ký tự, không ký tự điều khiển (xuống dòng được phép), rỗng = xoá ghi chú. Không cần `version`, không đổi `version` của đơn; ghi audit `ORDER_NOTE_UPDATED` với `after = {noteLength}` (không lưu nội dung ghi chú; xoá ghi chú → `after` rỗng).

**Chi tiết đã triển khai (T-027)**
- `POST /api/admin/orders/{id}/forfeited-to-stock {version}` → `200 {id, status:"FORFEITED", version}` (version + 1). Chỉ đơn `FORFEITED` làm theo đơn, sai → `409 INVALID_TRANSITION`; đã cộng kho rồi (`restocked_at` có giá trị) → `409 CONFLICT` (không cộng 2 lần); `version` cũ → `409 VERSION_CONFLICT`; không có → `404`. Cộng `stock_qty += quantity` cho từng item (biến thể `version + 1`), ghi `customer_order.restocked_at`, audit `ORDER_FORFEITED_TO_STOCK` (`after = {itemCount}`). Chưa có trong `allowedActions` (cần `restocked_at`).
- Job (không phải endpoint, `OrderJobs`, mỗi lô 50, mỗi đơn 1 transaction, tắt bằng `app.jobs.enabled=false`): hết hạn thanh toán (10 phút: `DEPOSIT`/`FULL` `PENDING` quá `due_at` trên đơn `AWAITING_DEPOSIT`/`AWAITING_PAYMENT` → `EXPIRED`, đơn `CANCELLED` bởi `SYSTEM`, trả kho/quota/slot); nhắc trả nốt (1 giờ: `ORDER_REMINDER` mốc 7 ngày/1 ngày, `dedupe_key` `order:{id}:pr:{requestId}:balance-reminder-7d|1d` (theo từng yêu cầu thanh toán, yêu cầu thay thế được nhắc lại), kèm `paymentUrl`; mốc cũ bị bỏ qua nếu mốc 1 ngày đã tới, không nhắc khi quá hạn); mất cọc (1 giờ: `BALANCE` `PENDING` quá `due_at` → `EXPIRED`, đơn `FORFEITED`, email `ORDER_FORFEITED`). Đơn chưa có yêu cầu thanh toán không bao giờ bị hết hạn/mất cọc.

**Chi tiết đã triển khai (T-029, tiến độ sản xuất, chỉ đơn MADE_TO_ORDER)**
- `POST /api/admin/orders/{id}/progress/{stage}` (admin + CSRF), `multipart/form-data`: `note?` (trim, ≤ 500 ký tự, không ký tự điều khiển trừ xuống dòng), `files?` (0–5 ảnh, mỗi ảnh ≤ 10 MB). `stage` ∈ `RAW_PRINT|PREP|PAINT`, giá trị khác → `400 VALIDATION_FAILED` (`errors[].field = stage`); quá 5 ảnh → `400` (`files`). Kiểm tra trước khi xử lý ảnh, theo thứ tự: đơn không có → `404`; giai đoạn đã duyệt → `200` idempotent (không lưu ảnh mới, không email, không audit; vẫn đúng khi đơn đã qua `IN_PRODUCTION`); không phải MADE_TO_ORDER hoặc không ở `IN_PRODUCTION` → `409 INVALID_TRANSITION`; giai đoạn trước chưa duyệt (`RAW_PRINT` → `PREP` → `PAINT`) → `409 INVALID_TRANSITION`.
  Response `200 {stage, approved: true, approvedAt, note, images[{url, width, height}], idempotentReplay}`. `idempotentReplay = true` khi giai đoạn đã được duyệt từ trước (gồm cả khi thua race với một lần duyệt song song).
  Ảnh xử lý ngoài transaction (cùng giới hạn đồng thời như upload ảnh sản phẩm, quá tải → `429`), lưu dưới prefix `progress/{uuid}`. Sau đó 1 transaction ngắn: khoá `customer_order` (`FOR UPDATE`, không khoá `payment_request`), kiểm tra lại trạng thái + giai đoạn trước, `INSERT … ON CONFLICT (order_id, stage) DO NOTHING`, lưu ảnh (`sort_order` 0..n-1), audit `PROGRESS_APPROVED` (`after = {stage, imageCount, noteLength}`, không lưu nội dung note), xếp email `ORDER_PROGRESS` (`dedupe_key` `order:{id}:progress:{STAGE}`). Lỗi → xoá file ảnh vừa lưu. Duyệt giai đoạn **không** đổi `customer_order.version` hay trạng thái.
  `application.yml`: `spring.servlet.multipart.max-request-size` nâng 11MB → 52MB (5 ảnh × 10 MB + trường text); `max-file-size` giữ 10MB.
- `GET /api/orders/{token}/progress` (công khai; `Cache-Control: no-store`, `Referrer-Policy: no-referrer`; dùng chung một bucket 60 lần/phút/IP với `GET /api/orders/{token}`, nên một lần mở trang tính 2 lượt) → `200 {stages: [{stage, label, explanation, state, approvedAt?, note?, images?}]}` đúng 6 phần tử theo thứ tự `AWAITING_APPROVAL`, `DEPOSIT_RECEIVED`, `RAW_PRINT`, `PREP`, `PAINT`, `FINISHED`. `state` ∈ `DONE|CURRENT|PENDING`. Quy tắc (WORKFLOWS §4b): CURRENT chỉ có một giai đoạn: `AWAITING_APPROVAL` khi `AWAITING_DEPOSIT`, `DEPOSIT_RECEIVED` khi `QUEUED`, và khi `IN_PRODUCTION` là giai đoạn đầu tiên chưa duyệt trong `RAW_PRINT`/`PREP`/`PAINT` (đủ 3 giai đoạn đã duyệt mà chưa mark-finished → không có CURRENT); đơn huỷ/`FORFEITED` hoặc đã qua `IN_PRODUCTION` không có CURRENT. `AWAITING_APPROVAL` DONE từ `QUEUED` trở đi; `DEPOSIT_RECEIVED` DONE từ `IN_PRODUCTION` trở đi (và cả đơn huỷ/mất cọc đã qua bước cọc); `RAW_PRINT`/`PREP`/`PAINT` DONE khi đã duyệt **hoặc** khi đơn đã `AWAITING_BALANCE` trở đi (giai đoạn bị bỏ qua vẫn DONE, không có `approvedAt`/`note`/`images`); `FINISHED` DONE từ `AWAITING_BALANCE` trở đi (kể cả `FORFEITED`). `explanation` là câu cố định trong code, viết trung tính để đúng ở mọi trạng thái. `approvedAt`, `note` (luôn là chuỗi, `""` khi không có; giống `note` của response admin) và `images` chỉ có ở giai đoạn đã duyệt, các giai đoạn khác là `null`. `images[].url` là rendition 1000 px. Không trả id, `storage_key`, admin, `admin_note`. Token sai định dạng/không tồn tại/đơn không phải MADE_TO_ORDER → cùng một `404 NOT_FOUND`.
- Email `ORDER_PROGRESS` **không có link** (token gốc không được lưu, ADR-0012): "Stage … of your order … is done. Open the order link from your order confirmation email to see photos." Payload chỉ có `publicCode` và `stage`.

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

Chi tiết đã triển khai (T-020):
- **`PUT /api/admin/settings`** thay toàn bộ cấu hình trong một transaction (có khoá `pg_advisory_xact_lock`, hai PUT đồng thời xếp hàng). Body và response cùng dạng: `{"defaultCapacity": 5, "paymentAllowedDomains": ["artistree.io"], "social": {"reddit": "https://www.reddit.com/u/…", "facebook": null, "instagram": null, "x": null}}`. Cả `defaultCapacity` (1–50, mặc định 5), `paymentAllowedDomains` (tối đa 20 hostname chữ thường, trùng lặp bị loại, chữ hoa bị từ chối) và khối `social` là bắt buộc; bốn link mạng xã hội có thể `null`/rỗng (= xoá). Link phải là `https`, đúng host của nền tảng hoặc tên miền con thật (`x` chấp nhận `x.com`/`twitter.com`), không userinfo, không cổng, ≤ 300 ký tự. Lỗi trả `VALIDATION_FAILED` với `errors[].field` dạng `social.reddit`, `paymentAllowedDomains[1]`, `defaultCapacity`. PUT giống hệt giá trị đang lưu không ghi gì và không tạo audit; thay đổi thật ghi `SETTINGS_UPDATED`.
- **`POST /api/admin/terms`** body `{"version"?: "YYYY-MM-DD" | "YYYY-MM-DD-N", "contentMd": "…"}` (≤ 50.000 ký tự, không chứa NUL); `version` mặc định là ngày UTC hôm nay. Trả `201 {version, contentMd, publishedAt}`. Điều khoản **bất biến** (không có PUT/PATCH/DELETE); trùng `version` → `409 CONFLICT`. Audit `TERMS_PUBLISHED` chỉ lưu `version` + `contentLength`.
- **`GET /api/terms/current`** trả bản có `published_at` mới nhất (hoà thì `version` lớn hơn theo chuỗi); `404 NOT_FOUND` khi chưa đăng bản nào. Hai GET công khai (`/api/site-info`, `/api/terms/current`) có `Cache-Control: public, max-age=60`; `site-info` luôn có đủ 4 khoá, chưa đặt thì `null`.

Chi tiết đã triển khai (T-021):
- Tháng luôn là chuỗi `yyyy-MM` (UTC); sai định dạng → `400 VALIDATION_FAILED`. Cửa sổ xếp slot = tháng hiện tại + 23 tháng.
- **`GET /api/queue/estimate?qty=1`** (công khai, `Cache-Control: public, max-age=60`) → `{"startMonth": "2026-11"}` hoặc `{"startMonth": null}` khi hết chỗ. `qty` 1–5, ngoài khoảng → 400. Chỉ đọc; tháng chưa có dòng được tính theo sức chứa mặc định. Chi tiết sản phẩm (`variants[].madeToOrder.estimatedStartMonth`) dùng cùng số này (qty 1), `null` nếu biến thể không nhận làm theo đơn.
- **`GET /api/admin/production-months?from=&to=`** → `[{"month":"2026-11","capacity":5,"allocated":2}]`. Mặc định `from` = tháng hiện tại, `to` = `from`+11; tối đa 24 tháng, `to` < `from` hoặc quá 24 tháng → 400. Tháng chưa có dòng trả dòng ảo (sức chứa mặc định, `allocated` 0).
- **`PUT /api/admin/production-months/{yyyy-mm}`** body `{"capacity": 0..1000}` → `{month, capacity, allocated}`. Tháng phải ≥ tháng hiện tại (400). Dưới số đã dùng → `409 CAPACITY_BELOW_ALLOCATED` (cập nhật có điều kiện nguyên tử, không cần version). Giá trị trùng hiện tại không ghi gì, không audit; thay đổi ghi `PRODUCTION_CAPACITY_UPDATED` (entityType `PRODUCTION_MONTH`, entityId = `yyyyMM`, after `{month, oldCapacity, newCapacity}`).
- **`GET /api/admin/queue?month=yyyy-MM`** → `{"month":"2026-11","entries":[{id, orderItemId, unitIndex, status, queuedAt}]}` sắp theo `queuedAt, id`.
- Hết chỗ khi xếp slot (nội bộ, dùng ở T-022) → `409 QUEUE_FULL` (cũng trả khi chờ khoá quá 2 giây). `allocate` chỉ gọi **một lần** cho mỗi order item (gọi lần hai đụng `uq_queue_entry_item_unit`; T-022 phải làm xác nhận cọc idempotent). Với `qty` > 1, `estimate` tìm **một** tháng còn đủ `qty` slot, còn `allocate` có thể rải các đơn vị qua nhiều tháng; `estimate` chỉ để hiển thị.

## 4. Health
| Method | Path | Mô tả |
|---|---|---|
| GET | `/api/health` | Công khai, chỉ trả `{"status":"UP"}` |
| — | `/actuator/**` | Chỉ trong mạng Docker nội bộ, không public |
