# Browser E2E (tùy chọn)

Chạy extension thật (MV3, CSP, chrome.storage) trong Chromium với backend thật, PostgreSQL thật và Trello giả. AI được thay bằng bộ trả lời theo quy tắc trong `backend/src/test/java/vn/aimtt/e2e/E2eServer.java` (chỉ ở test classpath, không đóng gói).

1. PostgreSQL rỗng riêng (không dùng DB thật), ví dụ cổng 55432, database `ai_mtt`.
2. `python3 tools/fake_trello.py --port 9999 --timeout-sleep 6`
3. Build extension: `cd extension && npm ci && cd .. && node tools/e2e/build_extension.mjs /đường/dẫn/tuyệt/đối/ext`
   Unpacked extension ID = 32 ký tự đầu của sha256(đường dẫn tuyệt đối) đổi 0–f thành a–p; hoặc load một lần trong Chrome để xem ID.
4. Backend: `cd backend && ./mvnw -DskipTests package` rồi `java -Djarmode=tools -jar target/meeting-to-task-0.1.0-SNAPSHOT.jar extract --destination /tmp/app`
   và chạy `java -Duser.timezone=UTC -cp '/tmp/app/meeting-to-task-0.1.0-SNAPSHOT.jar:/tmp/app/lib/*:backend/target/test-classes' vn.aimtt.e2e.E2eServer`
   với biến môi trường như mục "Trello giả" trong `docs/ai-meeting-to-task/Demo_Script.md`, `OPENAI_API_KEY=e2e-fake-key`,
   `TRELLO_TIMEOUT=3s` và `EXTENSION_ORIGIN_ALLOWLIST=chrome-extension://<ID>`.
5. `pip install playwright` (đã có Chromium) rồi `python3 tools/e2e/e2e_flow.py --ext /đường/dẫn/ext --shots e2e-shots` (thêm `--headed` để xem).

Kịch bản: đăng ký → dán transcript → Phân tích → review → OAuth Trello (trang Allow của Trello giả) → chọn Board/List → đối chiếu người phụ trách (Mai = gợi ý, Long = trùng tên) → gợi ý hạn → Tạo 2 card → kiểm tra card trên Trello giả (member, due UTC, mã AI_MTT_REF) → task thủ công + lỗi timeout sau khi tạo → UNKNOWN, không có nút Thử lại → Đối soát → SYNCED, không có card trùng → tải lại panel → xóa meeting (card vẫn còn).
