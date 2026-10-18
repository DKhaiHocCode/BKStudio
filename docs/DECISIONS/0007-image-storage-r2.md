# ADR-0007: Lưu ảnh — Cloudflare R2

- Trạng thái: Proposed
- Ngày: 2026-10-18

## Bối cảnh
~500+ ảnh sản phẩm, chỉ admin upload. Ảnh là phần nặng nhất về băng thông.

## Quyết định
- **Cloudflare R2**, bucket công khai qua subdomain `cdn.bkstudio.xxx`.
- Upload **đi qua backend**: kiểm tra loại file thật (magic bytes, chỉ JPEG/PNG/WebP), giới hạn dung lượng (vd 10 MB), **mã hoá lại ảnh** (xoá metadata EXIF, chặn file giả dạng), tạo vài kích thước (thumbnail, medium, large) dạng WebP, tên file ngẫu nhiên.
- DB chỉ lưu key của ảnh, không lưu file.

## Chi phí (theo trang giá chính thức của Cloudflare)
Free tier mỗi tháng: 10 GB lưu trữ, 1 triệu thao tác ghi (Class A), 10 triệu thao tác đọc (Class B), **egress miễn phí**. 500 ảnh × 3 kích thước × ~300 KB ≈ 0.5 GB → **$0**.

## Phương án đã cân nhắc
| Phương án | Vì sao không chọn |
|---|---|
| Lưu trên ổ VPS | Đơn giản nhưng tốn băng thông VPS, khó backup, mất khi đổi máy |
| AWS S3 | Tính phí egress; phức tạp hơn |
| Cloudinary | Free tier tốt nhưng phụ thuộc thêm; xử lý ảnh tự làm là bài học tốt |
| Upload thẳng lên R2 bằng presigned URL | Giảm tải backend, nhưng khó kiểm tra/mã hoá lại ảnh; không cần với 1 admin |

## Hệ quả
- ✅ $0, nhanh nhờ CDN của Cloudflare.
- R2 tương thích API S3 → dùng AWS SDK for Java; đổi nhà cung cấp dễ.
