# Session Handoff

> Cập nhật: 2026-10-18

## Đang ở đâu
**Phase 3 — System design: ĐÃ DUYỆT** (kèm bổ sung commission: F-15, F-16).

## Đã làm
- Phase 1–2 hoàn tất (REQUIREMENTS v1, ARCHITECTURE, ADR 0001–0010 Accepted).
- Phase 3:
  - `DATABASE.md` + `schema-draft.sql`: schema đã chạy thật trên PostgreSQL 16.15; 14 tình huống (2 khách mua món cuối, cấp slot đồng thời, bấm xác nhận 2 lần, dùng lại invoice…) đều cho kết quả đúng.
  - `WORKFLOWS.md`: máy trạng thái 2 loại đơn, checkout, thanh toán thủ công, hàng đợi slot, nhắc hạn/mất cọc, upload ảnh, job định kỳ.
  - `API_SPEC.md`, `SECURITY.md` (18 mối đe doạ), `DEPLOYMENT.md` (env, deploy, backup, RPO 24h / RTO 4h).
  - Điều chỉnh F-02: 1 biến thể có thể vừa có hàng sẵn vừa làm theo đơn.
  - Q6–Q9 đã duyệt. Thêm commission: trang Commission + link mạng xã hội; admin tạo sản phẩm `UNLISTED` có quota 1 suất, khách đặt theo luồng làm theo đơn (test 15–17 đạt).

## Đang chặn
- (Trước launch) Kiểm tra ToS Artistree; chọn nhà cung cấp VPS; mua tên miền.

## Việc tiếp theo
**Phase 4 — Roadmap & task breakdown**: `ROADMAP.md`, `TASKS.md` (ID, phạm vi, tiêu chí nghiệm thu, test, ước lượng giờ trong ngân sách 130 giờ), dependency graph + critical path. Sau đó Phase 5: quy trình làm việc (git, test, Definition of Done).
