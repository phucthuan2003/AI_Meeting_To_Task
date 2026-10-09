# Hướng dẫn test Review task (SDS 14.4)

Cập nhật 09/10/2026 · backend Flyway **V4** · extension **0.4.0**.

Chặng 14.4 biến đề xuất AI thành **task nháp có version** lưu ở backend. Trên Side Panel bạn sửa task (tự lưu), thêm task thủ công, loại bỏ/khôi phục, xem bằng chứng và cảnh báo. Nút **Tạo N card trên Trello** đã có nhưng luôn bị khóa cho tới chặng 14.5 (kết nối Trello).

## 1 Áp dụng bản mới

1. Dừng backend đang chạy (Ctrl+C), nạp lại `.env` như thường lệ rồi chạy backend. Flyway tự áp dụng `V4__review_tasks.sql`: tạo bảng `tasks`, `task_evidence` và **tự chuyển các kết quả AI đã COMPLETED trước đây thành task nháp** (không mất meeting cũ).
2. Build extension:

   ```sh
   cd extension
   npm ci
   npm run check
   ```

   `npm run check` chạy 55 tests + Vite build + kiểm tra MV3. Manifest trong `dist` phải là **0.4.0**.
3. Ở `chrome://extensions` bấm **Reload** extension đang load từ `extension/dist`, đóng/mở lại Side Panel, đăng nhập.

Nếu health không UP hoặc log báo lỗi migration V4, dừng lại và gửi dòng log lỗi (không gửi key/mật khẩu).

## 2 Checklist trên Side Panel

Dùng transcript mẫu (đặt ngày họp 09/10/2026, múi giờ Asia/Ho_Chi_Minh):

```text
Nam: Mai hoàn thành màn hình đăng nhập trước 17:00 ngày 12/10/2026.
Nam: Long sửa Payment API trước thứ Sáu nhé.
Nam: Payment API đang lỗi timeout.
```

| # | Thao tác | Kết quả mong đợi |
| --- | --- | --- |
| R1 | Lưu input → **Phân tích** → chờ COMPLETED | Khối **Review công việc** hiện các task AI, badge “Đề xuất AI”, checkbox chọn sẵn; câu “Payment API đang lỗi” không thành task riêng |
| R2 | Đọc từng task | Người phụ trách “Mai · chờ đối chiếu thành viên Trello”; hạn “… · cần xác nhận”; cảnh báo màu đỏ cụ thể theo trường; **Bằng chứng từ transcript** mở ra đúng câu gốc |
| R3 | Bấm **Xem vị trí trong nguồn** | Hiện segment và locator (dòng/paragraph) |
| R4 | **Sửa** task Mai → **Dùng đề xuất AI** cho hạn | Dòng “Hạn sẽ dùng: Thứ Hai, 12/10/2026 lúc 17:00 (Asia/Ho_Chi_Minh)”; badge “Chưa lưu” → “Đang lưu…” rồi biến mất; cảnh báo hạn mất |
| R5 | Chọn **Không giao người** | Cảnh báo người phụ trách mất; tiêu đề khối giảm số “cần xử lý”; badge **Đã sửa** |
| R6 | Task Long: chọn **Đặt ngày giờ**, chỉ chọn ngày | Giờ mặc định 17:00, có múi giờ; lưu tự động |
| R7 | Đặt hạn trước ngày họp (vd 01/10/2026) | Cảnh báo vàng “Hạn đứng trước ngày họp” — không tự sửa ngày |
| R8 | Đóng panel ngay sau khi sửa (đợi ~1 giây) rồi mở lại | Meeting và mọi chỉnh sửa đã lưu vẫn còn (lấy từ backend) |
| R9 | **Loại bỏ** một task | Task chuyển vào mục “Đã loại bỏ (1)”; mở mục → **Khôi phục** đưa về danh sách |
| R10 | **+ Thêm task thủ công** chỉ nhập tên | Task badge “Thủ công”, không có bằng chứng AI; cảnh báo hạn/người cho tới khi bạn quyết định |
| R11 | Mở **cùng meeting ở 2 cửa sổ Chrome**; sửa tên task ở cửa sổ A, đợi lưu; sửa tên cùng task ở B | B hiện “Xung đột phiên bản”, liệt kê giá trị trên server; không mất chữ bạn gõ. **Giữ thay đổi của tôi và lưu** ghi đè có chủ đích; **Bỏ thay đổi của tôi** tải bản server |
| R12 | Phân tích lại (nút **Phân tích lại**) | Hộp xác nhận cảnh báo đề xuất AI cũ sẽ được thay; task thủ công giữ nguyên |
| R13 | **Thay input** rồi lưu | Task AI cũ biến khỏi danh sách hiện hành; task thủ công vẫn còn |
| R14 | Đăng xuất → đăng nhập lại | Mở lại đúng meeting; task nháp đã lưu còn nguyên |
| R15 | **Lịch sử** | Mỗi meeting hiện trạng thái phân tích và “N task chờ duyệt / N đã loại” |
| R16 | Nút **Tạo N card trên Trello** | Luôn bị khóa, liệt kê lý do: thay đổi chưa lưu, cảnh báo chưa xử lý, chưa kết nối Trello (14.5) |
| R17 | Transcript không có việc cần làm | “Không tìm thấy công việc…”, gợi ý thêm task thủ công (AT02) |

Ghi kết quả vào bảng ở mục 4.

## 3 Test API bằng cURL

Giả sử đã có `API=http://127.0.0.1:8080/api/v1`, `TOKEN`, `MEETING_ID` (xem [API Test Guide](API_Test_Guide.md)).

```sh
# Danh sách task hiện hành
curl -sS "$API/meetings/$MEETING_ID/tasks" -H "Authorization: Bearer $TOKEN"

# Sửa theo version (lấy taskId + version từ lệnh trên)
TASK_ID=...; VERSION=1
curl -sS -X PATCH "$API/tasks/$TASK_ID" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"expectedVersion\":$VERSION,\"deadlineDecision\":\"RESOLVED\",\"dueLocal\":\"2026-10-12T17:00\",\"timezone\":\"Asia/Ho_Chi_Minh\",\"memberDecision\":\"NONE_SELECTED\"}"

# Gửi lại đúng version cũ -> 409 STALE_VERSION, details.currentVersion
curl -sS -X PATCH "$API/tasks/$TASK_ID" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"expectedVersion\":$VERSION,\"taskName\":\"Tab B\"}"

# Trường hệ thống -> 400 FIELD_NOT_EDITABLE
curl -sS -X PATCH "$API/tasks/$TASK_ID" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"expectedVersion":2,"reviewStatus":"APPROVED"}'

# Loại bỏ mềm / khôi phục
curl -sS -o /dev/null -w '%{http_code}\n' -X DELETE "$API/tasks/$TASK_ID?expectedVersion=2" -H "Authorization: Bearer $TOKEN"
curl -sS -X POST "$API/tasks/$TASK_ID/restore" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"expectedVersion":3}'

# Task thủ công, gửi lại cùng key không tạo trùng
curl -sS -X POST "$API/meetings/$MEETING_ID/tasks" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: manual-0001-demo' -d '{"taskName":"Gửi biên bản họp","dueLocal":"2026-10-15T17:00"}'

# Bằng chứng + vị trí nguồn
curl -sS "$API/tasks/$TASK_ID/evidence" -H "Authorization: Bearer $TOKEN"
```

Quy tắc PATCH: field **vắng** giữ nguyên, field **null** xóa. `deadlineDecision`: `RESOLVED` (cần `dueLocal` + timezone), `NONE_SELECTED` (không đặt hạn) hoặc `null` (chưa quyết định). `memberDecision`: `NONE_SELECTED` hoặc `null`; chọn thành viên Trello (`trelloMemberId`) trả 422 cho tới chặng 14.5. Giờ không tồn tại do chuyển giờ mùa hè trả 422 `DEADLINE_LOCAL_TIME_INVALID`. Task thuộc lượt phân tích/input cũ trả 409 `TASK_NOT_CURRENT`; task đã loại trả 409 `TASK_REJECTED`. Account khác nhận 404 trên mọi endpoint.

Cảnh báo `blocking=true` (người phụ trách/hạn chưa quyết định) phải xử lý trước khi tạo card ở 14.5; cảnh báo không chặn (hạn đã qua, trước ngày họp, nguồn hết hạn) chỉ để bạn kiểm tra.

## 4 Ghi kết quả

| Case | Actual | Pass/Fail | Ghi chú/traceId |
| --- | --- | --- | --- |
| R1–R17 | Chưa chạy | | |

## 5 Phạm vi đã kiểm chứng và giới hạn

- Agent đã chạy: 103 backend tests trên PostgreSQL 16 thật (gồm 8 case review: version 409, sửa song song 2 luồng, owner 404, trường hệ thống, DST, loại bỏ/khôi phục, task thủ công idempotent, reanalysis/thay input, backfill V4) và 55 extension tests; bundle kiểm tra bằng esbuild và chạy giao diện review trong Chromium headless với API giả (sửa → lưu, xung đột → giữ thay đổi).
- Chưa chạy: Vite build/MV3 check trên máy (môi trường agent không tải được binary Rollup), Chrome thật với backend thật, API AI thật.
- Chỉnh sửa chưa kịp lưu (dưới ~1 giây) có thể mất nếu phiên hết hạn đúng lúc đó; phần đã lưu luôn ở backend.
- Task AI chỉ hiện cho lượt phân tích hiện hành; task cũ vẫn lưu trong DB để đối chiếu nhưng không sửa được.
