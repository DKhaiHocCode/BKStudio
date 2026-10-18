# Session Handoff

> Cập nhật: 2026-10-18

## Đang ở đâu
**Lập kế hoạch hoàn tất (Phase 1–5).** Chưa có code. Task tiếp theo: **T-001**.

## Đã làm
- Phase 1–3: yêu cầu v1.1, 10 ADR, thiết kế DB (chạy thử PostgreSQL 16, 17 tình huống đạt), workflow, API, security, deployment; commission qua sản phẩm UNLISTED.
- Phase 4: 40 task (`tasks.py` → `TASKS.md`), `ROADMAP.md`. **Đã chọn phương án A**: giữ phạm vi, 10 giờ/tuần, ~197 giờ kể cả dự phòng, launch ~đầu 3/2027.
- Phase 5: `DEVELOPMENT.md` (git, quy ước code, quy trình task, DoD, báo lỗi), `TESTING.md`, `CHANGELOG.md`, template PR/bug, `CLAUDE.md` đầy đủ.

## Đang chặn
- Không có gì chặn T-001.
- (Trước T-032) O-2 nội dung điều khoản; O-5 link mạng xã hội.
- (Trước T-043) O-4 chọn VPS + tên miền, duyệt chi phí.
- (Trước launch) O-1 ToS Artistree.

## Việc tiếp theo
1. Cài môi trường máy (DEVELOPMENT.md §1): JDK 21, Node LTS, Docker Desktop (WSL2), Git Bash.
2. Bật bảo vệ nhánh `main` trên GitHub (PR + CI xanh).
3. **T-001** Repo structure & local Postgres (3 giờ) → T-002 → T-003…
Mở phiên mới: *"Đọc SESSION_HANDOFF và bắt đầu T-001."*
