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
| E2E | Playwright + docker compose | Trình duyệt thật, toàn hệ thống | 3 luồng: hàng sẵn, làm theo đơn, commission |
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
| Toàn bộ test backend | `./mvnw verify` |
| 1 lớp unit test | `./mvnw -Dtest=GlobalExceptionHandlerTest test` |
| 1 lớp integration test (`*IT`, chạy bằng Failsafe) | `./mvnw -Dit.test=CheckoutIT verify` |
| Frontend | `npm run lint && npm run build` (trong `frontend/`) |
| E2E | `npm run e2e` (trong `e2e/`, cần `docker compose` đang chạy) |
| Báo cáo độ phủ | `backend/target/site/jacoco/index.html` |

## 6. CI chặn merge khi
- Bất kỳ test nào đỏ · lint/format sai · build frontend lỗi · gitleaks phát hiện secret.
- E2E chạy trên PR vào `main` từ M4 trở đi (trước đó chạy thủ công để CI nhanh).

## 7. Tiêu chí phát hành
Xem `ROADMAP.md` §4 (tiêu chí launch). Sau launch, mỗi lần deploy: CI xanh + backup trước nếu có migration + kiểm tra `/api/health` và 1 trang sản phẩm.
