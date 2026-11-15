# ADR-0013: Backup — cảnh báo bằng ping healthchecks.io, khoá R2 theo bucket + bucket lock

- Trạng thái: Accepted (Khai duyệt 2026-11-15)
- Ngày: 2026-11-15
- Quan hệ: **bổ sung** ADR-0010 (ADR-0010 chỉ nói "backup mã hoá lên bucket R2 riêng", không nói cách cảnh báo). **Thay** dòng "Backup thất bại → email cảnh báo (job ghi vào outbox)" trong DEPLOYMENT.md §4 và câu "khoá riêng chỉ có quyền ghi" trong SECURITY.md T17.

## Bối cảnh
- T-044 chạy backup trong container riêng (`infra/backup`: image Postgres + curl + gpg), không phải trong backend. Container này không có logic email/outbox của backend.
- Cảnh báo kiểu "lỗi thì gửi email" chỉ chạy khi script còn chạy được. Container chết, VPS tắt hay lịch không chạy thì không ai biết.
- R2 **không có token chỉ-ghi**: chỉ có Admin R/W, Admin Read, Object R/W, Object Read (tài liệu Cloudflare, kiểm 2026-11-15). Thiết kế cũ "khoá chỉ có quyền ghi" không làm được.

## Quyết định
**1. Cảnh báo**
- Dịch vụ "dead man's switch" kiểu healthchecks.io (gói miễn phí): `backup.sh` gọi `BACKUP_HEALTHCHECK_URL` khi thành công, `BACKUP_HEALTHCHECK_URL/fail` khi lỗi hoặc bị dừng giữa chừng. Dịch vụ email cho Khai khi nhận `/fail` **hoặc quá hạn không nhận ping** (lịch 1 ngày + grace ~2 giờ).
- URL ping là secret (ai có nó giả được ping): chỉ ở `.env`, không log, chỉ `https://`, đưa cho curl qua config trên stdin.
- Không bắt buộc: thiếu thì vẫn backup nhưng log cảnh báo. Checklist launch yêu cầu có.

**2. Khoá R2 (quyết định bảo mật)**
- Khoá trên VPS: token **Object Read & Write, chỉ cho bucket backup**.
- **Bucket lock** theo prefix, bằng thời gian lưu (`daily/` 15 ngày, `weekly/` và `pre-deploy/` 60 ngày): khoá bị lộ cũng không xoá hay ghi đè được backup trong thời gian đó. Lifecycle xoá khi hết hạn; script không bao giờ xoá.
- Khoá đọc (Object Read, chỉ bucket backup) chỉ dùng khi khôi phục, không để trên VPS.
- Khoá VPS đọc được backup: chấp nhận, vì VPS đã có DB thật và passphrase.

## Phương án đã cân nhắc
| Phương án | Vì sao không chọn |
|---|---|
| Ghi lỗi vào outbox của backend | Container backup phải ghi DB và phụ thuộc logic backend; không bắt được container chết |
| Script tự gọi Resend | Thêm secret API email vào container backup; vẫn không bắt được container chết |
| Uptime monitor ping `/api/health` | Chỉ biết site sống, không biết backup chạy |
| Chỉ Object R/W, không bucket lock | Khoá lộ = xoá được hết backup (ransomware) |

## Hệ quả
- ✅ Bắt cả lỗi lẫn "không chạy"; không thêm code backend; $0.
- ✅ Backup gần đây không xoá/ghi đè được kể cả khi VPS bị chiếm.
- ⚠️ Phụ thuộc dịch vụ ngoài (đổi được: dịch vụ nào nhận GET `URL` / `URL/fail` cũng dùng được).
- ⚠️ Dashboard admin không biết "backup gần nhất"; xem trên healthchecks.io.
- ⚠️ Bucket lock chưa kiểm trên R2 thật (checklist SECURITY.md §5).
- Nợ: role DB riêng `pg_read_all_data` cho backup; mã hoá bằng public key (age / gpg `--recipient`) để VPS không giải mã được bản cũ; secret qua file thay vì env; shellcheck/hadolint trong CI và Dependabot ecosystem `docker`.
