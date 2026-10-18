# ADR-0010: Vận hành — log, giám sát, backup, vận chuyển

- Trạng thái: Accepted
- Ngày: 2026-10-18

## Quyết định
**Logging**
- Log dạng JSON ra stdout, Docker giữ log có xoay vòng (giới hạn dung lượng).
- Mỗi request có `requestId`; **không log** email, địa chỉ, mật khẩu, token.

**Giám sát**
- Endpoint `/actuator/health` (chỉ nội bộ) + 1 endpoint health công khai tối giản.
- Dịch vụ uptime miễn phí (UptimeRobot hoặc Better Stack) ping mỗi 5 phút, báo qua email.
- P1: Sentry free tier để bắt lỗi backend/frontend.

**Backup**
- `pg_dump` hằng đêm → mã hoá → upload lên **bucket R2 riêng** (không public), giữ 14 bản ngày + 8 bản tuần.
- Bật backup/snapshot của VPS (~20% giá server).
- **Diễn tập khôi phục ít nhất 1 lần trước launch** (điều kiện launch).

**Vận chuyển**
- Không tích hợp API hãng ship ở MVP. Admin nhập `carrier` + `tracking_number`; hệ thống tạo link tra cứu (nếu biết mẫu URL của hãng) và email cho khách.

**Bảo mật hạ tầng**
- SSH chỉ bằng key, tắt đăng nhập root bằng mật khẩu, firewall chỉ mở 22/80/443, cập nhật bảo mật tự động.
- Secret nằm trong file `.env` trên VPS (quyền 600) và GitHub Actions secrets; **không bao giờ commit**.

## Hệ quả
- ✅ Chi phí gần $0 ngoài phần backup VPS.
- ⚠️ Không có hệ thống log tập trung — chấp nhận ở quy mô này.
