# ADR-0003: Backend — Spring Boot (Java 21)

- Trạng thái: Accepted
- Ngày: 2026-10-18

## Bối cảnh
Chủ dự án đã quen Java, Spring Boot, REST API. 130 giờ không đủ để vừa học ngôn ngữ backend mới vừa làm sản phẩm.

## Quyết định
- **Spring Boot** (bản ổn định mới nhất tại thời điểm setup — kiểm tra lại ở task setup), **Java 21 (LTS)**, build bằng **Maven**.
- Thư viện: Spring Web, Spring Data JPA, Spring Security, Spring Validation, Flyway, Testcontainers cho integration test.
- **Modular monolith**: mỗi module 1 package (`catalog`, `order`, `queue`, `payment`, `notification`, `admin`, `shared`), module chỉ gọi nhau qua service công khai.
- Job định kỳ (nhả hàng giữ quá 48h, nhắc hạn trả nốt, chuyển `FORFEITED`): `@Scheduled` trong cùng app.

## Phương án đã cân nhắc
| Phương án | Vì sao không chọn |
|---|---|
| Node.js (NestJS / Next.js API routes) | 1 ngôn ngữ cho cả stack, nhưng phải học lại backend từ đầu; bỏ phí kỹ năng Java |
| Python (Django/FastAPI) | Quen Python nhưng chưa quen framework web Python; Django admin hấp dẫn nhưng không học được nhiều |

## Hệ quả
- ✅ Tận dụng kỹ năng sẵn có, tập trung thời gian vào phần khó (trạng thái đơn, đồng thời, bảo mật).
- ⚠️ Spring Boot dùng ~300–500 MB RAM — cần VPS ≥ 2 GB (chọn 4 GB, ADR-0005).
