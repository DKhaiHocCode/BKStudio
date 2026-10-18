# Session Handoff

> Cập nhật: 2026-10-18

## Đang ở đâu
**Phase 2 — Technical decisions: đã viết xong 10 ADR (trạng thái Proposed), chờ chủ dự án duyệt.**

## Đã làm
- Phase 1 hoàn tất: `docs/REQUIREMENTS.md` đã duyệt (v1). Chốt: hàng có sẵn trả 100%, hạn trả nốt 30 ngày (nhắc 7 ngày và 1 ngày trước), hỗ trợ cả Artistree và Shopee + mua hộ.
- Phase 2: kiểm tra giá/chính sách (Vercel Hobby cấm thương mại, R2 free tier, Resend free tier, giá Hetzner, Railway). Viết `docs/ARCHITECTURE.md` + ADR 0001–0010.

## Đang chặn
- Chủ dự án duyệt ADR (đặc biệt 0002 Next.js, 0004 PostgreSQL, 0005 Hetzner).
- Chọn vị trí datacenter (EU hay Singapore) — phụ thuộc khách ở đâu.
- Chủ shop kiểm tra ToS Artistree về hàng vật lý (rủi ro R1).

## Việc tiếp theo
1. Duyệt ADR → đổi trạng thái thành `Accepted`.
2. Bắt đầu **Phase 3 — System design**: ERD + schema, máy trạng thái đơn chi tiết, thiết kế xử lý đồng thời cho slot/kho, API spec, threat model.
