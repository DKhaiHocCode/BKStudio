# Testing Strategy — BKStudio

> Trạng thái: **Phase 5** · Cập nhật: 2026-10-18

## 1. Nguyên tắc
- Thứ gì liên quan **tiền, trạng thái đơn, kho, slot** phải có test tự động — đây là nơi lỗi gây thiệt hại thật.
- Test chạy trên **PostgreSQL thật** (Testcontainers), không dùng H2 — vì nhiều luật nằm trong constraint của Postgres.
- Thời gian giả bằng `Clock` inject được → test hạn 48 giờ / 30 ngày chạy trong mili-giây.
- Bug sửa xong phải có test chứng minh nó không quay lại.

## 2. Các tầng test
| Tầng | Công cụ | Phạm vi | Ví dụ |
|---|---|---|---|
| Unit | JUnit 5 + AssertJ | Logic thuần, không DB | Bảng chuyển trạng thái (mọi cặp trạng thái × hành động); tính tiền cọc 50%; kiểm tra magic bytes ảnh |
| Integration (backend) | Spring Boot Test + Testcontainers (Postgres, MinIO) + MockMvc | 1 module hoặc 1 API qua HTTP đến DB thật | Checkout trừ kho; 2 luồng song song mua món cuối; confirm 2 lần; job nhắc hạn chạy 2 lần không gửi trùng |
| DB constraints | Testcontainers | Ràng buộc trong `V1__init.sql` | 17 tình huống ở `DATABASE.md` §5 |
| Frontend | ESLint, TypeScript, `next build` | Lỗi kiểu, lỗi build | — |
| E2E | Playwright (Postgres + MinIO bằng compose; xem §8) | Trình duyệt thật, toàn hệ thống | 3 luồng: hàng sẵn, làm theo đơn, commission |
| Thủ công | Checklist | Thứ khó tự động | OG preview, giao diện 375px, email hiển thị trong Gmail |

Không viết unit test frontend cho từng component ở MVP — E2E bao phủ các luồng quan trọng, đổi lại tiết kiệm thời gian.

## 3. Mục tiêu độ phủ
- Mỗi quy tắc ở `REQUIREMENTS.md` §2 có **ít nhất 1 test** (ghi mã quy tắc trong tên/mô tả test).
- JaCoCo: **≥ 80% dòng** cho package `order`, `payment`, `queue`; không đặt mục tiêu % cho phần còn lại.

## 4. Đặt tên & cấu trúc
- Tên test mô tả hành vi: `confirmDeposit_twice_hasSingleEffect()`, `checkout_lastUnit_concurrent_onlyOneSucceeds()`.
- Mẫu Given / When / Then trong thân test.
- Dữ liệu test tạo bằng builder/fixture, mỗi test tự dọn (hoặc chạy trong transaction rollback khi phù hợp).

## 5. Lệnh
| Việc | Lệnh |
|---|---|
| Toàn bộ test backend (gồm kiểm định dạng Spotless) | `./mvnw verify` |
| Tự định dạng code Java khi Spotless báo lỗi | `./mvnw spotless:apply` |
| 1 lớp unit test | `./mvnw -Dtest=GlobalExceptionHandlerTest test` |
| 1 lớp integration test (`*IT`, chạy bằng Failsafe) | `./mvnw -Dit.test=CheckoutIT verify` |
| Frontend | `npm run lint && npm run build` (trong `frontend/`) |
| E2E | `npm run e2e` (trong `e2e/`, cần Postgres + MinIO + backend + frontend đang chạy: xem §8) |
| Báo cáo độ phủ | `backend/target/site/jacoco/index.html` |

## 6. CI chặn merge khi
- Bất kỳ test nào đỏ · lint/format sai · build frontend lỗi · gitleaks phát hiện secret.
- E2E (job `e2e`) chạy trên mọi PR vào `main` và mỗi push lên `main`.

## 7. Tiêu chí phát hành
Xem `ROADMAP.md` §4 (tiêu chí launch). Sau launch, mỗi lần deploy: CI xanh + backup trước nếu có migration + kiểm tra `/api/health` và 1 trang sản phẩm.

## 8. E2E (Playwright, `e2e/`)
3 luồng: hàng sẵn (`in-stock.spec.ts`), làm theo đơn (`made-to-order.spec.ts`), commission quota 1 (`commission.spec.ts`). Khách thao tác qua trình duyệt (sản phẩm → giỏ → checkout → trang `/orders/{token}`); admin thao tác qua API với session đã đăng nhập (một lần, `global-setup.ts`). Mỗi test tự tạo sản phẩm với tên ngẫu nhiên; chỉ dùng chờ theo điều kiện, không `sleep`.

Chạy local (cần DB + MinIO + backend + frontend đang chạy, và một admin đã tạo bằng CLI `--create-admin`):
```
cd e2e && npm ci && npx playwright install chromium
E2E_BASE_URL=http://localhost:3000 E2E_ADMIN_EMAIL=... E2E_ADMIN_PASSWORD=... E2E_TOTP_SECRET=<Base32 do CLI in ra> npm run e2e
```
PowerShell:
```
$env:E2E_BASE_URL="http://localhost:3000"; $env:E2E_ADMIN_EMAIL="..."; $env:E2E_ADMIN_PASSWORD="..."; $env:E2E_TOTP_SECRET="<Base32>"; npm run e2e
```
- Bộ test từ chối chạy nếu host không phải `localhost`/`127.0.0.1` (ghi đè: `E2E_ALLOW_REMOTE=1`, chỉ cho môi trường dùng một lần).
- Artifact của CI (trace, log) chứa token đơn và dữ liệu cá nhân giả của DB tạm trong job; repo công khai nên **không bao giờ** trỏ job E2E vào dữ liệu thật.
- Backend chạy với `--spring.profiles.active=dev` (hoặc `-Dspring-boot.run.profiles=dev`) và đặt `APP_CHECKOUT_RATE_LIMIT_MAX=1000` (chủ yếu cho chạy lặp local cùng IP; mặc định 5 đơn/IP/giờ sẽ chặn). Biến môi trường này gắn vào `app.checkout.rate-limit-max` nhờ relaxed binding của Spring (application.yml không có `${...}` cho khoá này). Không đổi mặc định production.
- Token đơn lấy từ trang "Thank you" của checkout (không log); không đọc từ email/DB.
- CI: job `e2e` trong `.github/workflows/ci.yml` dựng Postgres + MinIO bằng `infra/docker-compose.dev.yml`, sinh secret ngẫu nhiên (đã mask), tạo admin, chạy backend jar + `next start`, rồi chạy Playwright (chromium). Báo cáo/trace chỉ upload khi lỗi.
- Link thanh toán dùng kênh ARTISTREE (kênh trả nốt chưa chốt); không test SHOPEE_PROXY.
