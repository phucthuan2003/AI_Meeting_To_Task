# BÁO CÁO TIẾN ĐỘ DỰ ÁN AI MEETING TO TASK

**Ngày tổng hợp:** 10/10/2026 · **Dùng cho buổi báo cáo:** 11/10/2026  
**Phiên bản giao diện hiện tại:** Chrome Extension 0.5.0  
**Phạm vi:** từ phân tích yêu cầu và đặc tả SDS v3 đến nhập transcript, phân tích AI, duyệt công việc và tạo card trên Trello.

## 1. Tóm tắt kết quả

Dự án xây dựng một Chrome Extension giúp chuyển nội dung cuộc họp dạng văn bản thành danh sách công việc có người phụ trách, thời hạn và bằng chứng từ cuộc họp. Người dùng kiểm tra, chỉnh sửa và xác nhận trước khi hệ thống tạo card trên Trello.

Hiện đã triển khai luồng nghiệp vụ từ đầu đến cuối: **đăng nhập → nhập nội dung → lưu/chuẩn hóa → phân tích AI → sửa và duyệt task → chọn Board/List/thành viên → xác nhận → tạo card → xem kết quả**. Ngày 10/10/2026, người thực hiện xác nhận đã tạo và kiểm tra card trên **Trello thật từ extension**. Đây là kết quả kiểm thử thủ công do người thực hiện cung cấp, chưa kèm bộ ảnh, log và danh sách ca kiểm thử trong lượt tổng hợp này.

Dự án đang ở mức **MVP có luồng sử dụng thực tế**, chưa nên kết luận hoàn thiện toàn bộ hoặc sẵn sàng vận hành production. Các việc còn lại tập trung vào đo chất lượng AI, kiểm thử trên nhiều tình huống thực, xác minh đầy đủ kết nối bên ngoài, và hoàn thiện vận hành/bảo mật.

## 2. Bài toán và phạm vi giải quyết

Sau cuộc họp, người tham gia thường phải đọc lại nội dung, tìm các câu giao việc, xác định ai thực hiện và hạn hoàn thành, sau đó chuyển sang công cụ quản lý công việc để nhập lại. Quá trình này có thể bỏ sót việc, hiểu nhầm người nhận, nhầm hạn hoặc tạo công việc trùng.

Giải pháp của dự án là để AI đề xuất công việc, nhưng giữ bước kiểm tra của con người trước thao tác ghi vào Trello. Mỗi đề xuất có dẫn chứng để người dùng đối chiếu với câu nói gốc.

**Trong phạm vi hiện tại:**

- Nhập văn bản bằng cách dán nội dung hoặc tải file TXT/DOCX.
- Quản lý tài khoản, cuộc họp, nguồn văn bản, lịch sử và bản nháp.
- Chọn OpenAI hoặc Gemini để phân tích.
- Trích xuất việc mới, nhận diện sửa đổi/hủy việc và kiểm tra kết quả.
- Chỉnh sửa, thêm thủ công, loại bỏ/khôi phục công việc.
- Chọn nơi nhận trên Trello, xác nhận người phụ trách và hạn, tạo card.
- Xử lý lỗi, dữ liệu phiên bản cũ, trạng thái chưa rõ kết quả và vòng đời dữ liệu.

**Ngoài phạm vi MVP:** thu âm, chuyển giọng nói thành văn bản, tự đọc nội dung Teams/Meet/Zoom, đồng bộ thay đổi hai chiều với Trello. Không có chức năng tự huấn luyện mô hình AI riêng; hệ thống tích hợp mô hình qua API.

## 3. Quá trình thực hiện từ đầu đến hiện tại

| Chặng | Công việc đã thực hiện | Kết quả đầu ra |
| --- | --- | --- |
| Phân tích và thiết kế | Rà soát SDS v2, bổ sung quy trình duyệt, quản lý phiên bản, job nền, dẫn chứng và xử lý lỗi; xây dựng SDS v3 | Đặc tả nghiệp vụ, kiến trúc, dữ liệu và các tình huống nghiệm thu |
| Nền tảng backend | Spring Boot, PostgreSQL, migration, đăng ký/đăng nhập/đăng xuất, phân quyền theo chủ sở hữu | API nền tảng và cơ sở dữ liệu |
| Nhập nội dung cuộc họp | Paste/TXT/DOCX, giới hạn đầu vào, chuẩn hóa, chia segment, lưu nguồn và lịch sử | Preview có thể đối chiếu với nguồn gốc |
| Nối extension | React Side Panel, gọi API localhost, CORS, session, khôi phục meeting theo tài khoản | Người dùng thao tác trong trình duyệt |
| Job phân tích | Hàng đợi trong DB, worker, tiến độ, hủy, retry và checkpoint | Phân tích không phụ thuộc việc panel có đang mở |
| Tích hợp AI | Hai adapter OpenAI/Gemini, prompt/schema, kiểm tra JSON và evidence | Đề xuất công việc được kiểm tra trước khi lưu |
| Xử lý sự cố Gemini | Phân biệt lỗi CA/TLS của Python với HTTP 400; thử request đơn giản; chuyển adapter Gemini sang text có yêu cầu JSON trong prompt | Cách gọi tương thích hơn, giữ kiểm tra phía backend và không đưa key vào giao diện |
| Review task | Task có version, chỉnh sửa/autosave, cảnh báo, bằng chứng, thêm thủ công, loại bỏ/khôi phục | Người dùng kiểm soát dữ liệu trước khi tạo card |
| Tích hợp Trello | Kết nối, Board/List, đối chiếu thành viên, gợi ý hạn, snapshot và hàng đợi tạo card | Đã được người thực hiện xác nhận tạo và kiểm tra card trên Trello thật |
| Transcript dài và độ tin cậy | Chia phần, overlap, checkpoint/resume; UNKNOWN và đối soát card; retention và xóa dữ liệu | Có cơ chế xử lý lỗi và dữ liệu dài; một số phần cần đánh giá thực tế thêm |
| Kiểm thử và tài liệu | Unit/integration/E2E, Trello giả có bơm lỗi, dataset và script đánh giá, hướng dẫn demo | Bộ kiểm thử và tài liệu phục vụ chạy lại, trình bày và phát triển tiếp |

Nhật ký kỹ thuật gốc ghi chi tiết các bước 1–28 trong [Implementation_Progress.md](Implementation_Progress.md). Những trạng thái “chưa có Trello”, “chỉ chuẩn bị input” ở các bước cũ là lịch sử phát triển, không phải trạng thái hiện tại.

## 4. Kiến trúc và vai trò từng thành phần

```text
Người dùng
    │ thao tác trên Side Panel
    ▼
Chrome Extension MV3 — React
    │ REST API + JWT
    ▼
Backend Spring Boot
    ├── Auth / Meeting / Transcript / Task
    ├── Worker phân tích ─────────► OpenAI hoặc Gemini
    ├── Trello / Worker đồng bộ ──► Trello API
    ├── Privacy / Retention
    └── PostgreSQL: dữ liệu, job, phiên bản, checkpoint, snapshot
```

| Thành phần | Công nghệ/vai trò |
| --- | --- |
| Extension | Chrome Manifest V3, React 19.2.3, Vite 7.3.7; Side Panel nhập nội dung, theo dõi job, review và tạo card |
| Backend | Spring Boot; kiến trúc nguyên khối chia module để triển khai đơn giản nhưng tách trách nhiệm |
| PostgreSQL | Lưu tài khoản/session, meeting/revision/segment, task, job phân tích và đồng bộ, kết nối Trello |
| Flyway | Quản lý thay đổi cấu trúc DB theo migration V1–V6; tránh sửa schema thủ công mỗi lần nâng cấp |
| LLM adapter | Cùng giao diện gọi provider; key chỉ ở backend, người dùng chọn provider đã cấu hình |
| Worker | Nhận công việc từ DB, cập nhật trạng thái, xử lý timeout/hủy và khôi phục khi tiến trình gián đoạn |
| Công cụ hỗ trợ | Docker Compose cho DB; Trello giả; Playwright E2E; Python cho chẩn đoán và đánh giá |

Không cần một message broker riêng ở giai đoạn MVP: PostgreSQL vừa lưu dữ liệu nghiệp vụ vừa quản lý hàng đợi công việc. Thiết kế này giảm thành phần phải vận hành, nhưng khả năng chịu tải cần được đo trước khi mở rộng.

## 5. Luồng chạy và cách thức hoạt động

### 5.1. Khởi động hệ thống

PostgreSQL được khởi động trước. Backend đọc cấu hình DB, JWT, origin của extension, key AI và cấu hình Trello từ môi trường; Flyway cập nhật schema. Extension được build và load vào Chrome, gọi backend mặc định tại `http://127.0.0.1:8080`.

Kiểm tra `/actuator/health` trả `UP` trước khi thao tác. Thay đổi `.env` phải nạp lại biến và restart backend; sửa file không tự thay đổi cấu hình JVM đang chạy. Luồng demo đầy đủ đã có trong [Demo_Script.md](Demo_Script.md) và [Real_Trello_Test.md](Real_Trello_Test.md).

### 5.2. Đăng nhập và quản lý phiên

Backend xác thực tài khoản và cấp JWT có thời hạn 15 phút. Request nghiệp vụ gửi Bearer token, đồng thời backend kiểm tra phiên đăng nhập và chủ sở hữu dữ liệu. Logout thu hồi phiên; token cũ không tiếp tục được dùng.

JWT nằm trong Chrome session storage. Bộ nhớ bền của extension lưu con trỏ meeting theo tài khoản và backend, không lưu bền nội dung nhập chưa được gửi. Khi đăng nhập lại, meeting đã lưu có thể được tải từ DB. Account B không được đọc meeting của account A.

### 5.3. Nhập, chuẩn hóa và lưu nguồn

Người dùng nhập tiêu đề, ngày họp, múi giờ và transcript. Backend kiểm tra loại file/kích thước/định dạng, chuẩn hóa văn bản rồi chia thành các segment. Mỗi segment lưu nội dung và vị trí nguồn để truy vết.

**Segment là đoạn nguồn, không phải công việc.** Hai dòng có thể tạo hai segment, nhưng không nhất thiết tạo hai task. Khi thay toàn bộ nội dung, số segment có thể giảm; đó không phải bộ đếm tăng dần của hệ thống.

DB lưu meeting, phiên bản nội dung và các segment. Giao diện hiện preview và nguồn. Tới bước này chưa gọi AI. Các giới hạn cấu hình hiện tại gồm file 10 MiB, JSON body 1 MiB, văn bản trích xuất tối đa 200.000 đơn vị UTF-16; giới hạn thực tế của một lần gọi AI nhỏ hơn giới hạn nhập liệu.

### 5.4. Phân tích AI qua job nền

Khi người dùng bấm **Phân tích**, backend tạo job, lưu cố định phiên bản input, provider, model, prompt, schema và budget. API trả về job để giao diện theo dõi, thay vì giữ một request kéo dài tới khi AI hoàn thành.

Worker nhận job với quyền xử lý có thời hạn (lease), chuẩn bị nguồn và gọi provider. Panel định kỳ đọc trạng thái. Đóng panel không xóa job; backend còn chạy thì tiếp tục xử lý. Nếu backend dừng, công việc không chạy trong thời gian dừng; cơ chế lease/recovery hỗ trợ tiếp tục sau khi backend hoạt động lại.

Luồng chính là `QUEUED → PROCESSING → COMPLETED`; các nhánh khác gồm lỗi, hủy và `PARTIAL_FAILED` khi xử lý nhiều phần. Trong lúc phân tích, input bị khóa sửa để tránh kết quả thuộc một phiên bản nhưng hiển thị cho phiên bản khác.

### 5.5. Biến kết quả AI thành công việc

Prompt yêu cầu trích xuất các sự kiện `CREATE`, `UPDATE`, `CANCEL`. Nhờ đó, câu giao việc rồi sửa người nhận/hạn hoặc hủy ở phía sau có thể được tổng hợp thành danh sách cuối cùng.

OpenAI dùng cơ chế đầu ra có schema trong adapter. Gemini hiện đưa hướng dẫn và schema vào prompt dạng text; backend tiếp nhận JSON thuần hoặc một khối JSON bọc Markdown hợp lệ rồi kiểm tra.

Backend kiểm tra cấu trúc JSON, trường bắt buộc, giới hạn, thứ tự sự kiện, ID segment và evidence. Người phụ trách và hạn dạng nguyên văn phải có hỗ trợ từ nguồn. Trích dẫn hiển thị được lấy từ nguồn lưu ở backend, không tin một câu trích dẫn do model tự viết.

Kết quả hợp lệ được lưu thành task nháp cần review. **Kiểm tra schema không chứng minh AI hiểu đúng toàn bộ ngữ nghĩa**, vì vậy vẫn cần người dùng đối chiếu và các phép đo chất lượng độc lập.

### 5.6. Review, sửa và xử lý xung đột

Người dùng có thể sửa tên/mô tả, người nhận, hạn, ưu tiên; xem nguồn; thêm task thủ công; loại bỏ hoặc khôi phục. Autosave có khoảng chờ 800 ms và xếp tuần tự các lần lưu để hạn chế request chồng nhau.

Mỗi task có `version`. Khi lưu, client gửi `expectedVersion`. Nếu cửa sổ khác đã sửa dữ liệu, backend trả `409 STALE_VERSION`; giao diện cho người dùng xử lý xung đột thay vì tự ghi đè.

Ví dụ: cùng một tài khoản mở một task ở A và B, đều đang version 1. A lưu thành version 2. B gửi bản sửa dựa trên version 1 thì bị từ chối. Đây khác với kiểm thử hai tài khoản, vốn nhằm kiểm tra quyền sở hữu.

### 5.7. Chọn Trello, người nhận và thời hạn

Người dùng kết nối Trello và chọn Board/List. Backend kiểm tra List thuộc đúng Board và đích còn hợp lệ. Token Trello được mã hóa AES-GCM trước khi lưu.

Tên trong cuộc họp được đối chiếu với thành viên Board. Một kết quả khớp được đưa ra dưới dạng gợi ý; nhiều người cùng tên thì báo mơ hồ. Người dùng phải xác nhận hoặc chọn “Không giao người”. Hệ thống có alias đã xác nhận để hỗ trợ các lần sau.

Hạn tương đối được gợi ý dựa trên **ngày cuộc họp**, không phải ngày upload. Hạn còn mơ hồ cần người dùng xác nhận. Màn hình duyệt hiển thị ngày, giờ và múi giờ; dữ liệu gửi Trello dùng thời điểm UTC tương ứng.

Code có cả đường kết nối OAuth 2.0 + PKCE và API key + token. Việc tạo card Trello thật đã được người thực hiện xác nhận, nhưng chưa xác định phương thức kết nối của lần chạy đó; không dùng xác nhận này để kết luận riêng nhánh OAuth Atlassian thật đã đạt.

### 5.8. Duyệt và tạo card

Nút tạo card chỉ mở khi các task đủ điều kiện. Backend kiểm tra version task và nơi nhận, rồi trong một transaction tạo snapshot nội dung đã duyệt và các item đồng bộ. Worker tạo card từ snapshot để tránh nội dung bị thay đổi giữa lúc duyệt và gửi.

`Idempotency-Key` giúp cùng một yêu cầu gửi lại trả về job cũ thay vì tạo thêm job. Mỗi card có marker `AI_MTT_REF` trong mô tả để hỗ trợ tìm lại khi phản hồi bị mất.

Nếu Trello đã nhận lệnh nhưng request timeout, hệ thống không thể chắc card có được tạo hay chưa. Item chuyển sang **UNKNOWN** và được đối soát theo marker. Không tự tạo lại ngay; nếu tìm thấy card thì lưu liên kết và chuyển sang thành công. Cơ chế này giảm nguy cơ trùng, nhưng không phải cam kết tuyệt đối exactly-once trong mọi sự cố.

### 5.9. Transcript dài và xóa dữ liệu

Khi bật chunking, hệ thống chia theo ranh giới segment, chồng lấn tối đa hai segment và chuyển thông tin task đã biết sang phần tiếp theo. Có checkpoint từng phần, loại trùng trong vùng overlap và tổng hợp theo thứ tự. Nếu một phần lỗi, không công bố kết quả thiếu như đã hoàn tất; retry có thể tiếp tục từ phần chưa xong.

Chunking **mặc định tắt**, tối đa 20 phần khi bật. Cần đánh giá sửa/hủy ở xa và giới hạn thông tin task đã biết trước khi coi xử lý transcript dài là ổn định.

Nguồn transcript có hạn lưu mặc định 30 ngày; worker retention xử lý nguồn hết hạn và log theo cấu hình. Có chức năng xóa transcript riêng hoặc xóa meeting. Xóa meeting trong ứng dụng không xóa card Trello; dữ liệu đã gửi sang Trello không được thu hồi chỉ bằng việc xóa nguồn nội bộ.

## 6. Dữ liệu và những điểm kỹ thuật đáng trình bày

| Nhóm dữ liệu | Mục đích |
| --- | --- |
| User và auth session — V1 | Xác thực, thu hồi phiên và phân quyền |
| Meeting, revision, segment — V1 | Lưu cuộc họp, phiên bản và vị trí văn bản nguồn |
| Analysis job/checkpoint — V2 | Theo dõi phân tích bền vững qua DB |
| Analysis result — V3 | Lưu kết quả trích xuất, usage và thông tin xử lý |
| Task và task evidence — V4 | Review, version, thông tin người dùng chỉnh sửa và dẫn chứng |
| Connection, destination, snapshot, sync — V5 | Kết nối Trello, duyệt và theo dõi từng lần tạo card |
| Chunk result, processing log, trường retention — V6 | Tiếp tục xử lý nhiều phần và quản lý vòng đời dữ liệu |

Các quyết định nổi bật: con người duyệt trước khi ghi ra hệ thống ngoài; nguồn có thể truy vết; tách trạng thái review khỏi trạng thái đồng bộ; khóa theo phiên bản để tránh mất thay đổi; job nằm ở backend; phân biệt lỗi chắc chắn với kết quả chưa rõ; không ghi key hoặc transcript vào log xử lý thông thường.

## 7. Kết quả kiểm thử và mức độ bằng chứng

| Hạng mục | Kết quả có thể báo cáo | Nguồn và giới hạn |
| --- | --- | --- |
| Extension bản hiện tại | **61/61 tests đạt; Vite build và MV3 checks đạt** | Đã chạy lại `npm run check` trực tiếp ngày 10/10 trong lượt tổng hợp này; không thay thế kiểm thử người dùng |
| Backend | **Nhật ký ghi nhận 134 tests đạt: 80 unit + 54 integration** | Bước 28 ghi chạy Linux/JDK 21/PostgreSQL 16 thật; không chạy lại toàn bộ backend trong lượt này |
| Browser E2E | Nhật ký ghi nhận E2E đạt: nhập, review, tạo card, timeout → UNKNOWN → đối soát, reopen và xóa meeting | Dùng extension/Chromium/backend/PostgreSQL, nhưng AI theo quy tắc và Trello giả; không phải đánh giá AI thật |
| Công cụ Python | Nhật ký Bước 28 ghi 2 tests Trello giả và 6 tests đánh giá đạt | Là tests của công cụ hỗ trợ, không phải chỉ số chất lượng model |
| Gemini API thật | Đã có kết quả HTTP 200 với request đơn giản do người dùng cung cấp | Chứng minh request đó hoạt động, không suy ra độ chính xác trích xuất trên tập dữ liệu |
| Trello thật | **Người thực hiện xác nhận đã tạo và kiểm tra card từ extension** | Xác nhận ngày 10/10; chưa bổ sung ảnh/card ID đã ẩn thông tin nhạy cảm và bảng Pass/Fail chi tiết |
| Độ chính xác AI, hiệu quả sử dụng | Chưa có số đo thực được cung cấp | Không báo cáo F1, accuracy hoặc tỷ lệ tiết kiệm thời gian như kết quả đã đạt |

**Lưu ý đối chiếu test:** các XML đang có trong `backend/target/surefire-reports` vẫn gồm lượt cũ với 26 lỗi integration lúc khởi tạo môi trường PostgreSQL. Không cộng các file cũ thành kết quả của code hiện tại, cũng không xem chúng là bằng chứng bác bỏ lượt Linux được ghi trong nhật ký. Cần lưu report của một lượt `verify` mới, thống nhất commit và môi trường, để có hồ sơ nghiệm thu dễ kiểm tra.

Dataset hiện có **14 transcript tổng hợp, 7 dev và 7 test**, cùng script đo precision/recall/F1, người phụ trách, hạn, evidence, hủy task, latency và token. Đây là hạ tầng đánh giá đã làm; chưa phải bộ kết quả đánh giá AI thật. Số mẫu vẫn thấp hơn mức 30–50 được đề xuất trong kế hoạch.

## 8. Những phần chưa hoàn chỉnh và hướng cải tiến

| Vấn đề còn lại | Ảnh hưởng | Việc nên làm tiếp |
| --- | --- | --- |
| Chưa có baseline AI thật | Chưa biết tỷ lệ bỏ sót, bịa việc, nhầm người/hạn | Mở rộng dataset; chạy provider thật trên dev/test riêng; lưu model/prompt/version và phân tích lỗi |
| Transcript dài mới kiểm thử logic bằng provider giả | Sửa/hủy ở xa có thể mất ngữ cảnh; giới hạn known_tasks có thể bỏ thông tin cũ | Đánh giá trường hợp dài thực, overlap và checkpoint; chỉ bật mặc định sau khi đạt tiêu chí |
| Trello thật mới có xác nhận thủ công tổng quát | Chưa chứng minh mọi nhánh lỗi/quyền/quota đều đúng | Ghi bảng kiểm tên card, Board/List, member, due, reopen, chống trùng; bổ sung ảnh demo |
| OAuth Atlassian thật chưa được xác nhận riêng | Cấu hình scope/endpoint của app thực có thể chưa phù hợp | Xác minh trên app đăng ký thật; ghi rõ phương thức kết nối được dùng trong báo cáo |
| Chưa đo usability | Chưa chứng minh nhanh hoặc dễ dùng hơn cách thủ công | Cho người dùng làm cùng bài bằng hai cách; đo thời gian tới card đúng, số sửa và tỷ lệ đúng |
| Chưa kiểm thử tải và vận hành dài ngày | Queue DB, worker, rate limit trong một process có thể hạn chế khi mở rộng | Load test, đo độ trễ, giám sát queue, kiểm tra nhiều worker và DB pool |
| Triển khai hiện thiên về local | Chưa đủ điều kiện đưa lên môi trường dùng chung | HTTPS, secret management, backup/restore, quy trình migration, giám sát và cảnh báo |
| Hết JWT phải đăng nhập lại | Có thể gián đoạn phiên review dài | Đánh giá UX và cơ chế gia hạn phiên có kiểm soát trong chặng sau |
| Bằng chứng test phân tán | Khó tái lập kết quả và bảo vệ số liệu | Lưu commit SHA, cấu hình không chứa secret, JUnit report, E2E report và checklist thủ công |
| Đối soát card có giới hạn | Card đổi marker, di chuyển hoặc ngoài phạm vi tìm có thể không được tìm thấy | Kiểm thử tình huống ngoại lệ và hướng dẫn gắn card thủ công; không hứa loại bỏ tuyệt đối trùng |

Không gộp các tính năng ngoài MVP như ghi âm hoặc đồng bộ hai chiều thành lỗi chưa làm xong. Có thể đề xuất chúng là hướng phát triển sau khi chất lượng và độ tin cậy của luồng hiện tại được đo đầy đủ.

## 9. Kế hoạch tiếp theo theo ưu tiên

1. **Hoàn thiện bằng chứng demo thật:** lưu ảnh panel, danh sách task đã duyệt và card trên Board thử nghiệm; che token/key/email không cần thiết. Ghi rõ provider/model và phương thức kết nối Trello của lần demo.
2. **Chuẩn hóa kiểm thử phiên bản hiện tại:** chạy lại backend trên DB test riêng, lưu reports cùng commit; giữ kết quả extension mới đã đạt. Không chạy integration trên DB chứa dữ liệu thật.
3. **Đánh giá AI:** hoàn thiện bộ 30–50 mẫu, bao gồm câu hỏi không phải giao việc, sửa/hủy, trùng tên, hạn tương đối, thiếu metadata và transcript dài. Dùng test set chưa được dùng để chỉnh prompt.
4. **Đánh giá người dùng:** so sánh quy trình thủ công và extension, tính cả thời gian chờ AI và sửa lỗi; không chỉ đo tốc độ tạo card.
5. **Cải thiện theo lỗi quan sát được:** prompt, bộ xử lý hạn, trải nghiệm review, chi phí/token và chunking; sau đó mới chuẩn bị triển khai rộng hơn.

## 10. Kịch bản trình bày/demo khoảng 5–7 phút

| Thời gian | Nội dung trình bày |
| --- | --- |
| 0–1 phút | Bài toán nhập lại công việc sau cuộc họp; mục tiêu và phạm vi MVP |
| 1–2 phút | Sơ đồ extension → backend → DB/AI/Trello; giải thích AI đề xuất, người dùng duyệt |
| 2–4 phút | Dán transcript → preview → phân tích → xem evidence → sửa người/hạn → tạo card trên Board thử nghiệm |
| 4–5 phút | Mở card Trello, đối chiếu người nhận/hạn; đóng/mở panel để cho thấy dữ liệu đã lưu |
| 5–6 phút | Giải thích version conflict và UNKNOWN bằng ví dụ; demo lỗi bằng Trello giả nếu cần |
| 6–7 phút | Nêu kết quả kiểm thử, giới hạn AI/chunking/OAuth và kế hoạch đo chất lượng |

**Transcript demo ngắn, dùng ngày họp 11/10/2026 và múi giờ Asia/Ho_Chi_Minh:**

```text
Nam: Thuận hoàn thành màn hình đăng nhập trước 17:00 ngày 14/10/2026.
Nam: Thuận viết tài liệu API trước 10:00 ngày 15/10/2026.
Nam: À, tài liệu API chuyển hạn sang 16:00 ngày 16/10/2026.
Nam: Hệ thống hôm qua hơi chậm nhưng hiện đã ổn.
```

Kỳ vọng để kiểm tra: hai task; tài liệu API mang hạn sau khi sửa; câu thông báo hệ thống không thành task. Đổi tên Thuận theo thành viên Board thực và vẫn xác nhận người nhận trước tạo card. Đây là đáp án kỳ vọng, cần chạy thử trước buổi trình bày; nên chuẩn bị sẵn ảnh kết quả thành công để dự phòng mạng/quota.

## 11. Bản phát biểu ngắn có thể dùng trực tiếp

“Đề tài của em là AI Meeting to Task, hỗ trợ chuyển nội dung cuộc họp thành công việc trên Trello. Em đã xây dựng Chrome Extension bằng React, backend Spring Boot và cơ sở dữ liệu PostgreSQL. Người dùng nhập transcript bằng văn bản hoặc file, hệ thống lưu và chuẩn hóa nguồn, sau đó gọi OpenAI hoặc Gemini để đề xuất công việc.

Điểm em chú trọng là AI không tự tạo card ngay. Mỗi đề xuất có bằng chứng từ cuộc họp; người dùng kiểm tra, sửa người phụ trách và hạn rồi mới xác nhận. Backend quản lý phiên bản để tránh hai cửa sổ ghi đè nhau, đồng thời xử lý phân tích và tạo card bằng job lưu trong cơ sở dữ liệu.

Đến hiện tại em đã chạy được luồng từ extension và tạo, kiểm tra card trên Trello thật. Em cũng đã bổ sung xử lý trường hợp timeout khi tạo card: hệ thống giữ trạng thái chưa rõ kết quả và đối soát trước khi cho tạo lại, nhằm hạn chế trùng. Ngoài ra đã có chức năng task thủ công, chia transcript dài, checkpoint và quản lý thời hạn dữ liệu.

Về kiểm thử, bản extension hiện tại vừa chạy lại 61 tests và build thành công; nhật ký dự án ghi nhận 134 tests backend cùng E2E trên môi trường có Trello giả. Em phân biệt kết quả kiểm thử kỹ thuật này với chất lượng AI. Phần em cần làm tiếp là đánh giá trên bộ transcript lớn hơn, đo độ chính xác và thời gian tiết kiệm cho người dùng, kiểm thử thêm các tình huống Trello thực và hoàn thiện vận hành trước khi triển khai rộng.”

## 12. Câu hỏi giảng viên có thể đặt ra

**AI sai thì xử lý thế nào?** Backend kiểm tra JSON và dẫn chứng; người dùng review trước tạo card. Các kiểm tra này giảm lỗi nhưng không thay thế đo chất lượng ngữ nghĩa.

**Vì sao phải dùng backend khi extension cũng gọi API được?** Backend giữ key, kiểm tra quyền sở hữu, lưu dữ liệu/job và quản lý transaction. Công việc tiếp tục khi panel đóng nếu backend vẫn hoạt động.

**Có chắc không tạo card trùng không?** Có idempotency, snapshot và đối soát theo marker để giảm rủi ro. Không cam kết tuyệt đối exactly-once khi dịch vụ ngoài đã nhận lệnh nhưng phản hồi bị mất.

**Có phải mỗi segment là một task không?** Không. Segment là đoạn văn bản nguồn để phân tích và dẫn chứng; nhiều đoạn có thể nói về một task hoặc không có task nào.

**Đã hoàn thiện bao nhiêu phần trăm?** Nên báo cáo theo chức năng và bằng chứng: luồng chính đã triển khai và tạo card thật thành công; chất lượng AI, tải, OAuth thực và usability chưa có đầy đủ số đo. Chưa có cơ sở chấm một tỷ lệ phần trăm chính xác.

**Đóng panel hoặc mất mạng có mất dữ liệu không?** Dữ liệu đã lưu còn trong backend; input chưa lưu không được đảm bảo giữ lại. Mất phản hồi của lệnh ghi cần kiểm tra trạng thái trước gửi lại.

**Em tự xây dựng mô hình AI hay dùng API?** Dự án tích hợp API mô hình có sẵn. Phần thực hiện của dự án là phân tích yêu cầu, thiết kế dữ liệu/luồng, prompt, adapter, kiểm tra đầu ra, review, tích hợp Trello, xử lý lỗi và đánh giá.

## 13. Cơ sở tổng hợp và phạm vi rà soát

Báo cáo đối chiếu README, SDS v3, nhật ký triển khai, hướng dẫn API/review/Trello/evaluation và các module hiện tại, trọng tâm là AnalysisPipeline, GeminiProvider, review/storage, ApproveService, SyncRunner, Reconciler và PrivacyService. Lượt này trực tiếp chạy lại `npm run check`, đọc báo cáo test local và ghi nhận xác nhận Trello thật từ người thực hiện; không phải cuộc kiểm toán toàn bộ code hoặc một lượt chạy lại backend/E2E/AI có trả phí.

Tài liệu liên quan: [SDS v3](SDS_v3_Content.md) · [Tiến độ](Implementation_Progress.md) · [API](API_Test_Guide.md) · [Review](Review_Test_Guide.md) · [Trello thật](Real_Trello_Test.md) · [Demo](Demo_Script.md) · [Đánh giá AI](Evaluation_Guide.md) · [Kiểm thử tổng thể](Full_Test_Guide.md).
