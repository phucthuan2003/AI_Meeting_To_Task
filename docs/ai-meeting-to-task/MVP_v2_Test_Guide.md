# Chạy và kiểm tra MVP v2 — extension 0.7.0

Cập nhật 28/10/2026. Các chặng đã có code; các checklist dưới đây dành cho xác minh runtime trên máy local. Giữ DB/password/JWT/allowlist và key AI/Trello đang hoạt động.

## 1. Cấu hình STT

Trong `.env` ở Code thêm key thật trên máy, không gửi vào chat:

```dotenv
GROQ_API_KEY='THAY_BANG_KEY_GROQ_CUA_BAN'
STT_MODEL='whisper-large-v3-turbo'
AUDIO_WORKER_ENABLED=true
```

Tạo key trong [Groq Console](https://console.groq.com/keys). Chỉ GROQ_API_KEY có giá trị thì UI mới mở các nút audio; readiness không xác minh key/quota. Request thật có thể tính phí theo tài khoản. API docs: [Groq Speech-to-Text](https://console.groq.com/docs/speech-to-text). OpenAI/Gemini vẫn dùng key/model hiện có cho trích xuất task. Không dùng key Gemini thay cho key Groq.

## 2. Khởi động

Dừng backend cũ bằng Ctrl+C, rồi chạy từng dòng:

```sh
cd '/Users/phucthuan/phucthuan/Tài Liệu Học Năm 5/Công nghệ UD Doanh Nghiệp/Code'
docker compose up -d postgres
set -a
source ./.env
set +a
cd backend
./mvnw spring-boot:run
```

Cần Flyway áp dụng V7 và V8 thành công; không sửa V1, không xóa volume. Backend mới có hai bảng `capture_sessions`/`capture_chunks` và source_type AUDIO. Nếu migration lỗi, giữ log đã che thông tin nhạy cảm để kiểm tra, không xóa schema đang có dữ liệu.

Terminal thứ hai:

```sh
curl -sS http://127.0.0.1:8080/actuator/health
```

Cần status UP. Build extension:

```sh
cd '/Users/phucthuan/phucthuan/Tài Liệu Học Năm 5/Công nghệ UD Doanh Nghiệp/Code/extension'
npm run check
```

Trong chrome://extensions Reload extension từ extension/dist, kiểm tra phiên bản 0.7.0 và quyền mới. Đăng nhập rồi mở nút **Ghi âm / Live**. Nếu không thấy nút, chưa reload đúng bản. Không đổi extension ID khi không cần vì CORS dùng origin đó.

## 3. Test file audio trước

1. Chuẩn bị WAV/MP3/M4A/WebM ngắn 15–30 giây có lời nói rõ; tối đa 10 MiB và một giờ. Dùng dữ liệu giả: “Nam: Thuận viết tài liệu API trước 17 giờ ngày 30 tháng 10 năm 2026. Huy kiểm thử đăng nhập trước 10 giờ ngày 31 tháng 10 năm 2026.”
2. Chọn File ghi âm, nhập title/ngày họp/timezone, chọn file, bấm **Gửi file để chuyển lời nói**.
3. Theo dõi đoạn được xử lý. Transcript có thể sai tên/ngày; mở xem transcript, không lấy kết quả STT làm bản chép hoàn hảo.
4. Tới READY bấm **Chốt & lưu thành meeting để review**. Trong meeting mới, bấm Phân tích, review rồi tạo card như trước.
5. Kiểm tra nguồn có timestamp và locator startMs/endMs. Evidence là văn bản STT đã lưu; bản này không có nút phát lại audio và không lưu lâu dài file audio sau checkpoint.
6. Đóng/mở panel khi job chạy: khôi phục session ID được lưu theo account. Không tự tạo session/meeting mới khi mở lại.
7. File lỗi/giả đuôi phải bị chặn hoặc báo STT_REQUEST_FAILED, không tạo task. Im lặng có thể không có nguồn để publish; hủy phiên nếu không có lời nói.
8. STT_AUTH_ERROR: kiểm tra key Groq/restart; STT_RATE_LIMIT: kiểm tra quota; PROVIDER_* ở live: kiểm tra key/model AI. Khi lỗi, bấm retry chủ động; có thể tốn phí thêm. Không bấm gửi lại liên tục khi chưa biết request cũ thành công hay chưa.

## 4. Test SSE

Trong DevTools của Side Panel, Network lọc `events`:

- Request là GET, Accept text/event-stream, Authorization Bearer; token không nằm URL.
- Snapshot cập nhật sau mỗi checkpoint, có heartbeat nếu state không đổi.
- Khi ngắt mạng, UI báo/reconnect có giới hạn hoặc cho Làm mới; server vẫn giữ job.
- Khi token hết hạn hoặc logout, stream phải dừng cập nhật dữ liệu riêng và yêu cầu đăng nhập lại.
- Với transcript dài: bật CHUNKING_ENABLED trước khi restart và kiểm tra đề xuất tạm từ chunk hoàn tất. Không cho tạo card từ đề xuất tạm.

Luồng snapshot là trạng thái mới nhất, không replay từng event đã bỏ lỡ. SSE này không stream token LLM/NDJSON chưa validate. File ngắn có một lần gọi AI vẫn cần chờ lần đó hoàn tất để thấy task.

## 5. Test thu tab audio và microphone

1. Mở tab Chrome đang phát nội dung họp thử. Bấm biểu tượng extension ngay trên tab đó để cấp quyền thao tác cho tab; mở Side Panel.
2. Vào Ghi âm / Live, chọn Tab cuộc họp trong Chrome. Chọn AI đã cấu hình, nhập metadata.
3. Nếu dùng mic, bấm **Cấp quyền microphone**. Trang riêng chỉ thử quyền rồi dừng; cho phép trên Chrome/macOS. Quay về tab họp, bấm biểu tượng extension nếu cần và mở lại panel.
4. Bấm **Chuẩn bị phiên live**, sau đó **Bắt đầu thu tab đang mở**. Kiểm tra state RECORDING và âm thanh tab vẫn nghe được.
5. Nói/phát lời thử ít nhất 20–30 giây. Sau mỗi đoạn khoảng 15 giây sẽ có upload; tổng thời gian hiện task còn gồm STT/LLM/hàng đợi. Không kỳ vọng task dưới một giây.
6. Đóng panel 20 giây rồi mở lại: offscreen phải tiếp tục thu nếu Chrome và tab còn hoạt động. Xem số đoạn/giây và transcript. Khi Stop hoặc tab kết thúc, tracks phải dừng.
7. Bấm **Dừng thu & gửi đoạn cuối**. Cần STOPPED phía local, DRAINING rồi READY phía backend, xử lý đủ count. Không gọi publish khi còn form sửa chưa lưu.
8. Lỗi quyền/không có audio: thử microphone permission page và tab họp khác hoặc dùng file upload. Không tuyên bố thu system audio của desktop app; bản này chỉ thu tab được người dùng kích hoạt.

## 6. Test sửa/hủy và bảo vệ chỉnh sửa

Phát các câu ở những đoạn audio khác nhau, cách nhau hơn 15 giây:

```text
Nam: Mai hoàn thành màn hình đăng nhập trước 17:00 ngày 30/10/2026.
Nam: Việc màn hình đăng nhập chuyển cho Huy, giữ nguyên hạn.
Nam: Không cần làm màn hình đăng nhập nữa, hủy việc đó.
```

- Kỳ vọng CREATE → UPDATE → CANCEL tác động cùng task_ref, không tạo thêm ba công việc. Đây là đáp án cần xác minh với AI thật, không phải kết quả đã đo.
- Sau CREATE, sửa tên việc trong thẻ live và Lưu. Khi AI UPDATE, bản sửa đã lưu vẫn được ưu tiên khi chốt.
- Mở form rồi chờ snapshot/version thay đổi: lưu từ version cũ trả 409, không ghi đè. Chọn giữ bản sửa với version mới nhất rồi lưu lại nếu phù hợp.
- Nếu AI CANCEL, override không tự làm việc sống lại; UI báo có bản sửa không còn proposal. Sau khi chốt có thể thêm thủ công nếu cần.
- Tới READY, chốt thành meeting: task có PENDING_REVIEW, evidence gắn đúng segment DB, user override được áp dụng; chưa có card Trello tự tạo.
- Review member/hạn và dùng approve-and-sync cũ để tạo card thật trên Board test. Kiểm tra card và không trùng khi bấm lại/publish lại.

## 7. Test lỗi và phục hồi

| Tình huống | Kỳ vọng |
| --- | --- |
| Mất phản hồi khi upload | Không tự retry POST; Gửi lại đoạn đang chờ dùng cùng sequence/checksum |
| Gửi lại cùng sequence khác nội dung | 409, không thay audio cũ |
| Gửi thiếu đoạn/sequence nhảy | Bị từ chối, không âm thầm bỏ nguồn |
| Backend dừng giữa request | Lease hết hạn → FAILED; retry chủ động, checkpoint STT còn thì không gọi lại STT |
| Hết token khi thu | Dừng thu/giữ đoạn chưa upload nếu offscreen còn; đăng nhập lại cùng account rồi gửi lại |
| Backend lỗi khi live còn thu | Lần upload tiếp theo bị chặn và thu dừng; xử lý backend rồi retry đoạn local, cuối cùng Stop/chốt |
| Đóng Chrome/reload extension | Audio local chưa gửi có thể mất. Chốt các đoạn đã nhận chỉ sau khi xác nhận bỏ phần chưa gửi |
| Queue chậm | Dừng khi queue đạt giới hạn, không tăng RAM vô hạn |
| Phần cuối lỗi | Không READY/publish như đầy đủ; retry/hủy hoặc chốt phạm vi đã nhận có xác nhận |
| AI JSON/evidence sai | FAILED, không publish task giả |
| Đổi tài khoản | Không xem/control session backend của account trước; pointer được phân account/backend |
| Cancel | Dừng media local, xóa raw audio/checkpoint/session transcript tạm; không tạo meeting/card |
| Quá 24 giờ | Session tạm được dọn theo lịch khi worker/retention đang bật; meeting đã publish giữ theo chính sách meeting |

Không tiếp tục thu thêm vào một phiên đã mất stream sau browser restart. Dùng chốt các đoạn đã nhận hoặc hủy rồi tạo phiên mới. Hai phiên unfinished tối đa mỗi account; chốt/hủy phiên cũ để tạo phiên mới. Live tối đa 480 đoạn, hai giờ và 100 events; budget có thể giới hạn sớm hơn.

## 8. Chạy automated checks

```sh
cd '/Users/phucthuan/phucthuan/Tài Liệu Học Năm 5/Công nghệ UD Doanh Nghiệp/Code/backend'
./mvnw verify
```

Tests cần PostgreSQL test riêng hoặc embedded PostgreSQL mở được socket. Trong sandbox hiện tại integration không khởi tạo được DB; chạy trên terminal local. Không đặt TEST_DATABASE_URL trỏ DB có dữ liệu thật.

Tests tích hợp mới dùng STT/LLM giả, không gọi API trả phí. Kiểm thử thu âm phần cứng và API thật ở các mục trên vẫn bắt buộc. Ghi kết quả thực vào bảng Pass/Fail, kèm Chrome/macOS, model, thời lượng, độ trễ và lỗi đã che secret.
