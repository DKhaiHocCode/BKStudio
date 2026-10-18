## Task
<!-- vd: T-022 · API checkout -->

## Thay đổi gì & vì sao

## Bằng chứng nghiệm thu
<!-- output test, ảnh chụp màn hình, lệnh kiểm tra -->

## Checklist review
**Đúng**
- [ ] Mọi tiêu chí nghiệm thu trong TASKS.md đạt
- [ ] Trường hợp biên: dữ liệu rỗng, trùng, quá giới hạn, 2 request đồng thời, hết hạn
- [ ] Lỗi trả đúng `code` theo API_SPEC

**An toàn**
- [ ] Validate mọi input; giá/tiền lấy từ DB, không tin client
- [ ] Endpoint admin có kiểm tra quyền; request thay đổi dữ liệu có CSRF
- [ ] Không secret, không log email/địa chỉ/token
- [ ] Không trả entity JPA ra API

**Dữ liệu**
- [ ] Migration mới (không sửa migration cũ), có test
- [ ] Thao tác nhiều bước nằm trong 1 transaction

**Chất lượng**
- [ ] Test mới + toàn bộ test xanh (đã chạy thật)
- [ ] Không cảnh báo lint/format
- [ ] Giao diện kiểm tra ở 375px (nếu có UI)

**Tài liệu (Definition of Done)**
- [ ] Tài liệu liên quan đã cập nhật (DEVELOPMENT.md §9)
- [ ] `tasks.py` status/actual + `--render`; CHANGELOG; SESSION_HANDOFF
