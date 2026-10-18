# ADR-0004: Database — PostgreSQL + Flyway

- Trạng thái: Accepted
- Ngày: 2026-10-18

## Bối cảnh
Dữ liệu quan hệ rõ ràng (sản phẩm, biến thể, đơn, thanh toán, slot). Yêu cầu toàn vẹn cao: không bán quá số hàng, không trùng slot khi đặt đồng thời. Chủ dự án đã quen MySQL.

## Quyết định
**PostgreSQL** (chạy trong Docker trên VPS), schema quản lý bằng **Flyway migration** (không dùng `ddl-auto=update`).

## Phương án đã cân nhắc
| | PostgreSQL (chọn) | MySQL |
|---|---|---|
| Quen thuộc | Mới, nhưng SQL gần như giống | ✅ Đã quen |
| Ràng buộc toàn vẹn | CHECK, partial unique index, deferrable constraint, `SELECT … FOR UPDATE SKIP LOCKED` | Có phần lớn, nhưng partial index không có |
| Kiểu dữ liệu | `jsonb`, enum, `timestamptz` chuẩn | JSON có nhưng yếu hơn |
| Dịch vụ managed miễn phí (nếu sau này cần) | Nhiều (Neon, Supabase…) | Ít hơn |

Chênh lệch không lớn; chọn PostgreSQL vì ràng buộc toàn vẹn mạnh hơn cho bài toán slot/kho, và đây là kỹ năng đáng học. Chủ dự án đã chọn PostgreSQL (2026-10-18).

## Hệ quả
- ✅ Ràng buộc ở tầng DB là lưới an toàn cuối cùng cho bài toán đồng thời.
- ✅ Flyway: mọi thay đổi schema có version, review được, chạy lại được.
- ⚠️ Phải học vài khác biệt nhỏ so với MySQL (kiểu dữ liệu, `SERIAL`/`IDENTITY`, quoting).
