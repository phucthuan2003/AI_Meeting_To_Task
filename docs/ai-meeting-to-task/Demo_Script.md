# Kịch bản demo và kiểm thử lỗi (SDS 14.8)

Demo dùng **Trello giả** chạy trên máy (`tools/fake_trello.py`, chỉ cần Python 3) nên không đụng Board thật và có thể bơm lỗi theo ý. AI dùng key thật của bạn (OpenAI/Gemini) như bình thường.

## 1 Khởi động (làm từ đầu, theo thứ tự)

Cần: Docker Desktop đang mở, JDK 17+, Node, Python 3, file `.env` đang dùng (DB, JWT, `EXTENSION_ORIGIN_ALLOWLIST`, key OpenAI/Gemini).

**Bước 0 — một lần duy nhất:** mở `.env` và thêm một dòng `TOKEN_ENCRYPTION_KEY='...'`. Giá trị là kết quả của lệnh `openssl rand -base64 32`. Giữ cố định giá trị này: nếu đổi, các kết nối Trello đã lưu phải kết nối lại.

**Terminal 1 — PostgreSQL + Trello giả** (để mở suốt buổi):

```sh
cd "/Users/phucthuan/phucthuan/Tài Liệu Học Năm 5/Công nghệ UD Doanh Nghiệp/Code"
docker compose up -d postgres
python3 tools/fake_trello.py --port 9999
```

Mở http://127.0.0.1:9999/ để xem Board giả (Demo Board › To Do/Doing). Board có các thành viên:

- Nguyễn Thị Mai
- **Trần Long** và **Phạm Long** (hai người tên Long để demo trùng tên)
- Lê Nam

**Terminal 2 — backend:**

```sh
cd "/Users/phucthuan/phucthuan/Tài Liệu Học Năm 5/Công nghệ UD Doanh Nghiệp/Code"
set -a; . ./.env; set +a
export TRELLO_API_BASE_URL=http://127.0.0.1:9999/1 TRELLO_ALLOW_LOCAL_HTTP=true TRELLO_API_KEY=demo-key
export TRELLO_AUTHORIZE_URL=http://127.0.0.1:9999/authorize TRELLO_TOKEN_URL=http://127.0.0.1:9999/oauth/token
export TRELLO_CLIENT_ID=demo-client TRELLO_CLIENT_SECRET=demo-secret
export TRELLO_CALLBACK_URL=http://127.0.0.1:8080/api/v1/trello/oauth/callback
export TRELLO_TIMEOUT=5s
cd backend && ./mvnw spring-boot:run
```

Kiểm tra backend đã sẵn sàng:

- Trong log phải thấy Flyway migrate lên **v6** (chỉ ở lần đầu).
- Mở http://127.0.0.1:8080/actuator/health, kết quả phải là `UP`.

Các biến `TRELLO_*` trên chỉ có hiệu lực trong terminal này. Mở terminal mới thì quay lại dùng Trello thật.

**Terminal 3 — extension:**

```sh
cd "/Users/phucthuan/phucthuan/Tài Liệu Học Năm 5/Công nghệ UD Doanh Nghiệp/Code/extension"
npm ci
npm run check
```

Sau khi build xong, mở `chrome://extensions` và bấm **Reload** ở extension (vẫn load từ `extension/dist`, nên extension ID không đổi). Kiểm tra version là **0.5.0**. Mở Side Panel, đăng nhập; footer phải hiện "Bản 0.5".

Transcript mẫu (ngày họp 05/10/2026, múi giờ Asia/Ho_Chi_Minh):

```text
Nam: Chào mọi người, bắt đầu họp sprint.
Nam: Mai hoàn thành màn hình đăng nhập trước thứ Sáu nhé.
Nam: Long sửa Payment API nhé.
Mai: Em nhận.
Nam: Payment API đang lỗi timeout.
```

## 2 Ba tình huống demo

**A. Thành công.** Phân tích → khối Trello → **Kết nối Trello (OAuth)** → trang Fake Trello → **Allow** → "Đã kết nối Trello" → chọn Demo Board › To Do → **Đối chiếu người phụ trách** → task Mai: *Xác nhận gợi ý* (Nguyễn Thị Mai) → **Gợi ý hạn từ ngày họp** → *Dùng gợi ý* (Thứ Sáu 09/10/2026 17:00) → **Tạo N card** → xác nhận → "Đã tạo xong" → **Mở card** (trang card của Trello giả hiện mô tả có `AI_MTT_REF=…`).

**B. Thành viên mơ hồ.** Task "Sửa Payment API": cảnh báo *nhiều thành viên Board khớp "Long"*, nút Tạo card bị khóa với lý do cụ thể cho tới khi bạn chọn Trần Long hoặc Phạm Long (hoặc "Không giao người"). Hệ thống không tự chọn.

**C. UNKNOWN có hành động xử lý.** Bơm lỗi "card đã tạo nhưng phản hồi bị treo":

```sh
curl -s -X POST http://127.0.0.1:9999/__admin/fault -H 'Content-Type: application/json' -d '{"mode":"timeout_after_create","count":1}'
```

Thêm một task thủ công (Không giao người, Không đặt hạn) → Tạo 1 card → kết quả **Chưa rõ kết quả** (không có nút Thử lại). Mở http://127.0.0.1:9999/ thấy card đã có. Bấm **Đối soát ngay** (hoặc chờ worker tự đối soát sau ~30 giây) → **Đã tạo**, không sinh card trùng (`/__admin/state` → `createCalls` không tăng thêm).

Các lỗi khác để thử: `server_error_after_create` (502 sau khi tạo → UNKNOWN), `reject` (400 → Lỗi, phải sửa task), `rate_limit` (429 → tự thử lại có giới hạn), `auth` hoặc `POST /__admin/revoke` (token mất quyền → yêu cầu kết nối lại; `POST /__admin/restore-token` để khôi phục). Đặt lại: `POST /__admin/reset`.

## 3 Chế độ token với Trello giả

Thay vì OAuth, dán token demo in ra ở Terminal 1 (`demotoken000…001`) vào ô **Token Trello**. (Link "mở trang cấp token" trỏ tới trello.com thật, không dùng cho Trello giả.)

## 4 Khóa phiên bản demo

Sau khi chạy đủ checklist ở [Full Test Guide](Full_Test_Guide.md), commit và gắn tag, ví dụ `git tag demo-v0.5.0`, để buổi demo dùng đúng mã đã kiểm thử.
