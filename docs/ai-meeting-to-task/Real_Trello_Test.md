# Test tạo card trên Trello thật (từ đầu)

Làm theo thứ tự này sau mỗi lần mở lại máy hoặc VS Code. Chỉ dùng Board thử nghiệm.

## 0 Chuẩn bị (đã làm thì bỏ qua)

- `.env` có `TRELLO_API_KEY` (lấy ở https://trello.com/power-ups/admin → app → API key) và `TOKEN_ENCRYPTION_KEY` (`openssl rand -base64 32`, giữ cố định).
- Trên trello.com đã có Workspace và Board `AI MTT Test` với List `To Do`.
- Không thêm biến `TRELLO_API_BASE_URL`, `TRELLO_ALLOW_LOCAL_HTTP` hoặc biến của Trello giả vào `.env`.

## 1 Khởi động

Docker Desktop phải đang mở.

Terminal 1, backend (để chạy suốt buổi test):

```sh
cd "/Users/phucthuan/phucthuan/Tài Liệu Học Năm 5/Công nghệ UD Doanh Nghiệp/Code"
docker compose up -d postgres
set -a; . ./.env; set +a
cd backend && ./mvnw spring-boot:run
```

Đợi log hiện `Started ...`. Kiểm tra http://127.0.0.1:8080/actuator/health trả về `UP`.

Terminal 2, extension. Chỉ cần làm khi code extension thay đổi:

```sh
cd "/Users/phucthuan/phucthuan/Tài Liệu Học Năm 5/Công nghệ UD Doanh Nghiệp/Code/extension"
npm ci
npm run check
```

Mở `chrome://extensions`, bấm **Reload** extension, rồi mở Side Panel. Footer phải hiện "Bản 0.5".

## 2 Tạo meeting và phân tích

1. Đăng nhập trên Side Panel.
2. Điền thông tin meeting:
   - **Tiêu đề:** `Sprint test Trello thật`
   - **Ngày họp:** `2026-10-10`
   - **Múi giờ:** `Asia/Ho_Chi_Minh`
3. Dán transcript dưới đây vào ô **Nội dung**. Đổi "Thuận" thành tên hiển thị Trello của bạn nếu khác.

```text
Nam: Chào mọi người, bắt đầu họp sprint tuần này.
Nam: Thuận hoàn thành màn hình đăng nhập trước thứ Sáu nhé.
Thuận: Ok anh, em nhận.
Nam: Thuận viết tài liệu API cho module thanh toán trước ngày 20/10 nhé, việc này ưu tiên cao.
Nam: Ai đó cập nhật file README cho dự án nhé.
Nam: Thuận chuẩn bị slide demo cho ngày mai nhé.
Nam: À không, slide demo để Nam tự làm, Thuận không cần làm.
Nam: Hôm qua server bị chậm nhưng giờ đã ổn rồi.
Nam: Có ai muốn thử dùng Notion thay Trello không? Cứ suy nghĩ thêm.
Nam: Ok, kết thúc họp.
```

4. Bấm **Lưu & xem preview**. Ở mục **Chọn AI**, chọn OpenAI hoặc Gemini, rồi bấm **Phân tích**. Đợi danh sách task hiện ra.

Kết quả mong đợi: khoảng 3–4 task (màn hình đăng nhập, tài liệu API, README, có thể có slide demo giao cho Nam). Không có task cho câu "server chậm" hay "Notion".

## 3 Kết nối Trello

Bỏ qua bước này nếu panel đã hiện "Đã kết nối: …". Kết nối được lưu mã hóa ở backend nên vẫn còn sau khi khởi động lại.

1. Ở khối **Trello**, bấm **mở trang cấp token Trello**, rồi bấm **Allow**.
2. Copy token hiển thị trên trang Trello.
3. Dán token vào ô **Token Trello**, rồi bấm **Kết nối bằng token**. Panel hiện "Đã kết nối: <tên bạn>".

## 4 Chọn nơi tạo card, người phụ trách và hạn

1. Chọn **Board** `AI MTT Test`, **List** `To Do`, rồi bấm **Lưu nơi tạo card**.
2. Bấm **Đối chiếu người phụ trách**.
3. Bấm **Gợi ý hạn từ ngày họp**.
4. Với từng task, bấm **Sửa**:
   - **Người phụ trách:** bấm **Xác nhận gợi ý**, hoặc chọn thành viên trong danh sách, hoặc chọn **Không giao người**.
   - **Hạn:** bấm **Dùng gợi ý**, hoặc chọn **Không đặt hạn**.
5. Kiểm tra hạn của từng task:

   | Task | Hạn |
   | --- | --- |
   | Màn hình đăng nhập | Thứ Sáu, 16/10/2026 lúc 17:00 (Asia/Ho_Chi_Minh) |
   | Tài liệu API | 20/10/2026 lúc 17:00, ưu tiên Cao |
   | README | Không có người phụ trách |

## 5 Tạo card

1. Khi nút **Tạo N card trên Trello** sáng lên, bấm nút đó. Nếu nút còn khóa, đọc lý do hiện ngay dưới nút và sửa task tương ứng.
2. Kiểm tra màn hình xác nhận: Board › List, người được giao, ngày giờ. Bấm **Xem trước mô tả card** nếu muốn xem nội dung.
3. Bấm **Xác nhận tạo N card**.
4. Đợi khối **Kết quả tạo card** hiện "Đã tạo xong", rồi bấm **Mở card**.

## 6 Kiểm tra trên trello.com

Mở Board `AI MTT Test` và kiểm tra từng card:

- [ ] Card nằm trong List `To Do`, tên card đúng với task.
- [ ] Thành viên được gắn đúng người. Card README không có thành viên.
- [ ] Hạn hiển thị đúng giờ Việt Nam: 16/10 17:00 và 20/10 17:00.
- [ ] Mô tả có "Ưu tiên: Cao" (card tài liệu API), thông tin cuộc họp và dòng `AI_MTT_REF=…`.
- [ ] Đóng và mở lại Side Panel: kết quả và nút **Mở card** vẫn còn.
- [ ] Bấm **Tạo** lần nữa trên các task đã tạo: không được (task đã có card), không sinh card trùng.

## 7 Dọn dẹp (tùy chọn)

- Xóa meeting trên panel: các card trên Trello vẫn còn. Muốn xóa card thì archive trực tiếp trên Trello.
- Thu hồi token Trello trong phần cài đặt tài khoản Trello khi không còn test.

Nếu panel báo lỗi, ghi lại thông báo trên panel cùng vài dòng log backend tương ứng (che token/key) để gửi lại.
