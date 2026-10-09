# Cấu hình Trello (SDS 14.5)

Cập nhật 10/10/2026 · backend Flyway **V5/V6** · extension **0.5.0**.

Backend là nơi duy nhất giữ token Trello (mã hóa AES-256-GCM bằng `TOKEN_ENCRYPTION_KEY`, khóa nằm ngoài DB). Extension chỉ nhận trạng thái kết nối. Có ba cách kết nối:

| Cách | Khi nào dùng | Biến môi trường |
| --- | --- | --- |
| OAuth 2.0 + PKCE (thiết kế chính của SDS) | Có app OAuth 2.0 của Atlassian cho Trello | `TRELLO_CLIENT_ID`, `TRELLO_CLIENT_SECRET`, `TRELLO_CALLBACK_URL` |
| API key + token | Board thử nghiệm, demo nhanh với Trello thật | `TRELLO_API_KEY` |
| Trello giả trên máy | Demo/thử lỗi không đụng Trello thật | xem [Demo Script](Demo_Script.md) |

## 1 Biến bắt buộc

```sh
# 32 byte ngẫu nhiên, base64. Giữ cố định: đổi khóa sẽ làm token đã lưu không giải mã được (phải kết nối lại).
export TOKEN_ENCRYPTION_KEY="$(openssl rand -base64 32)"
```

Đặt vào `.env` cùng các biến DB/JWT như cũ rồi khởi động lại backend. Không commit `.env`. Health vẫn `UP` khi chưa có khóa, nhưng panel sẽ báo chưa thể lưu kết nối.

## 2 Chế độ API key + token (Trello thật)

1. Đăng nhập Trello, mở trang quản trị Power-Up/API key (https://trello.com/power-ups/admin), tạo một integration và lấy **API key**.
2. `export TRELLO_API_KEY="..."` rồi khởi động lại backend.
3. Trên Side Panel → khối **Trello** → bấm *mở trang cấp token Trello* → **Allow** → copy token → dán vào ô **Token Trello** → **Kết nối bằng token**. Token chỉ đi qua backend một lần và được mã hóa; ô nhập được xóa ngay.

Chỉ dùng Board thử nghiệm (SDS 12.1). Token này có quyền đọc/ghi các Board của tài khoản; thu hồi tại trang Trello khi xong.

## 3 Chế độ OAuth 2.0 (Atlassian)

1. Tạo app OAuth 2.0 (3LO) cho Trello trong Atlassian developer console, bật quyền đọc thành viên/Board và ghi card, thêm callback `http://127.0.0.1:8080/api/v1/trello/oauth/callback` (đổi host/port nếu khác; production phải HTTPS).
2. Đặt biến:

```sh
export TRELLO_CLIENT_ID="..."
export TRELLO_CLIENT_SECRET="..."
export TRELLO_CALLBACK_URL="http://127.0.0.1:8080/api/v1/trello/oauth/callback"
# Mặc định: read:member:trello read:board:trello write:board:trello offline_access — đổi theo scope của app nếu khác
# export TRELLO_SCOPES="..."
```

3. Khởi động lại backend. Panel hiện nút **Kết nối Trello (OAuth)** → tab Atlassian mở ra → Allow → tab hiện "Đã kết nối Trello" → quay lại panel (panel tự cập nhật trong vài giây).

Backend tạo `state` ngẫu nhiên + PKCE S256, giao dịch hết hạn sau 10 phút và chỉ dùng một lần; refresh token được làm mới tuần tự và lưu atomically. Nếu refresh thất bại, kết nối chuyển **REAUTH_REQUIRED** và panel yêu cầu kết nối lại; bản nháp review không bị mất. Tài liệu Atlassian dùng `https://auth.atlassian.com/authorize` và `/oauth/token`; nếu tài khoản/app của bạn dùng URL API khác, chỉnh `TRELLO_AUTHORIZE_URL`, `TRELLO_TOKEN_URL`, `TRELLO_API_BASE_URL`. Nếu Atlassian từ chối app, dùng chế độ token ở mục 2.

## 4 Luồng trên Side Panel

1. Kết nối Trello → chọn **Board** và **List** → **Lưu nơi tạo card** (backend kiểm tra List thuộc Board, cả hai đang mở).
2. **Đối chiếu người phụ trách**: tên gần giống chỉ là *gợi ý* (SUGGESTED); nhiều người trùng tên là *AMBIGUOUS*; không bao giờ tự giao. Bấm **Xác nhận gợi ý** hoặc chọn thành viên trong danh sách; tùy chọn *Ghi nhớ tên* lưu alias cho Board này.
3. **Gợi ý hạn từ ngày họp**: diễn giải "ngày mai", "thứ Sáu", "15/10"… dựa trên **ngày họp** (không dùng ngày upload); "trước X" được ghi chú để bạn quyết định. Bấm **Dùng gợi ý** để xác nhận.
4. Chọn task → **Tạo N card trên Trello** → màn hình xác nhận liệt kê Board › List, người được giao, ngày-giờ-múi giờ, có thể **Xem trước mô tả card** → **Xác nhận**.
5. Khối **Kết quả tạo card** hiện từng task: Đã tạo (mở card), Lỗi (Thử lại nếu lỗi tạm thời; lỗi dữ liệu → sửa task rồi tạo lại), **Chưa rõ kết quả** (UNKNOWN: Đối soát ngay / Đã có card – dán link / Tạo lại chỉ sau khi đối soát không thấy, có cảnh báo trùng).

Đổi Board/List bị khóa khi còn card đang tạo hoặc chờ đối soát. Card đã tạo chỉ xem trên Trello (đồng bộ một chiều).

## 5 Biến tùy chọn

| Biến | Mặc định | Ý nghĩa |
| --- | --- | --- |
| `TRELLO_API_BASE_URL` | `https://api.trello.com/1` | REST API |
| `TRELLO_TIMEOUT` | `20s` | Timeout mỗi request Trello; quá hạn sau khi gửi create → UNKNOWN |
| `TRELLO_ALLOW_LOCAL_HTTP` | `false` | Chỉ bật cho Trello giả trên 127.0.0.1 |
| `SYNC_WORKER_ENABLED` | `true` | Worker tạo card |
| `JOB_LEASE_SECONDS` | `60` | Lease worker sync |
