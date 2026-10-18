# ADR-0011: Java 25 (LTS) thay cho Java 21

- Trạng thái: Accepted
- Ngày: 2026-10-18
- Thay thế: phần phiên bản Java trong ADR-0003 (phần còn lại của ADR-0003 giữ nguyên)

## Bối cảnh
- ADR-0003 chọn Java 21 (LTS). Dự án bắt đầu code từ cuối 2026, khi Java 25 (LTS, ra mắt 9/2025) đã có.
- Máy của chủ dự án đang cài JDK 24 — bản **non-LTS**, đã ngừng nhận bản vá bảo mật từ khi JDK 25 ra mắt.
- Tài liệu chính thức Spring Boot 4.1.1: *"requires at least Java 17 and is compatible with versions up to and including Java 26."*

## Quyết định
- Dùng **Java 25 (LTS)**, bản build **Eclipse Temurin**, cho cả máy dev, CI và image production (`eclipse-temurin:25-jre`).
- `pom.xml` khai báo `<java.version>25</java.version>` → build bằng JDK thấp hơn sẽ báo lỗi ngay.
- Không cần gỡ JDK 24 trên máy; chọn JDK 25 qua `JAVA_HOME` / SDK của IDE cho riêng dự án.

## Phương án đã cân nhắc
| Phương án | Vì sao không chọn |
|---|---|
| Giữ Java 21 (LTS) | Vẫn ổn, nhưng thời gian hỗ trợ còn lại ngắn hơn 25 mà không có lợi ích bù lại |
| Dùng JDK 24 có sẵn | Non-LTS, không còn bản vá bảo mật — không phù hợp cho hệ thống chạy thật |

## Hệ quả
- ✅ Cửa sổ hỗ trợ dài nhất trong các bản LTS hiện có; cùng phiên bản từ máy dev đến production.
- ⚠️ Một số thư viện ít phổ biến có thể chưa kiểm thử với Java 25 — kiểm tra khi thêm dependency (T-002, T-011 thư viện WebP).
