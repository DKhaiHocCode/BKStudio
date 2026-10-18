# Roadmap — BKStudio

> Trạng thái: **Đã duyệt — Phương án A** (giữ phạm vi, 10 giờ/tuần, launch dự kiến ~đầu tháng 3/2027) · Cập nhật: 2026-10-18
> Số giờ lấy từ `docs/tasks.py` (tính bằng script, không ước chừng). Chi tiết từng task: [`TASKS.md`](TASKS.md).

## 1. Sự thật cần nói thẳng
| | Giờ |
|---|---|
| Phạm vi đã duyệt (P0) cần để launch | **164 giờ** |
| Dự phòng 20% (bug, học công nghệ mới, ước lượng sai) | +33 giờ → **~197 giờ** |
| Ngân sách ban đầu (3 tháng × 10 giờ/tuần) | **130 giờ** |
| Thiếu | **~67 giờ** |

Ước lượng đã tính cả thời gian **học** (Next.js, Spring Security, Docker là mới). Claude Code giúp viết code nhanh hơn, nhưng mục tiêu số 1 là *bạn hiểu được hệ thống* (G1) — nên không nên trông vào việc AI làm hộ để kéo con số xuống.

Với 130 giờ (trừ dự phòng còn ~108 giờ) chỉ đủ xong **M0 + M1 + M2 (103 giờ)** — tức backend gần hoàn chỉnh nhưng **chưa có giao diện đặt hàng và chưa deploy**. Không launch được.

## 2. Các phương án
| Phương án | Giờ/tuần | Thời gian | Launch dự kiến | Ghi chú |
|---|---|---|---|---|
| **A. Giữ phạm vi, kéo dài thời gian** | 10 | ~20 tuần | **~đầu tháng 3/2027** | Rủi ro thấp nhất. M3 rơi vào dịp Tết (đầu tháng 2/2027) |
| **B. Giữ phạm vi, tăng giờ** | 15 | ~14 tuần | **~cuối tháng 1/2027** | Phụ thuộc lịch học/thi cuối kỳ |
| **C. Giữ 3 tháng / 10 giờ, cắt phạm vi** | 10 | 13 tuần | ~giữa tháng 1/2027 | Phải cắt ~55 giờ → cắt vào chất lượng (bỏ E2E test, admin UI tối giản, bỏ tự động hoá). **Không khuyến nghị** |

✅ **Đã chọn: A** (2026-10-18). Có thể chuyển sang B bất cứ lúc nào nếu có thêm giờ — phạm vi giữ nguyên, chỉ các mốc dời sớm hơn.

Nếu muốn rút ngắn mà ít đau nhất, các ứng viên cắt (theo thứ tự):
1. T-041 E2E tự động (6h) → thay bằng checklist test tay *(mất lưới an toàn khi sửa code về sau)*.
2. Đơn giản hoá T-016 upload ảnh (bỏ kéo-thả, −3h).
3. Gộp T-035 vào từng task UI (−2h).
4. T-034 bỏ trang hàng đợi riêng, sửa sức chứa trong Cài đặt (−2h).

## 3. Mốc (theo phương án A — 10 giờ/tuần, đã gồm dự phòng 20%, bắt đầu 19/10/2026)
| Mốc | Nội dung | Giờ (chưa dự phòng) | Xong khoảng | Kết quả kiểm chứng được |
|---|---|---|---|---|
| **M0** Setup | Repo, Postgres local, Spring Boot + Next.js skeleton, Flyway V1 + 17 test DB, CI | 19 | tuần 3 (~8/11) | CI xanh; 17 test DB chạy trên Postgres thật |
| **M1** Catalog & admin | API catalog, pipeline ảnh, đăng nhập admin + TOTP, admin sản phẩm, trang sản phẩm có OG | 42 | tuần 8 (~13/12) | Tạo sản phẩm trong admin → hiện trên web; share link lên mạng xã hội có ảnh |
| **M2** Đơn & thanh toán | Checkout, máy trạng thái, email, payment request, hàng đợi slot, job hết hạn/nhắc hạn | 42 | tuần 13 (~17/1/2027) | Toàn bộ vòng đời đơn chạy được qua API (test tự động) |
| **M3** Giao diện | Giỏ hàng, checkout, trang đơn, Commission/Policy, admin đơn hàng + dashboard, responsive | 31 | tuần 17 (~14/2/2027) | Đi hết vòng đời 1 đơn chỉ bằng giao diện |
| **M4** Launch | Rà soát bảo mật, E2E, Docker production, server + domain, backup + diễn tập khôi phục, deploy | 30 | tuần 20 (~7/3/2027) | Launch checklist §4 đều ✅ |
| M5 | Tự đóng đơn, ẩn danh dữ liệu cũ, nút "Chuyển thành hàng sẵn" | 3 | sau launch | — |
| P1 | Turnstile, Sentry, mã giảm giá, dời slot | 13 | nếu còn thời gian | — |

Phương án B (15 giờ/tuần): M0 ~1/11 · M1 ~22/11 · M2 ~20/12 · M3 ~3/1/2027 · M4 ~24/1/2027.

**Mỗi mốc kết thúc bằng một buổi review**: chạy demo, cập nhật `SESSION_HANDOFF.md`, so giờ thực tế với ước lượng (`python3 docs/tasks.py` in ra % chênh lệch từ trường `actual`) → điều chỉnh các mốc sau. Nếu sau M1 giờ thực tế vượt ước lượng > 30%, ta xem lại phạm vi ngay lúc đó, không đợi đến cuối.

## 4. Tiêu chí launch (tất cả phải ✅)
**Chức năng**
- [ ] Đơn hàng sẵn: đặt → thanh toán → ship → hoàn tất (E2E xanh)
- [ ] Đơn làm theo đơn: cọc → slot → hoàn thiện → trả nốt → ship (E2E xanh)
- [ ] Commission UNLISTED quota 1 (E2E xanh)
- [ ] Hết hạn thanh toán nhả kho/quota; nhắc hạn 7 ngày / 1 ngày; mất cọc sau 30 ngày (test tự động với đồng hồ giả)
- [ ] 1 đơn thật trọn vòng với số tiền nhỏ qua Artistree

**Bảo mật & dữ liệu**
- [ ] Toàn bộ checklist `SECURITY.md` §5
- [ ] Không secret nào trong git history (gitleaks)

**Vận hành**
- [ ] Backup tự động chạy ≥ 3 đêm liên tiếp; **đã khôi phục thử thành công**
- [ ] Uptime monitor báo lỗi được (đã thử tắt backend)
- [ ] Rollback về bản trước đã thử
- [ ] Email: SPF/DKIM/DMARC đạt, mail-tester ≥ 9/10

**Kinh doanh (chủ shop)**
- [ ] O-1: đã xác nhận ToS Artistree (rủi ro R1)
- [ ] O-2: Terms / Policy / Privacy đã đăng
- [ ] Chi phí hạ tầng thực tế nằm trong ngân sách đã duyệt

## 5. Cập nhật risk register
| ID | Rủi ro | Khả năng | Tác động | Giảm thiểu |
|---|---|---|---|---|
| R3 (cập nhật) | Phạm vi vượt thời gian | **Cao — đã xác nhận: 164h vs 130h** | Trung bình | Chọn phương án A/B; review giờ thực tế sau mỗi mốc |
| R11 | Lịch học/thi cuối kỳ và Tết làm gián đoạn | Cao | Trung bình | Dự phòng 20%; mốc được tính theo giờ làm, không theo ngày cố định |
| R12 | Công nghệ mới (Spring Security, Next.js, Docker) tốn thời gian học hơn dự kiến | Trung bình | Trung bình | Các task khó (T-012, T-022) có phần "đọc tài liệu" riêng; hỏi Claude giải thích trước khi code |
