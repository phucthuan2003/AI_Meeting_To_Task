# Nâng cấp MVP v2 — kế hoạch, kết quả và đánh giá từng chặng

Ngày bắt đầu: **28/10/2026**. Bản hiện tại **0.7.0**, V7/V8. Đã có code cả năm chặng; xác minh runtime Chrome/DB/API thật vẫn còn. Giữ Modular Monolith, job/lease/version/evidence và xác nhận trước khi tạo card. Yêu cầu của người dùng: triển khai theo thứ tự đề xuất và đánh giá sau mỗi bước.

## Lộ trình

| Chặng | Phạm vi | Trạng thái |
| --- | --- | --- |
| 1 | Import VTT/SRT và context menu lấy văn bản được chọn | Đã triển khai; xem đánh giá và checks bên dưới |
| 2 | Upload audio → STT → pipeline hiện có | Đã triển khai code; chờ kiểm chứng runtime |
| 3 | SSE cho tiến độ và đề xuất có version, reconnect | Đã triển khai code; chờ kiểm chứng runtime |
| 4 | Thu tab audio + mic trên môi trường mục tiêu | Đã triển khai code; chờ kiểm chứng runtime |
| 5 | Live incremental CREATE/UPDATE/CANCEL, bảo vệ chỉnh sửa và chốt phiên | Đã triển khai code; chờ kiểm chứng runtime |

Đo độ trễ từ nguồn đến đề xuất hợp lệ, độ trễ chốt sau Stop, chất lượng trích xuất và chi phí/giờ họp. Không cam kết latency dưới 1 giây hoặc thu được âm thanh mọi ứng dụng desktop khi chưa benchmark. Audio được bổ sung từ chặng 2; đánh giá chặng 1 bên dưới là mốc trước khi triển khai audio.

## Chặng 1 — Phụ đề và context menu

### Thay đổi

- Backend: `SubtitleParser` đọc cue VTT/SRT UTF-8, giữ timestamp bắt đầu với mili giây, end timestamp và `startMs/endMs`, cue ID/index, vị trí payload trong file gốc. VTT voice tag cung cấp speaker; SRT giữ nhãn trong text, không tự đoán speaker. Cue nhiều người giữ nhãn trong text, speaker cấp cue null. Nội dung multiline gộp cho phân tích nhưng sourceText vẫn lấy đúng raw range.
- Bỏ NOTE/STYLE/REGION khỏi nội dung phân tích, có cảnh báo. Bỏ markup phụ đề được nhận diện, giải mã các entity thông dụng; không chạy HTML. Các cue lỗi, thời gian không hợp lệ/đảo thứ tự hoặc block hỏng làm request thất bại, tránh lưu một phần mà người dùng tưởng đầy đủ. Cho phép cue overlap nếu start time không lùi.
- Giữ parser DOCX và giới hạn file/văn bản/encoding cũ. Định dạng hỗ trợ là tập con phụ đề cuộc họp; không phải renderer WebVTT đầy đủ hoặc parser mọi biến thể SRT. File hỏng cần sửa/xuất lại thay vì cố đoán.
- Flyway V7 mở source_type để lưu VTT/SRT, không sửa migration V1 đã chạy.
- Extension **0.6.0 tại mốc chặng 1**: upload VTT/SRT; quyền mới `contextMenus`. Menu “Đưa văn bản vào Meeting to Task” mở panel, đưa đoạn chọn vào khu vực chờ xem trước. Người dùng chọn “Dùng cho meeting mới”, xác nhận bỏ thay đổi chưa lưu nếu có, kiểm tra rồi bấm Lưu. Không tự gửi AI, không ghi đè meeting cũ, không đọc DOM/URL hoặc thêm quyền đọc mọi trang.
- Đoạn chọn nằm ở trusted session storage, gắn account/backend/session, có thời hạn dùng 10 phút; không lưu ở local storage. Logout xóa đoạn chờ; đóng Chrome mất session. Cần đăng nhập trước khi chọn. Dùng đoạn chọn chỉ tạo bản nháp mới; muốn kết quả AI vẫn phải Lưu rồi Phân tích. Tính năng hỗ trợ một đoạn chờ gần nhất, chưa phải hàng đợi nhiều đoạn.

### Trước và sau

| Trước | Sau | Tác dụng |
| --- | --- | --- |
| File phụ đề bị từ chối | VTT/SRT đi vào cùng pipeline như transcript | Không phải chuyển phụ đề thành TXT thủ công |
| Timestamp chỉ được nhận diện đơn giản ở đầu text | Cue có khoảng thời gian và vị trí gốc | Dẫn chứng dễ tra ngược về thời điểm nguồn |
| Chọn → copy → mở panel → paste | Chọn → menu chuột phải → xem trước → dùng bản nháp | Giảm thao tác, giữ người dùng kiểm soát việc gửi dữ liệu |
| Nguồn người nói không xác định | Giữ nhãn VTT khi có; thiếu thì null | Tránh diễn giải sai độ chắc chắn của dữ liệu |

### Kiểm thử và giới hạn

- Tests parser mới: VTT BOM/CRLF, cue nhiều dòng/overlap, voice tag, entity, raw offsets, SRT và mili giây, nhiều người nói, malformed/timestamp/encoding/giới hạn. Client tests kiểm tra phân phạm vi session, không cắt text im lặng, không lưu URL, mở panel trước await và cho upload phụ đề.
- Đã viết thêm integration test lưu/đọc phụ đề và owner isolation trong FoundationIntegrationTest; lượt chạy bị sandbox chặn mở socket PostgreSQL (`SocketException: Operation not permitted`) trước assertions. Docker socket cũng bị chặn. Không coi migration/lưu DB là đã xác minh ở môi trường này.
- Kết quả cuối: **85 backend unit tests + package đạt; 64 extension tests + Vite build/MV3 checks đạt**, ghi tại Implementation_Progress.md Bước 31. Cần xác minh Chrome thật (context menu/gesture) và V7 trên DB local. File mẫu trong `samples/meeting.vtt` và `samples/meeting.srt`.

### Cách áp dụng và test local

1. Mở Docker Desktop, khởi động PostgreSQL; ở terminal backend, nạp lại `.env` rồi chạy `./mvnw spring-boot:run`. Flyway cần thành công tới V7. Không xóa volume hoặc sửa V1.
2. Trong extension chạy `npm run check`, vào `chrome://extensions` Reload extension từ `extension/dist`. Phiên bản phải là 0.6.0; kiểm tra quyền mới nếu Chrome yêu cầu.
3. Đăng nhập, tạo meeting mới, chọn “TXT / DOCX / VTT / SRT”; lần lượt upload hai file mẫu. Kiểm tra ba segments, timestamp `00:00:01.250`, nguồn nguyên văn và `sourceLocator` có `startMs/endMs`. VTT có nhãn speaker; SRT không suy ra danh tính.
4. Chọn AI → Phân tích → review. Với mẫu, kỳ vọng hai việc cuối đều giao Huy, login giữ hạn 30/10 17:00 và API 31/10 10:00. Đây là kỳ vọng AI cần đo thực tế; parser tests không chứng minh semantic AI đã đạt.
5. Trên trang web thông thường, bôi đen văn bản giả → chuột phải → “Đưa văn bản vào Meeting to Task”. Panel hiển thị đoạn chờ, chưa có request tạo meeting/AI. Bấm “Dùng cho meeting mới”, kiểm tra rồi Lưu/Phân tích.
6. Thử khi đang có nội dung chưa lưu: nhập đoạn chọn phải hỏi trước khi bỏ. Bấm hủy thì nội dung cũ còn. Đăng xuất/đổi account không được nhập lại đoạn của phiên cũ. Chọn quá 200.000 ký tự phải báo lỗi và không tự cắt.
7. Chọn khi chưa đăng nhập: panel yêu cầu đăng nhập rồi chọn lại. Reload panel không tự tạo meeting hoặc gọi AI. Tính năng menu trên trang hạn chế của Chrome có thể không khả dụng; thử bằng trang HTTPS thông thường.
8. Để test integration, dùng PostgreSQL test riêng và chạy `./mvnw -Dtest=FoundationIntegrationTest test` (cấu hình TEST_DATABASE_URL/USER/PASSWORD theo TestDatabase). Không dùng DB chứa dữ liệu thật vì tests tạo fixtures.

### Đánh giá chặng

Đã hoàn thành phần code chặng 1, tăng nguồn đầu vào và tiện ích trình duyệt mà vẫn tận dụng luồng review/Trello. Phần còn chờ xác minh là migration/lưu DB và thao tác menu trong Chrome thật. Nên kiểm tra các mục này trước chặng audio để tránh cộng lỗi môi trường vào pipeline STT mới.

### Thiết kế chặng tiếp theo

Audio upload cần module ingestion/STT riêng, giới hạn dung lượng/thời lượng, kiểm tra container/codec, job bền trong DB, adapter provider và transcript có timestamp. API key đặt ở backend. Chỉ gọi API âm thanh thật khi người dùng chủ động gửi file; dùng response giả để kiểm tra logic trước. Chưa thêm dependency xử lý audio hoặc thay đổi luồng hiện hành trong chặng 1.

Nguồn tham chiếu: [Chrome Side Panel](https://developer.chrome.com/docs/extensions/reference/api/sidePanel), [WebVTT](https://www.w3.org/TR/webvtt1/).

## Chặng 2 — Audio upload và STT (đã triển khai code)

- Thêm module `capture` và migration **V8**: session FILE/LIVE, hàng đợi đoạn audio, checksum/sequence, lease và checkpoint STT. WAV/MP3/M4A/WebM tối đa 10 MiB; kiểm tra signature container, còn việc giải mã do STT kiểm tra. Giới hạn thời lượng file một giờ được xác định từ response STT sau khi request đã gửi, không phải preflight codec đầy đủ.
- Adapter Groq `whisper-large-v3-turbo`, cấu hình `GROQ_API_KEY` tại backend; request multipart tới endpoint cố định HTTPS, key trong Authorization header, không gửi key tới extension. Dùng transport có timeout, response size cap và heartbeat lease. Không có tự động chuyển provider.
- Audio được lưu trong PostgreSQL khi enqueue; sau checkpoint STT thì xóa bytes audio. Transcript tạm/checkpoint còn tới khi publish/cancel hoặc hết hạn 24 giờ. Retention dọn session quá hạn. Audio trong backup DB phụ thuộc chính sách backup riêng.
- File STT hoàn tất cần bấm Chốt & lưu thành meeting; sau đó dùng nút Phân tích và review/Trello hiện có. Silence không tạo transcript giả; phiên không có lời nói không được publish.
- Retry là thao tác chủ động. STT đã checkpoint sẽ được tái sử dụng khi LLM lỗi; request có thể đã tính phí dù response bị mất. Hết lease chuyển FAILED với mã CAPTURE_WORKER_INTERRUPTED, không tự gọi lại bên ngoài.
- **Đánh giá:** đã mở được đầu vào audio và nền job bền. Chưa gọi Groq thật từ agent; cần đo nhận dạng tiếng Việt, tiếng ồn, câu cắt ở ranh giới, thời lượng và quota. Không coi signature hợp lệ là chứng minh file giải mã được.

## Chặng 3 — SSE và đề xuất tạm (đã triển khai code)

- GET `/api/v1/captures/{id}/events` và `/api/v1/jobs/{id}/events` trả SSE snapshot của trạng thái đã lưu. Client dùng fetch streaming với Bearer header; không đưa token vào URL. Server kiểm tra owner trước mở và kiểm tra JWT/session trong khi stream chạy.
- Có heartbeat, đóng luồng sau 60 giây và reconnect để lấy snapshot hiện tại; giới hạn số kết nối. Snapshot thay thế toàn bộ view, không phải replay event log từng thao tác; bỏ lỡ event không làm mất trạng thái DB. Hash event ID không phải số thứ tự dùng để tạo task. Client có giới hạn buffer và retry có backoff; analysis vẫn có GET polling fallback.
- Analysis dài hiển thị candidate tạm từ chunk checkpoint hợp lệ; chưa được duyệt/tạo card. Live hiển thị transcript và đề xuất sau mỗi đoạn xử lý. Câu sửa/hủy phía sau có thể thay đổi kết quả.
- **Điều chỉnh có chủ đích so với bản thảo:** không đẩy token/raw NDJSON chưa kiểm tra ra UI. SSE phát kết quả đã kiểm tra theo đoạn/checkpoint. Transcript ngắn vẫn chờ một lần gọi AI hoàn tất mới có task; chỉ tiến độ được đẩy trước. Không có cam kết task đầu tiên dưới một giây.
- **Đánh giá:** có luồng cập nhật chủ động và khôi phục view; chưa đo tải kết nối/proxy buffering trên server triển khai thật. Không dùng SSE thay cho hàng đợi/DB.

## Chặng 4 — Thu tab audio và mic (đã triển khai code, cần kiểm chứng Chrome thật)

- Extension **0.7.0** thêm tabCapture/offscreen/activeTab; không có content script hoặc đọc DOM. Người dùng mở tab cuộc họp, bấm biểu tượng extension tại tab đó, chuẩn bị phiên rồi Start. Không hỗ trợ thu toàn bộ system audio của Zoom/Teams desktop trong bản này.
- Offscreen document giữ stream khi đóng panel; AudioWorklet lấy PCM, gộp tab/mic, mã hóa từng đoạn WAV mono 16 kHz độc lập khoảng 15 giây. Không gửi các Blob WebM chưa có container đầy đủ. Audio tab được nối lại đầu ra để người dùng tiếp tục nghe, microphone không được phát lại vào loa.
- Có trang `microphone.html` để cấp quyền bằng một thao tác rõ ràng; trang thử quyền rồi dừng tracks, không upload. Quyền Chrome/OS vẫn có thể chặn; có thông báo và hướng dùng file audio.
- Queue local giữ các đoạn chưa gửi trong RAM, tối đa khoảng tám đoạn; gửi tuần tự và checksum/sequence chống trùng tại backend. Hết token/lỗi mạng/backpressure dừng thu và giữ đoạn chờ, không tự lặp POST. Đăng nhập lại cùng account rồi dùng Gửi lại đoạn đang chờ nếu offscreen còn sống.
- Giới hạn live hai giờ/480 đoạn; đóng Chrome hoặc extension reload có thể mất audio chưa upload. Những đoạn backend đã nhận/checkpoint còn. Có thao tác chốt riêng các đoạn đã nhận với xác nhận bỏ phần chưa gửi. Không hứa phục hồi stream sau browser restart.
- **Đánh giá:** vòng đời media tách khỏi panel và có giới hạn bộ nhớ. Chưa kiểm chứng microphone/tab capture trên Mac/Chrome thật, chất lượng resampling, echo hoặc ngắt âm ở ranh giới; không cam kết thu được mọi nguồn/thiết bị.

## Chặng 5 — Incremental và chốt phiên (đã triển khai code)

- Sau STT của mỗi đoạn, gửi nguồn mới và known_tasks tới provider đã chốt khi tạo session. AI trả CREATE/UPDATE/CANCEL theo schema hiện tại. Backend kiểm tra nguồn/JSON, giữ events theo thứ tự và reconcile trước khi hiển thị; giới hạn 100 events toàn phiên, input prompt theo budget.
- Task_ref giữ liên kết xuyên các phần. Candidate live có ID hiển thị ổn định; AI cập nhật đề xuất gốc, không sửa bản override do người dùng lưu. Sửa live dùng expectedVersion; version cũ trả 409. Form đang mở giữ input của người dùng và có lựa chọn rõ ràng để dùng version mới khi lưu lại. Chặn publish khi còn form live chưa lưu.
- Khi AI hủy một việc, không dùng override để tự khôi phục việc đã hủy; UI báo có override không còn proposal, người dùng quyết định thêm thủ công sau khi chốt.
- Stop đóng tracks, đóng gói/gửi đoạn cuối rồi gửi chunkCount chính xác. Backend vào DRAINING, xử lý hết rồi READY. Publish trong transaction tạo meeting/segment AUDIO, ánh xạ lại evidence sang segment DB, tạo kết quả COMPLETED + task nháp và áp dụng override bằng nghiệp vụ task hiện tại. Không gọi AI lại cả cuộc họp ở bước publish.
- Publish lặp trả cùng meeting ID, không tạo meeting thứ hai. Card chỉ được tạo sau review, xác nhận member/hạn và approve-and-sync cũ. Transcript/error/overrides tạm được dọn khi publish; link meeting giữ tới hết hạn session.
- Usage/latency của các response LLM nhận được được cộng trong session và chuyển sang analysis result khi publish; usage thiếu là null. Số liệu này không tính token/cost STT hoặc response bị mất và không phải thời gian đầu cuối của cuộc họp.
- **Đánh giá:** có cơ chế thay đổi/hủy và bảo vệ bản sửa. Chất lượng hiểu tham chiếu xa, câu cắt, trùng việc và task_ref cần benchmark provider thật. Vượt budget làm FAILED, không âm thầm bỏ ngữ cảnh hoặc công bố kết quả đầy đủ giả.

## Kiểm tra bản 0.7.0 và phần chưa xác minh

- Backend unit + package: kết quả cuối trong Implementation_Progress.md bước 36; tests mới kiểm tra STT response/timestamp/quota/auth/redaction, signature, STT checkpoint trước LLM, resume không gọi lại STT, JSON sai không publish và budget.
- Extension: tests WAV độc lập, kích thước PCM, SSE frame chia nhỏ/CRLF/UTF-8/heartbeat, Bearer không nằm URL, auth expiry, lệnh audio không tự retry; cùng toàn bộ regression tests cũ và build MV3 có offscreen/worklet/microphone page.
- Viết bốn CaptureIntegrationTest với PostgreSQL và STT/LLM giả: file dedup/publish/owner/source AUDIO; live override/version/evidence; CANCEL ở phần sau; thiếu đoạn cuối và cancel chặn publish. Lượt chạy bị sandbox chặn socket trước assertions, không phải pass.
- Chưa xác minh migration V7/V8 trên DB local, SSE qua HTTP thật, Chrome capture/mic thật, Groq/LLM live thật, Trello từ nguồn audio/live hoặc browser E2E mới. Tests cũ dùng AI/Trello giả không chứng minh chất lượng AI mới.
- Hướng dẫn khởi động và checklist: [MVP_v2_Test_Guide.md](MVP_v2_Test_Guide.md).
