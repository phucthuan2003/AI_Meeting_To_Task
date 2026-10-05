# 1 Giới thiệu
## 1 1 Mục đích
Tài liệu đặc tả thiết kế giải pháp AI Meeting-to-Task phiên bản 3.0, dùng làm cơ sở triển khai Extension, Backend, pipeline AI, tích hợp Trello và kiểm thử. Hệ thống giúp chuyển transcript thành công việc có bằng chứng, được người dùng kiểm tra trước khi tạo card.
Phiên bản 3.0 giữ kiến trúc Browser Extension và Spring Boot Modular Monolith. Thay đổi chính là xử lý phân tích bằng job có lưu trạng thái, xác định Board/List và thành viên trước khi duyệt, gắn xác nhận với phiên bản dữ liệu, và đối soát các lần tạo card chưa biết kết quả.
## 1 2 Tổng quan sản phẩm
Người dùng mở Side Panel, paste transcript hoặc đính kèm một file .txt/.docx, kiểm tra phần văn bản được đọc, nhập ngày họp nếu cần và yêu cầu phân tích. Backend chuẩn hóa, gọi LLM, kiểm tra đề xuất rồi lưu các task chờ review. Người dùng sửa, thêm, loại bỏ task, chọn Trello Board/List và kiểm tra người phụ trách trước khi bấm tạo card.
LLM xử lý ý nghĩa nội dung cuộc họp. Backend kiểm soát dữ liệu, quyền truy cập, phiên bản đã duyệt, tiến trình xử lý và mọi lệnh ghi sang Trello. Extension hiển thị và thu nhận lựa chọn của người dùng; tiến trình không phụ thuộc vào việc Side Panel luôn mở.
## 1 3 Luồng nghiệp vụ chính
1. Đăng nhập hệ thống và mở hoặc tạo một cuộc họp.
2. Nhập một transcript, xem trước text và kiểm tra metadata.
3. Bấm Phân tích sau khi biết provider sẽ nhận dữ liệu.
4. Theo dõi job; có thể đóng panel và mở lại để tiếp tục.
5. Review task cùng evidence, thêm task thủ công hoặc loại bỏ đề xuất.
6. Kết nối Trello nếu cần, chọn Board/List và kiểm tra member mapping.
7. Chọn các task cần tạo; kiểm tra ngày giờ, cảnh báo và bản xem trước card.
8. Bấm Tạo N card trên Trello để xác nhận phiên bản hiện tại và tạo sync job.
9. Xem kết quả từng task; mở card thành công hoặc xử lý task lỗi/UNKNOWN.
## 1 4 Phạm vi MVP
MVP hỗ trợ tiếng Việt và tiếng Anh; nhập text, .txt, .docx; metadata title, ngày họp và múi giờ; evidence truy xuất từ câu nguồn; review và tạo task thủ công; một tài khoản Trello đang hoạt động cho mỗi user; chọn một Board/List đích cho mỗi cuộc họp; tạo card một chiều; retry có kiểm soát; khôi phục tiến trình và xóa dữ liệu.
Task có thể không có assignee, deadline hoặc priority. Thiếu trường tùy chọn không làm task mất tính hành động. Người dùng được chủ động tạo card không giao người hoặc không đặt hạn sau khi xử lý các cảnh báo liên quan.
Transcript dài được hỗ trợ qua chunking có kiểm soát. Giai đoạn triển khai đầu sử dụng single-call trong giới hạn đã benchmark; chỉ bật chunking khi kiểm thử thay đổi, hủy task và lỗi từng chunk đạt yêu cầu. Khi chunking chưa bật, input vượt ngân sách bị từ chối rõ ràng, không cắt bớt âm thầm.
## 1 5 Ngoài phạm vi MVP
Không thu âm, không Speech-to-Text, không tự lấy transcript từ Teams/Meet/Zoom, không tự theo dõi cuộc họp, không chatbot tổng quát, không đồng bộ hai chiều, không tự tạo card trước xác nhận và không tự huấn luyện model. SRT, VTT và PDF là hướng mở rộng parser.
Task đã sync chỉ được xem và mở trên Trello trong MVP. Thay đổi trực tiếp trên Trello không được đọc ngược để cập nhật task nội bộ.
## 1 6 Thuật ngữ
| Thuật ngữ | Ý nghĩa |
| --- | --- |
| Transcript | Nội dung cuộc họp do người dùng cung cấp |
| Segment | Đơn vị văn bản có ID, thứ tự và vị trí nguồn ổn định |
| Candidate | Đề xuất của AI chưa được người dùng duyệt |
| Job | Công việc nền có trạng thái lưu trong DB |
| Snapshot | Bản nội dung và đích Trello đã được xác nhận |
| Idempotency | Gửi lại cùng yêu cầu nội bộ không tạo thêm một thao tác mới |
| UNKNOWN | Lệnh ghi có thể đã thành công nhưng chưa biết kết quả |

# 2 Giả định và ràng buộc
## 2 1 Giả định
Người dùng sử dụng Chrome/Chromium hỗ trợ Manifest V3 và Side Panel; đã có transcript và tài khoản Trello; được phép cung cấp nội dung họp cho provider đã chọn. Backend truy cập được PostgreSQL, LLM API và Trello API qua HTTPS.
Mỗi meeting thuộc một user trong MVP. Chưa triển khai chia sẻ meeting hoặc review nhiều người. Có thể sử dụng nhiều tab/panel của cùng user nên backend vẫn phải xử lý sửa và sync đồng thời.
## 2 2 Ràng buộc
LLM và Trello là dịch vụ ngoài, có rate limit, giới hạn context và khả năng lỗi. Transcript và evidence có thể chứa dữ liệu nhạy cảm. Kết quả AI luôn là đề xuất; JSON hợp lệ không đồng nghĩa với nội dung đúng.
DB transaction không bao trùm thao tác ghi trên Trello. Không thể hứa đảm bảo tuyệt đối một card trong mọi sự cố chỉ bằng transaction nội bộ; thiết kế phải phân biệt thất bại chắc chắn với kết quả chưa rõ và dừng tạo lại khi chưa đối soát.
## 2 3 Giới hạn vận hành khởi điểm
Các giá trị dưới đây là cấu hình thiết kế khởi điểm để kiểm thử, không phải số đo hiệu năng đã đạt. Có thể điều chỉnh sau benchmark, nhưng phải cập nhật cấu hình, thông báo UI và testcase cùng lúc.
| Cấu hình | Giá trị khởi điểm | Cách áp dụng |
| --- | --- | --- |
| File upload | 10 MiB | Kiểm tra cả client và backend |
| Văn bản trích xuất | 200000 ký tự | Áp dụng cho cả paste và file |
| DOCX giải nén | 50 MiB tổng, 20 MiB mỗi entry | Kiểm tra trước và trong parser |
| Job analysis hoạt động | 2 mỗi user | Vượt giới hạn trả 429 |
| Worker đồng thời | Analysis 2, sync 2 | Giới hạn toàn backend; điều chỉnh theo provider |
| LLM attempt | Tối đa 3 mỗi chunk | Bao gồm lần đầu và retry |
| Polling UI | 3 giây rồi tăng đến 15 giây | Chỉ poll khi panel đang mở |
| Lưu transcript | 30 ngày | Xóa cả raw, normalized và evidence nguồn |
| Log kỹ thuật đã loại dữ liệu nhạy cảm | 90 ngày | Không lưu prompt/token/secret |
Context budget được tính theo model: input transcript + instructions + metadata + output reserve + safety margin phải nằm trong context window. Không dùng số ký tự làm đại diện duy nhất cho token.

# 3 Yêu cầu hệ thống
## 3 1 Functional Requirements
| Mã | Yêu cầu | Điều kiện cần đáp ứng |
| --- | --- | --- |
| FR 01 | Nhập transcript | Paste hoặc một file .txt/.docx, không dùng đồng thời |
| FR 02 | Xem trước input | Hiển thị text parser và cảnh báo trước analysis |
| FR 03 | Metadata | Title tùy chọn; ngày họp và timezone phục vụ deadline |
| FR 04 | Validate input | Kiểm tra rỗng, loại file, giới hạn và lỗi parser |
| FR 05 | Normalize | Giữ nghĩa, thứ tự, speaker và timestamp khi có |
| FR 06 | Segment và evidence | Mỗi đoạn có ID và ánh xạ về nguồn |
| FR 07 | Job analysis | Trả 202, theo dõi tiến độ và khôi phục sau đóng panel |
| FR 08 | LLM extraction | Structured output qua LLMProvider |
| FR 09 | Validate output | Schema, nguồn evidence và quy tắc nghiệp vụ |
| FR 10 | Transcript dài | Chunking có thứ tự, UPDATE/CANCEL và consolidation |
| FR 11 | Review | Sửa, thêm, loại bỏ và chọn task |
| FR 12 | Resolve deadline | Giữ câu gốc, timezone và độ rõ ràng |
| FR 13 | Kết nối Trello | Cấp quyền, trạng thái kết nối, ngắt và kết nối lại |
| FR 14 | Chọn đích | Board/List hợp lệ và danh sách member theo Board |
| FR 15 | Resolve assignee | Đề xuất và xác nhận mapping; không chọn từ tên gần giống |
| FR 16 | Xác nhận phiên bản | Snapshot theo task version và đích Trello |
| FR 17 | Sync | Chỉ tạo card từ snapshot đã được người dùng xác nhận |
| FR 18 | Kết quả từng task | Card ID/URL, thành công, lỗi hoặc UNKNOWN |
| FR 19 | Retry và đối soát | Không tự tạo lại card khi kết quả chưa rõ |
| FR 20 | Lưu và tiếp tục | Khôi phục meeting, review draft và job |
| FR 21 | Xóa dữ liệu | Xóa meeting hoặc transcript theo quy tắc vòng đời |
| FR 22 | Evaluation | Dataset có quyền sử dụng và metric định nghĩa rõ |
## 3 2 Non Functional Requirements
| Nhóm | Yêu cầu thiết kế |
| --- | --- |
| Security | HTTPS, owner check mọi tài nguyên, JWT ngắn hạn, secret backend only |
| Reliability | Job lưu DB, recovery theo lease, kiểm soát yêu cầu đồng thời |
| AI Reliability | Structured output, source verification, warnings và human review |
| Maintainability | Module transcript, AI, task, job và Trello có boundary rõ |
| Privacy | Provider được công bố, retention rõ, evaluation opt-in riêng |
| Usability | Một thao tác xác nhận tạo card; lưu draft; kết quả lỗi có hành động cụ thể |
| Observability | Trace ID, job/chunk/sync attempt và lỗi đã loại nội dung nhạy cảm |
| Performance | Đo P50/P95, token và chi phí; giới hạn worker và retry |
## 3 3 Điều kiện bắt buộc của luồng
Mọi card phải truy xuất được về user, meeting, task và snapshot đã duyệt. Task version hoặc đích thay đổi trước khi enqueue phải làm xác nhận cũ mất hiệu lực. Một task có tối đa một sync item đang hoạt động hoặc chưa đối soát. Không biến kết quả phân tích một phần thành COMPLETED.
Một transcript ngắn có một câu phân công hợp lệ vẫn được phân tích. Không có action item là kết quả thành công với tasks rỗng, không phải lỗi AI.

# 4 Kiến trúc giải pháp
## 4 1 Architectural Style
Backend là một Spring Boot application chia module/package. PostgreSQL lưu domain data và job. Worker chạy trong cùng application ở MVP; chưa cần microservices, Redis hoặc message broker.
| Module | Trách nhiệm |
| --- | --- |
| auth | Đăng ký, đăng nhập, session và owner context |
| meeting | Input, metadata, transcript revision và lịch sử |
| transcript | Parser, normalization, segmentation, chunk plan |
| ai | Provider adapter, prompt, schema, output validation |
| task | Candidate, review, version và snapshot |
| job | Queue trong DB, lease, retry, recovery và progress |
| trello | OAuth, đích, member, payload, sync và reconciliation |
| evaluation | Dataset, ground truth và metric có quyền sử dụng |
| common | Error contract, trace ID và cấu hình chung |
## 4 2 Logical Architecture
Luồng dữ liệu được phân chia thành bốn đường. Extension gọi backend qua REST. Backend đọc/ghi PostgreSQL. AI module gọi External LLM API. Trello module gọi Trello API. PostgreSQL không gọi LLM hoặc Trello.
```text
Extension Side Panel
        | HTTPS REST
        v
Spring Boot API ----> PostgreSQL
        |                 ^
        +--> DB job worker|
                    |     |
                    +--> LLMProvider --> External LLM API
                    +--> TrelloAdapter --> Trello API
```
## 4 3 Deployment Architecture
Browser chạy extension đã đóng gói React. Backend Docker và PostgreSQL đặt trên môi trường server; API chỉ mở qua HTTPS. Outbound HTTPS từ backend tới provider được cấu hình riêng. Database không mở trực tiếp cho extension.
Đối với demo, localhost dùng cấu hình phát triển riêng và credentials riêng. Extension production chỉ có host permission cho backend production; không sử dụng wildcard cho mọi website.
## 4 4 Extension Architecture
Extension gồm sidepanel, components, api client, draft storage và service worker để mở panel/điều phối sự kiện nếu cần. Không cần content script đọc trang, tabs hoặc quyền truy cập Teams/Meet/Zoom trong MVP.
Access JWT của hệ thống lưu trong chrome.storage.session, giới hạn truy cập vào extension trusted contexts. MeetingId và jobId đang mở có thể lưu chrome.storage.local; không lưu raw transcript vào chrome.storage.sync. Backend là nguồn dữ liệu chính cho draft đã lưu.
Panel có thể gọi REST trực tiếp trong thời gian đang mở. Service worker không sở hữu tiến trình AI hoặc giữ transaction nghiệp vụ. Nếu worker dừng, mở lại panel sẽ truy vấn DB qua API [R1].
## 4 5 Worker và recovery
Worker claim job bằng transaction ngắn, khóa bản ghi phù hợp và gán lease_owner, lease_expires_at. Giao tiếp dịch vụ ngoài diễn ra ngoài DB transaction; không giữ khóa DB trong thời gian chờ HTTP.
Analysis job hết lease có thể chạy lại phần chưa hoàn tất theo chunk checkpoint. Sync item đã đánh dấu DISPATCHED mà worker chết phải chuyển UNKNOWN để đối soát, không enqueue một create mới. Lease hoặc khóa backend không phải cơ chế chống trùng được Trello bảo đảm.
## 4 6 LLM Provider Abstraction
Business logic sử dụng LLMProvider thay vì SDK trực tiếp. Adapter cung cấp model, context budget, tokenizer hoặc estimator, khả năng structured output và thông tin usage. Kết quả được chuyển về một schema domain thống nhất.
Provider phải được chọn từ danh sách đã công bố. Fallback mặc định tắt; chỉ bật cho provider được người dùng/đơn vị sử dụng chấp thuận nhận transcript. Tổng retry không vượt ngân sách job và không tự mở rộng sang provider mới.

# 5 Thiết kế chi tiết
## 5 1 Input và xem trước
Người dùng chọn Paste hoặc File. Đổi chế độ phải xác nhận thay thế draft chưa lưu trong UI; backend chỉ nhận đúng một nguồn. Backend tạo meeting và transcript revision sau khi xác thực input, parse file và lưu text, nhưng chưa gửi tới LLM.
Response trả preview, số ký tự, số segment và parser warnings. Title là tùy chọn; khi trống UI dùng tên hiển thị theo thời điểm tạo để tìm lại meeting, không đưa tên tự sinh vào prompt như một sự kiện cuộc họp.
Ngày họp là tùy chọn nếu không cần chuẩn hóa deadline tương đối. UI yêu cầu chọn timezone khi có ngày/giờ deadline; có thể đề xuất timezone trình duyệt và yêu cầu người dùng kiểm tra. Không tự lấy ngày upload làm ngày họp.
## 5 2 File Parsing
TranscriptParser chỉ chuyển file về raw text cùng source map; không trích task. TXT nhận UTF-8 và UTF-8 BOM; nếu encoding không đọc được, trả thông báo chuyển file sang UTF-8 thay vì âm thầm thay ký tự.
DOCX dùng Apache POI, đọc paragraph và table theo thứ tự trong body. Mỗi dòng nguồn có locator như paragraphIndex hoặc tableIndex/rowIndex/cellIndex. Headers, footers và comment không được nhập vào transcript mặc định; báo nếu nội dung chỉ nằm trong các phần này. Không tải tài nguyên ngoài, không chạy macro, không xử lý .docm.
Backend kiểm tra extension, MIME và cấu trúc thật; giới hạn compressed/uncompressed size, entry count và tỉ lệ giải nén bằng cơ chế bảo vệ parser. File hỏng, mã hóa, không có text hoặc vượt giới hạn trả lỗi có mã riêng.
## 5 3 Normalization và segmentation
Chuẩn hóa Unicode NFC, newline, whitespace trong câu, ký tự control và format speaker. Giữ thứ tự đoạn, dấu phủ định, dấu câu có nghĩa, mốc thời gian, biểu thức ngày và tên người. Không xóa tùy tiện các từ như không, thôi, bỏ, đổi, à không vì chúng có thể thay đổi quyết định.
Segment gồm segmentId, sequence, speaker nullable, timestamp nullable, text, normalizedStart/End và sourceLocator. Normalization phải giữ ánh xạ về raw source; evidence hiển thị câu nguồn và vị trí mở lại. Không bịa speaker/timestamp khi nguồn không có.
```json
{
  "segmentId": "seg-0042",
  "sequence": 42,
  "speaker": "Nam",
  "timestamp": "00:32:12",
  "text": "Long sửa login trước thứ Sáu nhé.",
  "sourceLocator": {"paragraphIndex": 12}
}
```
## 5 4 Tạo job phân tích
Sau khi xem trước, người dùng bấm Phân tích. Backend tạo job tham chiếu transcriptRevision, metadataVersion, provider/model và promptVersion. Những giá trị này được cố định cho lượt xử lý; không đọc metadata đang thay đổi giữa chừng.
Trong khi analysis đang chạy, sửa transcript/metadata bị từ chối bằng 409. Người dùng có thể hủy job hoặc chờ xong rồi tạo revision mới. UI không thể mất tiến trình chỉ vì đóng panel; job tiếp tục ở backend.
Thay transcript hoặc ngày họp sau analysis tạo revision mới và làm candidate chưa sync cũ không còn là kết quả hiện hành. Không ghi đè task đã sync. Lượt reanalysis tạo tập candidate mới, giữ lịch sử và cảnh báo task giống với card đã tồn tại để người dùng không tạo lại nhầm.
## 5 5 Job lifecycle
| Trạng thái analysis | Ý nghĩa | Hành động tiếp theo |
| --- | --- | --- |
| QUEUED | Chờ worker | Theo dõi hoặc hủy |
| PROCESSING | Đang parse context/gọi LLM/consolidate | Theo dõi tiến độ |
| COMPLETED | Tất cả phần cần thiết hợp lệ | Review task |
| PARTIAL_FAILED | Ít nhất một chunk chưa xử lý được | Retry chunk; chưa mở sync kết quả này |
| FAILED | Không thể tạo kết quả hợp lệ | Sửa input hoặc retry theo lỗi |
| CANCEL_REQUESTED | Đã yêu cầu dừng | Chờ HTTP đang chạy kết thúc |
| CANCELLED | Đã dừng và không publish kết quả | Sửa input hoặc tạo lượt mới |
Cancel là best effort; request đã gửi provider vẫn có thể tính phí. Worker chỉ publish candidate khi job hoàn tất và chưa bị hủy. Progress gồm stage, completedChunks, totalChunks; không hiển thị phần trăm giả trước khi biết tổng số chunk.
## 5 6 Token budget và chunking
Ưu tiên single-call nếu tổng token và thời gian dự kiến phù hợp giới hạn model/job. Dự trữ output cho schema; kết quả bị cắt do output limit phải coi là chưa hợp lệ, không parse một phần thành công.
Chunk được cắt tại ranh giới segment và giữ sequence toàn cục. Dùng overlap có giới hạn để giữ liên kết ở biên; task/event trùng trong overlap được deduplicate bằng nguồn, không chỉ task name. Tất cả chunk nhận cùng meeting context và bảng speaker đã xác minh khi có.
Chunk output gồm sự kiện CREATE, UPDATE, CANCEL, các trường được nói rõ và evidence IDs. UPDATE/CANCEL được giữ ngay cả khi chunk không chứa task mới. Một lớp reconciliation dùng các sự kiện cùng câu nguồn liên quan để xác định công việc cuối cùng. Nếu ngân sách consolidation cũng vượt giới hạn, chia nhóm theo sự kiện có liên quan và đánh dấu quan hệ chưa giải quyết; không bỏ sự kiện.
## 5 7 Trích xuất hành động
AI nhận diện công việc có hành động hoặc cam kết rõ. Câu báo lỗi đơn thuần và ý tưởng chưa được chấp nhận không tự biến thành task. Có thể trích task không nêu người hoặc hạn nếu có yêu cầu hành động rõ ràng.
Ví dụ: Payment API đang lỗi không đủ để tạo task sửa lỗi; Cần sửa Payment API, người phụ trách sẽ chốt sau là task hợp lệ với assignee thiếu. Mọi task AI phải tham chiếu evidence tồn tại trong revision đang phân tích.
## 5 8 Output Validation
Schema validation kiểm tra JSON, field type, enum, kích thước, ngày/giờ và cấu trúc reference. Backend tính lại warnings và resolution status theo dữ liệu nguồn, không tin tuyệt đối cờ needs_confirmation của model.
Source validation kiểm tra segmentId và range thuộc revision, khôi phục quote từ source và tránh evidence của chunk khác không liên quan. Business validation kiểm tra tên task, dữ liệu thiếu, correction/cancellation, deadline và membership. Các kiểm tra này giảm lỗi nhưng không chứng minh đầy đủ tính đúng ngữ nghĩa; human review vẫn bắt buộc.
Nếu JSON sai, chỉ retry giới hạn với schema và lỗi kiểm tra. Không ghi task từ output chưa hợp lệ. Nếu tất cả attempt thất bại, job FAILED và UI cho biết có thể nhập task thủ công.
## 5 9 Consolidation
Gộp task khi bằng chứng cho thấy cùng một công việc, không gộp chỉ vì tên gần giống. Giữ lịch sử CREATE/UPDATE/CANCEL theo sequence. Câu nói muộn hơn chỉ thay thế câu trước khi rõ là quyết định sửa/hủy; câu hỏi, giả định hoặc đề xuất sau cùng chưa được chấp thuận phải để cảnh báo.
Ví dụ Long làm login, sau đó À không, Mai làm login tạo một task giao Mai và giữ cả hai nguồn. Nếu sau đó Bỏ việc login, sprint này chưa làm thì task được đánh dấu CANCELLED và không vào danh sách tạo card. Nếu chưa biết hai câu nói cùng công việc, trả CONFLICTING_ASSIGNMENT cho review.
Sau consolidation phải validate lần cuối vì việc gộp có thể làm mất evidence hoặc tạo tổ hợp assignee/deadline chưa được nói trong nguồn.
## 5 10 Review và lưu draft
Một màn hình chính hiển thị danh sách task, checkbox, tên, assignee, hạn, priority, warning và evidence mở rộng. UI phân biệt đề xuất AI với trường đã được người dùng sửa. Thêm task thủ công đặt origin USER; không yêu cầu evidence AI.
Draft lưu backend bằng PATCH với expectedVersion. Autosave có debounce; nút Tạo bị khóa khi còn thay đổi chưa lưu hoặc request lưu đang lỗi. Trước khi xác nhận, UI hiển thị đủ năm ngày, giờ, timezone và nơi tạo card, không chỉ ngày/tháng.
Không có action item thì UI báo rõ, cho phép đọc lại transcript hoặc thêm task thủ công. Task không được chọn giữ PENDING_REVIEW; task loại bỏ chuyển REJECTED. Warning có nội dung cụ thể thay vì một cờ boolean duy nhất.
## 5 11 Trello Board và member resolution
Người dùng có thể phân tích trước khi kết nối Trello, nhưng phải chọn connection, Board và List trước xác nhận tạo card. Backend kiểm tra List thuộc Board, Board/List chưa đóng và user có quyền tạo card. Lấy member theo Board đích, không dùng danh sách người trong toàn tài khoản để tự gán.
Resolution status gồm MISSING, SUGGESTED, AMBIGUOUS, RESOLVED và NONE_SELECTED. Mapping từ tên gần giống chỉ là SUGGESTED, kể cả có đúng một kết quả. Mapping theo ID hoặc alias đã được user xác nhận cho connection/Board có thể RESOLVED sau khi kiểm tra membership còn hợp lệ.
Người dùng chọn member bằng Trello member ID hoặc chọn Không giao người. Nhiều match yêu cầu chọn; không có match có thể giữ tên gốc trong mô tả nhưng không gán member ngoài Board. Đổi connection/Board/List tăng destinationVersion; mapping và xác nhận liên quan được kiểm tra lại. Không đổi destination trong khi có item QUEUED, SYNCING hoặc UNKNOWN; backend trả 409 RESOURCE_BUSY để tránh tạo card theo một đích mà UI đã thay.
## 5 12 Deadline resolution
AI giữ deadline_raw, loại biểu thức và reference IDs. Backend chuẩn hóa các biểu thức có quy tắc rõ dựa trên ngày họp và timezone; tuyệt đối không lấy ngày upload làm mốc. MISSING, RESOLVED, AMBIGUOUS và NONE_SELECTED là các trạng thái khác nhau.
Ngày mai có thể tính từ ngày họp khi cách dùng từ rõ. Thứ Sáu, thứ Sáu tới, cuối tuần, sớm và trước thứ Sáu có thể mơ hồ; UI giữ câu gốc cùng giá trị đề xuất và yêu cầu xác nhận khi cần. Quy tắc trước và vào một ngày không được coi là đồng nhất.
Khi chỉ có ngày và user đã xác nhận, mặc định giờ 17:00 theo timezone được hiển thị, cho phép sửa. Lưu due_local và due_at UTC tương ứng; adapter gửi due theo ISO 8601. Khi có giờ chính xác trong nguồn thì không áp dụng giờ mặc định. Không đặt hạn chọn rõ ràng tạo due_at null.
Deadline trước ngày họp hoặc đã qua thời điểm tạo card là cảnh báo kiểm tra, không tự sửa sang một ngày tương lai. Ground truth và evaluation phải ghi nhận chính sách diễn giải đã dùng.
## 5 13 Priority và mô tả card
Priority chỉ có LOW, MEDIUM, HIGH hoặc null. Thiếu phát biểu về mức ưu tiên thì null; không suy mức ưu tiên chỉ từ deadline gần hoặc tên task. UI cho phép user đặt priority.
MVP đưa priority vào mô tả card nếu có; chưa tự tạo Trello label hoặc Custom Field. Description gồm mô tả task, tên người nguồn nếu chưa gán, meeting title nếu có và mã tham chiếu hệ thống. Evidence không tự gửi sang Trello; người dùng chọn đính kèm trích dẫn tối thiểu và được xem trước payload.
## 5 14 Xác nhận và snapshot
Nút Tạo N card gửi selected task IDs, expected versions, destinationVersion và lựa chọn đã xử lý cảnh báo. Backend kiểm tra owner, analysis hiện hành đã COMPLETED, draft đã lưu, member/deadline resolutions và trạng thái sync. Task manual có thể sync không cần kết quả AI, nhưng vẫn cần user xác nhận.
Trong một DB transaction, backend kiểm tra optimistic versions, tạo immutable snapshots và sync items, gán review_status APPROVED, sync_status QUEUED. Chỉ commit thành công mới trả 202. Đây là một hành động xác nhận rõ của người dùng dù backend kết hợp confirm và enqueue trong một endpoint.
Snapshot gồm task version, destination version, connection identity, Board/List, Trello member IDs, due_at, priority, description, lựa chọn đưa evidence và payload hash. Worker chỉ gửi snapshot này; không đọc task draft mới nhất để tạo card. Tính bất biến áp dụng trong vòng đời xử lý; sau retention, được scrub phần nội dung nhạy cảm và ghi audit, nhưng không sửa payload khi job còn active.
Task đang QUEUED/SYNCING/UNKNOWN bị khóa sửa trong MVP. Khi thất bại chắc chắn trước tạo card, sửa task có thể hủy snapshot cũ và yêu cầu duyệt lại. Task đã SYNCED chỉ xem và mở Trello.
| Vòng đời task | Trạng thái | Quy tắc chuyển |
| --- | --- | --- |
| Review | PENDING_REVIEW | Đề xuất hoặc draft còn editable |
| Review | APPROVED | Snapshot đã ghi trong approve-and-sync |
| Review | REJECTED | User loại bỏ; không được enqueue |
| Sync | NOT_SYNCED | Chưa có lượt tạo card |
| Sync | QUEUED | Item được lưu và chờ worker |
| Sync | SYNCING | Worker đang thực hiện snapshot |
| Sync | SYNCED | Có card ID đã xác minh |
| Sync | FAILED | Biết chắc lỗi; retry theo phân loại |
| Sync | UNKNOWN | Có thể đã ghi; chỉ đối soát trước create mới |
Sửa task sau FAILED an toàn đưa review về PENDING_REVIEW và sync về NOT_SYNCED sau khi supersede item cũ. Restore task REJECTED cũng trở về PENDING_REVIEW; không giữ approval cũ. Không cho REJECTED một item đang QUEUED/SYNCING/UNKNOWN hoặc đã SYNCED.
## 5 15 Đồng bộ Trello
Worker kiểm tra kết nối còn cùng tài khoản, quyền và đích snapshot còn hợp lệ ngay trước gửi. Kiểm tra này chỉ xác nhận tính hợp lệ; không tự thay member/đích. Nếu khác với snapshot, dừng và yêu cầu user sửa/duyệt lại.
Tạo card bằng một request gồm name, desc, idList, idMembers và due khi có; API Trello hỗ trợ các trường này [R2]. Khi nhận success, lưu cardId/url ngay trong transaction cập nhật sync item rồi đánh dấu SYNCED. Nếu đã có cardId, mọi retry sử dụng card đó, không gọi create lại.
Mỗi card có mã tham chiếu ổn định theo logical task ID, ví dụ AI_MTT_REF=<task UUID>. Mã này không chứa secret. Backend có unique logical task sync mapping, khóa enqueue và Idempotency-Key theo user/endpoint; những cơ chế này chặn duplicate nội bộ nhưng không thay thế đối soát dịch vụ ngoài.
## 5 16 UNKNOWN và retry
Một timeout, mất kết nối sau dispatch, worker chết sau dispatch hoặc DB không lưu được response success đều tạo UNKNOWN. Không tự chuyển các trường hợp này thành FAILED có thể tạo lại. Sync job khác cũng không được tạo card cho task UNKNOWN.
Reconciliation đọc card ở Board/List đích theo trang và khoảng thời gian phù hợp, so mã tham chiếu. Một match xác minh được thì liên kết card và chuyển SYNCED. Nhiều match thì báo DUPLICATE_DETECTED để user xử lý. Không thấy match không chứng minh create thất bại ngay; chờ có giới hạn và đối soát lại, sau đó giữ NEEDS_ACTION.
Nếu vẫn chưa rõ, UI cho phép mở Trello, nhập card URL/ID để backend xác minh và liên kết. Hành động Tạo lại sau đối soát cần cảnh báo nguy cơ trùng và xác nhận riêng; không dùng nút retry thông thường cho UNKNOWN. Giữ lịch sử các attempt.
Lỗi chắc chắn chưa dispatch hoặc phản hồi từ chối rõ ràng có thể FAILED; chỉ retry tự động lỗi tạm thời đã phân loại an toàn. 429 dùng Retry-After nếu có và backoff có jitter; không retry vô hạn. Lỗi quyền, member, Board hoặc token cần hành động sửa, không lặp create.
## 5 17 Kết quả một phần và recovery
Một batch có task thành công và task lỗi là PARTIAL_FAILED; giữ card thành công, không rollback Trello. UI liệt kê từng task và chỉ retry task phù hợp. UNKNOWN hiển thị riêng vì cần đối soát.
Sync job dùng QUEUED, RUNNING, COMPLETED, PARTIAL_FAILED, FAILED và NEEDS_ACTION. Nếu còn item đang chạy thì job RUNNING; khi không còn item hoạt động và còn UNKNOWN thì NEEDS_ACTION; tất cả SYNCED thì COMPLETED; có cả SYNCED và FAILED thì PARTIAL_FAILED; tất cả thất bại chắc chắn thì FAILED. Retry item cập nhật lại summary của cùng job.
Mở lại extension tải meeting, task versions, destination và job gần nhất từ backend. Access JWT hết hạn yêu cầu đăng nhập lại, sau đó quay về meeting cũ. Không bắt người dùng upload và trả phí phân tích lại chỉ vì panel đã đóng.

# 6 API Design
## 6 1 Quy ước chung
Base path là /api/v1. Dữ liệu JSON dùng camelCase; schema LLM là domain riêng, adapter chuyển tên trường. Mọi endpoint nghiệp vụ cần Bearer JWT của hệ thống. UUID trong ví dụ là giá trị minh họa và client không được giả định task/meeting IDs có thể đoán.
POST tạo job, approve-and-sync và retry dùng Idempotency-Key. Cùng key + cùng body trong cùng user/endpoint trả lại resource/result cũ; cùng key khác body trả 409 IDEMPOTENCY_CONFLICT. Khóa idempotency của sync được giữ cùng mapping để không tạo lại task đã sync sau khi key hết hạn.
GET danh sách dùng cursor và limit tối đa 100. Error có traceId, code và retryable; không trả stacktrace hoặc secret. Tài nguyên không thuộc user trả 404. Input hợp lệ nhưng vi phạm quy tắc dùng 422; stale version dùng 409.
## 6 2 Authentication API
| Method | Path | Kết quả |
| --- | --- | --- |
| POST | /auth/register | 201 user; password được hash |
| POST | /auth/login | 200 accessToken, expiresAt, sessionId |
| POST | /auth/logout | 204; revoke session hiện tại |
| GET | /auth/me | 200 user/session hiện tại |
JWT access có thời hạn khởi điểm 15 phút, lưu session ID/jti để logout có thể revoke. MVP không có refresh token riêng cho JWT hệ thống; hết hạn đăng nhập lại, draft đã lưu vẫn còn. Trello OAuth refresh token là loại khác, chỉ ở backend.
## 6 3 Meeting và input API
| Method | Path | Ý nghĩa |
| --- | --- | --- |
| POST | /meetings | Tạo meeting từ JSON paste hoặc multipart file |
| GET | /meetings | Lịch sử meeting của user |
| GET | /meetings/{id} | Metadata, revision, đích và job gần nhất |
| GET | /meetings/{id}/transcript | Preview/source với phân trang segment |
| PATCH | /meetings/{id}/input | Thay input/metadata, expectedVersion |
| DELETE | /meetings/{id}/transcript | Xóa nội dung nguồn theo lifecycle |
| DELETE | /meetings/{id} | Xóa meeting sau kiểm tra job/sync đang hoạt động |
POST JSON chỉ có transcriptText; multipart chỉ có file. Title, meetingDate, timezone là field chung. Không chấp nhận cả file và text. Backend parse trước khi trả 201 và chưa gọi LLM.
```json
{
  "title": "Sprint Planning",
  "meetingDate": "2026-09-30",
  "timezone": "Asia/Ho_Chi_Minh",
  "transcriptText": "Nam: Mai xử lý payment nhé."
}
```
Response 201 gồm meetingId, inputVersion, transcriptRevision, preview, segmentCount, warnings và analysisStatus NOT_STARTED. PATCH khi có job hoạt động hoặc snapshot đang QUEUED/SYNCING/UNKNOWN trả 409 RESOURCE_BUSY.
## 6 4 Analysis Job API
| Method | Path | Ý nghĩa |
| --- | --- | --- |
| POST | /meetings/{id}/analysis-jobs | 202 tạo job theo inputVersion |
| GET | /jobs/{jobId} | Progress, trạng thái, lỗi và resource links |
| POST | /jobs/{jobId}/cancel | 202 yêu cầu hủy analysis |
| POST | /jobs/{jobId}/retry | 202 retry phần analysis thất bại |
| GET | /meetings/{id}/tasks | Candidate của lượt hiện hành và manual task |
Request analysis gồm expectedInputVersion, providerId và processingPolicyId. Policy xác định model/prompt/schema và được server kiểm tra; client không gửi API key hoặc prompt hệ thống. Retry không nhận một transcript mới trong body.
```json
{
  "jobId": "analysis-job-id",
  "meetingId": "meeting-id",
  "status": "QUEUED",
  "stage": "WAITING",
  "completedChunks": 0,
  "totalChunks": null
}
```
COMPLETED chỉ publish khi toàn pipeline hợp lệ. PARTIAL_FAILED giữ chunk checkpoints nhưng task từ lượt này chưa được tạo card. GET job luôn kiểm tra owner thông qua meeting.
## 6 5 Task Review API
| Method | Path | Ý nghĩa |
| --- | --- | --- |
| POST | /meetings/{id}/tasks | 201 task origin USER |
| PATCH | /tasks/{id} | Sửa draft theo expectedVersion |
| DELETE | /tasks/{id} | 204 soft reject draft chưa sync |
| POST | /tasks/{id}/restore | Khôi phục task REJECTED còn editable |
| GET | /tasks/{id}/evidence | Câu nguồn, locator và warnings |
PATCH nhận taskName, description, trelloMemberId, memberDecision, deadlineRaw, dueLocal, timezone, deadlineDecision, priority và includeEvidenceInCard khi cần. Không cho client sửa origin, reviewStatus, syncStatus hoặc trelloCardId trực tiếp.
```json
{
  "expectedVersion": 3,
  "taskName": "Sửa Login API",
  "trelloMemberId": "trello-member-id",
  "memberDecision": "RESOLVED",
  "dueLocal": "2026-10-02T17:00:00",
  "timezone": "Asia/Ho_Chi_Minh",
  "deadlineDecision": "RESOLVED",
  "priority": null,
  "includeEvidenceInCard": false
}
```
Response trả version mới, dueAt UTC, resolution status và warnings tính lại. Khi chọn không giao người/không hạn, gửi NONE_SELECTED và field giá trị null. Evidence AI giữ nguồn gốc; user sửa task không thay quote nguồn.
## 6 6 Trello Connection API
| Method | Path | Ý nghĩa |
| --- | --- | --- |
| POST | /trello/connections/authorize | Tạo authorization transaction, trả authorizationUrl |
| GET | /trello/oauth/callback | Callback backend xử lý code/state |
| GET | /trello/connections | Trạng thái và Trello identity, không trả token |
| DELETE | /trello/connections/{id} | Ngắt kết nối và xóa token lưu |
| GET | /trello/connections/{id}/boards | Board có thể sử dụng |
| GET | /trello/boards/{id}/lists | List theo connection được chỉ định |
| GET | /trello/boards/{id}/members | Member của Board theo connection |
Mọi truy vấn Board/List/member đều yêu cầu connection thuộc user, không chỉ nhận một Trello ID tùy ý. Callback được correlate với authorization transaction đã tạo, kiểm tra state/PKCE, thời hạn và dùng một lần; không yêu cầu JWT query string. Từ chối cấp quyền không làm mất review draft.
## 6 7 Destination và resolve API
| Method | Path | Ý nghĩa |
| --- | --- | --- |
| PUT | /meetings/{id}/destination | Lưu connectionId, boardId, listId, expectedVersion |
| POST | /meetings/{id}/resolve-members | Gợi ý mapping theo Board và task versions |
| POST | /meetings/{id}/resolve-deadlines | Gợi ý chuẩn hóa theo input version |
Resolve chỉ đưa đề xuất và cập nhật draft với version checks; không duyệt hoặc tạo card. Lưu destination mới tăng destinationVersion và làm mapping/snapshot cũ mất hiệu lực khi còn editable.
## 6 8 Approve và Sync API
POST /meetings/{id}/approve-and-sync thay cho hai thao tác confirm và sync rời rạc trong v2. Request chứa task IDs được chọn, expectedVersions và expectedDestinationVersion; một transaction tạo snapshots và sync job. Nếu một task không hợp lệ, trả 422 theo task và không enqueue một phần của request này.
```json
{
  "expectedDestinationVersion": 2,
  "tasks": [
    {"taskId": "task-id-1", "expectedVersion": 4},
    {"taskId": "task-id-2", "expectedVersion": 1}
  ]
}
```
Response 202 gồm syncJobId, status QUEUED và taskCount. Worker xử lý từng item độc lập; request enqueue là all-or-none trong DB nhưng kết quả Trello có thể thành công một phần.
| Method | Path | Ý nghĩa |
| --- | --- | --- |
| GET | /sync-jobs/{id} | Kết quả từng task và batch summary |
| POST | /sync-items/{id}/retry | Retry FAILED an toàn, cùng snapshot |
| POST | /sync-items/{id}/reconcile | Đối soát UNKNOWN |
| POST | /sync-items/{id}/link-card | Xác minh và liên kết card đã tồn tại |
| POST | /sync-items/{id}/recreate | Tạo lại có xác nhận riêng sau đối soát |
Retry không nhận snapshot mới; muốn sửa task phải hủy snapshot thất bại có thể sửa rồi approve-and-sync lại. Retry trên SYNCED trả kết quả hiện có; trên UNKNOWN trả 409 RECONCILIATION_REQUIRED. Recreate cần expectedVersion, acknowledgementDuplicateRisk và quyền owner; luôn ghi audit event.
```json
{
  "status": "NEEDS_ACTION",
  "summary": {"synced": 1, "failed": 0, "unknown": 1},
  "results": [
    {"taskId": "task-id-1", "status": "SYNCED", "cardId": "card-id"},
    {"taskId": "task-id-2", "status": "UNKNOWN", "retryable": false}
  ]
}
```
## 6 9 Error Response
```json
{
  "timestamp": "2026-10-05T09:00:00Z",
  "traceId": "trace-id",
  "status": 409,
  "code": "STALE_VERSION",
  "message": "Task đã thay đổi. Tải lại để duyệt phiên bản mới.",
  "retryable": false,
  "details": [{"taskId": "task-id", "currentVersion": 5}]
}
```
| HTTP | Mã ví dụ | Hướng xử lý |
| --- | --- | --- |
| 400 | INVALID_INPUT, INVALID_ENCODING | Sửa request/encoding |
| 401 | SESSION_EXPIRED | Đăng nhập lại và khôi phục meeting |
| 404 | RESOURCE_NOT_FOUND | Không trả tài nguyên user khác |
| 409 | STALE_VERSION, RESOURCE_BUSY | Tải lại hoặc chờ job |
| 409 | RECONCILIATION_REQUIRED | Đối soát, không create retry |
| 413 | FILE_TOO_LARGE, TEXT_TOO_LARGE | Giảm kích thước input |
| 415 | INVALID_FILE_TYPE | Dùng .txt/.docx hợp lệ |
| 422 | UNRESOLVED_MEMBER, UNRESOLVED_DEADLINE | User giải quyết trước xác nhận |
| 422 | TRANSCRIPT_OVER_BUDGET | Chọn input phù hợp hoặc bật chunking đã kiểm thử |
| 429 | USER_JOB_LIMIT, RATE_LIMIT | Chờ thời gian server chỉ định |
| 502/503 | LLM_PROVIDER_ERROR, TRELLO_UNAVAILABLE | Theo dõi trạng thái job và retry theo phân loại |

# 7 Thiết kế dữ liệu
## 7 1 Quan hệ dữ liệu
USER sở hữu MEETING, AUTH_SESSION và TRELLO_CONNECTION. MEETING có TRANSCRIPT_REVISION, TRANSCRIPT_SEGMENT, ANALYSIS_JOB, TASK và DESTINATION. TASK có TASK_EVIDENCE, TASK_SNAPSHOT và SYNC_ITEM. SYNC_JOB nhóm SYNC_ITEM; mỗi item có nhiều SYNC_ATTEMPT. ANALYSIS_JOB có CHUNK_RESULT và PROCESSING_LOG.
Các bảng user-owned đều có owner relation kiểm tra qua service và query. PostgreSQL dùng UUID, TEXT, JSONB và TIMESTAMPTZ; không sử dụng LONGTEXT. FK bắt buộc và unique constraints phải thể hiện trong migration.
## 7 2 Users và Auth Sessions
| Bảng | Trường chính | Ràng buộc |
| --- | --- | --- |
| users | id, email, password_hash, created_at | PK UUID; email UNIQUE NOT NULL |
| auth_sessions | id, user_id, expires_at, revoked_at | FK NOT NULL; kiểm tra session trên request |
Password hash dùng thuật toán được Spring Security hỗ trợ với cost được benchmark; không lưu password thô. JWT không chứa transcript, Trello token hoặc quyền user được tự khai báo.
## 7 3 Trello Connections và Destinations
| Bảng | Trường chính | Ràng buộc |
| --- | --- | --- |
| trello_connections | id, user_id, trello_member_id, auth_type, status | Một connection ACTIVE mỗi user ở MVP |
| trello_connections | access_token_enc, refresh_token_enc, expires_at, scopes, key_version | Secret encrypted; token nullable sau disconnect |
| authorization_transactions | id, user_id, state_hash, pkce_verifier_enc, expires_at, consumed_at | TTL ngắn; one-use |
| meeting_destinations | meeting_id, connection_id, board_id, list_id, version | UNIQUE meeting_id; optimistic version |
| member_aliases | user_id, connection_id, board_id, alias, member_id, confirmed_at | Mapping được user xác nhận, theo đúng Board |
Không đặt token trong bảng cấu hình Board/List để tránh lặp secret. Connection chuyển REAUTH_REQUIRED khi refresh thất bại hoặc bị revoke. Reconnect cùng Trello identity vẫn phải kiểm tra quyền; đổi identity không tự sử dụng lại snapshot cũ.
## 7 4 Meetings và transcript revisions
| Bảng | Trường chính | Ràng buộc |
| --- | --- | --- |
| meetings | id, user_id, title, meeting_date, timezone | FK owner NOT NULL; metadata nullable theo quy tắc |
| meetings | input_version, current_revision_id, current_analysis_job_id | Optimistic version và kết quả hiện hành |
| meetings | created_at, updated_at, deleted_at | TIMESTAMPTZ |
| transcript_revisions | id, meeting_id, revision, source_type, content_hash | UNIQUE meeting_id/revision; hash theo owner, không global dedup |
| transcript_revisions | raw_content, normalized_content, expires_at, deleted_at | TEXT nullable sau purge |
| transcript_segments | id, revision_id, sequence, speaker, timestamp_raw, text, source_locator | UNIQUE revision_id/sequence |
Source file chỉ giữ trong thời gian parser xử lý, xóa sau parse thành công hoặc thất bại. content_hash phát hiện input lặp trong user/meeting, không dùng để trả dữ liệu của người khác hoặc đảm bảo task/card không trùng. FK nguồn của task/evidence dùng SET NULL hoặc được scrub trong transaction purge; không xóa task đã duyệt chỉ vì source segment hết retention.
## 7 5 Jobs và chunk results
| Bảng | Trường chính | Ràng buộc |
| --- | --- | --- |
| analysis_jobs | id, meeting_id, revision_id, input_version, status, stage | Một job active mỗi meeting |
| analysis_jobs | provider, model, prompt_version, schema_version, policy_id | Cố định theo attempt |
| analysis_jobs | lease_owner, lease_expires_at, attempt_count, next_retry_at | Recovery và backoff |
| analysis_jobs | completed_chunks, total_chunks, error_code, created_at, completed_at | Progress và audit |
| chunk_results | job_id, chunk_index, segment_range, status, output_json, attempts | UNIQUE job_id/chunk_index |
Output JSON có evidence refs và event history nên có thể nhạy cảm; purge cùng transcript. Log usage tách khỏi nội dung để vẫn đo chi phí sau purge.
## 7 6 Tasks và evidence
| Trường task | Type | Quy tắc |
| --- | --- | --- |
| id, meeting_id, analysis_job_id | UUID | analysis_job_id null cho origin USER |
| origin, source_revision_id | VARCHAR, UUID | AI hoặc USER; nguồn có thể null sau purge |
| task_name, description | VARCHAR(255), TEXT | task_name NOT NULL |
| assignee_raw, trello_member_id | TEXT, VARCHAR | Lưu tên nguồn và ID đã chọn riêng |
| member_resolution | VARCHAR | MISSING/SUGGESTED/AMBIGUOUS/RESOLVED/NONE_SELECTED |
| deadline_raw, due_local, due_at, timezone | TEXT, timestamp, timestamptz, VARCHAR | due_at null khi không hạn |
| deadline_resolution | VARCHAR | MISSING/AMBIGUOUS/RESOLVED/NONE_SELECTED |
| priority, warnings | VARCHAR, JSONB | priority nullable; warnings do server tính |
| review_status, sync_status | VARCHAR | Hai vòng đời độc lập |
| version, include_evidence_in_card | BIGINT, BOOLEAN | Optimistic lock; default evidence false |
| trello_card_id, trello_card_url | VARCHAR, TEXT | Mapping sau success hoặc reconciliation |
| created_at, updated_at, rejected_at | TIMESTAMPTZ | Audit |
task_evidence gồm task_id, segment_id, field_role, start/end và source_revision_id; field_role xác định TASK, ASSIGNEE, DEADLINE hoặc PRIORITY. Quote được lấy từ segment khi hiển thị, không lưu quote model làm nguồn chuẩn. Sau purge, trả sourceAvailable=false và không còn quote.
## 7 7 Snapshots và sync
| Bảng | Trường chính | Ràng buộc |
| --- | --- | --- |
| task_snapshots | id, task_id, task_version, destination_version, payload_json, payload_hash, approved_by, approved_at | Immutable; chứa đích và lựa chọn đã duyệt |
| sync_jobs | id, meeting_id, user_id, status, created_at, completed_at | Nhóm theo một lần bấm tạo |
| sync_items | id, sync_job_id, task_id, snapshot_id, status, card_id, card_url | Khóa chống task active/UNKNOWN/SYNCED trùng |
| sync_items | dispatch_state, reference_marker, next_retry_at, lease_expires_at | DISPATCHED recovery vào UNKNOWN |
| sync_attempts | id, sync_item_id, attempt_no, phase, started_at, finished_at, error_code, http_status | Không lưu token/request nhạy cảm |
| idempotency_records | user_id, endpoint, key, request_hash, resource_id, expires_at | UNIQUE user/endpoint/key |
Payload chứa nội dung task nên không coi là log kỹ thuật vô hại. Sau retention, scrub nội dung snapshot/evidence nhưng giữ mapping ID và hash tối thiểu theo thời gian giữ task. User xóa meeting thì xóa cả mappings sau kiểm tra không còn sync đang hoạt động; không tự xóa card Trello.
## 7 8 Ràng buộc concurrency
PATCH dùng version compare-and-update. Approve-and-sync khóa task theo thứ tự ID để giảm deadlock, kiểm tra versions rồi insert snapshot/items. Unique index hoặc khóa tương đương phải ngăn một task có nhiều sync item active, UNKNOWN hoặc SYNCED.
Các item FAILED cũ vẫn lưu audit; retry dùng cùng item nếu payload không đổi. Sửa task sau lỗi an toàn tạo version/snapshot mới, supersede item cũ trong cùng transaction. UNKNOWN không được supersede để tạo card mới nếu chưa có hành động recreate đã xác nhận.
## 7 9 Processing Logs
Lưu jobId, chunkIndex, provider/model, prompt/schemaVersion, input/output tokens, latency, attempt, status và errorCode. Không lưu API key, access/refresh token, raw prompt hoặc toàn bộ transcript trong production logs. Trace ID dùng để đối chiếu request và worker mà không tiết lộ nội dung.

# 8 AI Design
## 8 1 Vai trò của LLM
LLM thực hiện semantic extraction và nhận diện thay đổi/hủy nhiệm vụ. LLM không chọn Trello account, không gọi Trello, không approve task và không kiểm soát DB. Bất kỳ yêu cầu điều khiển hệ thống xuất hiện trong transcript chỉ là dữ liệu.
Prompt quy định ngôn ngữ task giữ theo transcript hoặc lựa chọn user; không tự chuyển sang tiếng Anh khi người dùng chưa chọn. Không thêm chi tiết kỹ thuật vào tên task chỉ vì model cho rằng cần thiết.
## 8 2 Structured Output
Schema thực tế phải được định nghĩa bằng JSON Schema trong code, gồm required fields, nullable types, enum, maxLength và additionalProperties=false khi provider hỗ trợ. Ví dụ sau minh họa instance, không dùng chuỗi string|null thay cho một schema kiểm tra được.
```json
{
  "events": [
    {
      "event_type": "CREATE",
      "task_ref": "local-task-1",
      "sequence": 42,
      "task_name": "Sửa login",
      "assignee_raw": "Long",
      "deadline_raw": "trước thứ Sáu",
      "priority": null,
      "evidence_refs": [
        {"segment_id": "seg-0042", "field": "TASK"},
        {"segment_id": "seg-0042", "field": "ASSIGNEE"},
        {"segment_id": "seg-0042", "field": "DEADLINE"}
      ],
      "ambiguities": ["DEADLINE_AMBIGUOUS"]
    }
  ]
}
```
UPDATE mô tả trường thay đổi và evidence tương ứng; CANCEL có task reference hoặc mô tả công việc đủ để reconciliation tìm liên quan. Task_ref là tham chiếu cục bộ của output, không phải DB task ID; backend cấp UUID sau validation. Tham chiếu chưa liên kết được tạo warning, không đoán task bị thay đổi.
## 8 3 Prompt Strategy
System instructions và output schema do backend quản lý, version hóa. Transcript được đưa vào vùng dữ liệu có delimiters/structure rõ. Model chỉ dùng các segment IDs có trong request và không bịa quote, speaker, ngày hoặc member ID.
Prompt phân biệt lời giao việc, lời nhận việc, câu hỏi, báo lỗi, ý tưởng, correction và cancellation. Mọi giá trị có bằng chứng phải liên kết nguồn. Priority không được suy từ deadline. Câu nói cuối chưa chắc là quyết định cuối; cần đủ ngữ cảnh xác nhận.
## 8 4 Human review và provenance
UI hiển thị evidence cùng dấu hiệu trường do AI đề xuất, do backend chuẩn hóa hoặc do user sửa. Chỉnh sửa của user là quyết định nghiệp vụ có audit, không làm evidence gốc biến thành bằng chứng cho một giá trị mới.
Candidate cần kiểm tra nhưng không bắt user điền mọi trường tùy chọn. User có thể xác nhận không hạn/không người; hệ thống vẫn không được tự gán phần thiếu. needsConfirmation là trường derived tiện hiển thị từ warnings, không dùng làm quyền tự tạo card.
## 8 5 Lỗi provider và giới hạn chi phí
Mỗi job ghi policy/provider/model thực dùng và token usage. Timeout, 429 và lỗi tạm thời được retry có giới hạn theo ngân sách. Không tự fallback khi chưa có policy chấp thuận provider nhận dữ liệu. Khi chuyển model/provider được phép, output phải qua schema và business validation như lượt đầu.
Trong MVP, chốt một provider implementation sau benchmark và giữ interface để mở rộng. Không cần triển khai nhiều adapter ngay từ đầu để chứng minh abstraction.

# 9 Evaluation và kiểm thử
## 9 1 Mục tiêu đánh giá
Đánh giá AI quality, system reliability và hiệu quả sử dụng. Kết quả AI tốt không thay thế kiểm thử tạo card, xác nhận phiên bản và recovery. Số đo trong báo cáo phải ghi model, prompt/schemaVersion, dataset version và chính sách deadline.
## 9 2 Dataset và ground truth
Chuẩn bị 30-50 transcript có quyền sử dụng, phân tầng Việt/Anh, ít/nhiều speaker, thiếu metadata, noise, tên trùng, deadline tương đối, correction/cancellation, task trùng, transcript không có task và transcript dài. Tách tập phát triển prompt và tập kiểm tra giữ riêng theo meeting, tránh cùng biến thể xuất hiện ở cả hai.
Ground truth do người đánh giá tạo, một người khác kiểm tra các case mơ hồ và thống nhất bất đồng. Ghi cả action item, assignee, deadline_raw, interpretation policy, evidence IDs và trạng thái cancellation. Trường không có đủ căn cứ được gán null/ambiguous, không ép thành một ngày hoặc một người.
## 9 3 AI Quality Metrics
| Metric | Định nghĩa |
| --- | --- |
| Task precision | Task đúng / task dự đoán |
| Task recall | Task đúng / task ground truth |
| Task F1 | Trung bình điều hòa precision và recall |
| Assignee accuracy | Exact identity match trên task đã match, tách null và ambiguous |
| Deadline accuracy | Exact normalized date/time theo policy, tách ambiguous |
| Evidence validity | Reference tồn tại trong đúng revision |
| Evidence support | Đánh giá nguồn có hỗ trợ từng trường task/assignee/deadline/priority |
| Cancellation accuracy | Task bị hủy đúng và không còn được đề xuất tạo card |
| Unsupported field rate | Số giá trị có nội dung nhưng thiếu bằng chứng / số giá trị dự đoán |
Task matching phải one-to-one dựa trên cùng hành động/đối tượng trong rubric; không dùng chính model đang đánh giá làm trọng tài duy nhất. Báo macro theo transcript và micro toàn dataset. Với transcript không có task, báo số task tạo sai và tỷ lệ meeting có false positive; không gọi đó là FPR nếu chưa định nghĩa true negative.
## 9 4 Preprocessing Evaluation
So sánh raw và processed transcript với cùng model, prompt, output budget và dataset. Ghi token/latency/cost bên cạnh chất lượng để xem normalization có cải thiện thực tế. Với chunking, bổ sung các cặp sự kiện cách xa nhau; overlap đơn thuần không được coi là đã giải quyết correction.
## 9 5 End to End Usability
So sánh nhập Trello thủ công với extension theo thiết kế crossover có cân bằng thứ tự để giảm hiệu ứng học. Đo tổng thời gian tới khi có card đúng, bao gồm AI wait, review, sửa lỗi và retry; đo số trường sửa, chuyển màn hình, task đúng và duplicate card.
Đo riêng onboarding/Trello connect và sử dụng lặp lại, không loại bỏ chi phí kết nối để làm kết quả đẹp hơn. Số người tham gia và các hạn chế mẫu phải ghi rõ trong báo cáo evaluation.
## 9 6 Acceptance test quan trọng
| ID | Tình huống | Kết quả bắt buộc |
| --- | --- | --- |
| AT 01 | Paste một câu giao việc ngắn | Phân tích được; không báo quá ngắn |
| AT 02 | Transcript không có action item | COMPLETED, tasks rỗng |
| AT 03 | File hỏng/encoding sai/ZIP vượt giới hạn | Từ chối có mã lỗi, không gọi LLM |
| AT 04 | DOCX có paragraph xen bảng | Giữ đúng thứ tự và source locator |
| AT 05 | Hai member tên Long | AMBIGUOUS; không tự gán |
| AT 06 | Một tên gần khớp | Chỉ SUGGESTED nếu chưa xác nhận alias |
| AT 07 | Deadline tương đối thiếu ngày họp | Giữ raw và AMBIGUOUS |
| AT 08 | Chỉ ngày đã xác nhận | Hiển thị giờ mặc định/timezone trước sync |
| AT 09 | Không có căn cứ priority | priority null |
| AT 10 | Evidence reference giả | Output bị từ chối hoặc cảnh báo không đủ nguồn |
| AT 11 | Correction qua nhiều chunk | Một task với phân công đúng, đủ nguồn |
| AT 12 | CANCEL ở chunk cuối | Không tạo task bị hủy |
| AT 13 | Một chunk lỗi | PARTIAL_FAILED; không sync kết quả như hoàn tất |
| AT 14 | Đóng panel lúc analysis | Job vẫn chạy; mở lại thấy kết quả |
| AT 15 | Backend restart lúc analysis | Recovery checkpoint, không mất meeting |
| AT 16 | JWT hết hạn lúc review | Đăng nhập lại và tiếp tục draft |
| AT 17 | Hai tab sửa một task | Một update bị 409, không mất sửa âm thầm |
| AT 18 | Đổi Board sau mapping | Tăng version, kiểm tra member lại |
| AT 19 | Click tạo hai lần/cùng key | Một sync request; không thêm card |
| AT 20 | Hai key khác nhau sync cùng task | Khóa domain ngăn create đồng thời |
| AT 21 | Create success rồi DB lưu lỗi | UNKNOWN, đối soát trước tạo lại |
| AT 22 | Timeout sau dispatch | Không auto retry create; UNKNOWN |
| AT 23 | Worker chết sau dispatch | Lease recovery sang UNKNOWN |
| AT 24 | Retry khi đã có card ID | Không tạo card mới |
| AT 25 | Batch 2 thành công, 1 lỗi | Giữ 2 card; retry đúng item lỗi |
| AT 26 | Token revoke/quyền mất | Yêu cầu reconnect/sửa, không lặp create |
| AT 27 | User khác gọi task/job/sync API | 404; không lộ dữ liệu |
| AT 28 | Transcript chứa instruction tấn công | Không tạo action ngoài luồng đã duyệt |
| AT 29 | Xóa nguồn/retention | Raw, normalized, evidence/chunk output bị purge |
| AT 30 | Fallback chưa được chấp thuận | Không gửi transcript provider khác |
## 9 7 Điều kiện hoàn tất MVP
Tất cả acceptance test về owner, xác nhận, duplicate nội bộ và UNKNOWN phải pass trước demo ghi Trello thật. Dataset AI được báo cáo theo từng nhóm, không chỉ một accuracy. Ngưỡng precision/recall và P95 latency được chốt sau baseline benchmark, trước thử nghiệm người dùng; không coi số mục tiêu là kết quả đã đạt.
Single-call phải chạy end-to-end trước khi mở chunking. Chunking chỉ được bật khi AT 11-13 và các case cancellation/correction xa nhau pass. Không công bố hiệu quả giảm thời gian nếu chưa đo toàn workflow.

# 10 Bảo mật và riêng tư
## 10 1 Authentication và quyền tài nguyên
HTTPS bắt buộc ngoài localhost phát triển. Backend kiểm tra JWT/session, owner và quan hệ tài nguyên trên mọi endpoint, kể cả get job, evidence, retry và callback transaction. Không dựa vào UUID khó đoán làm cơ chế bảo vệ.
CORS chỉ cho extension origin và frontend callback được cấu hình; host permission chỉ cho backend cần dùng. Tránh log Authorization, URL callback chứa code và token response. Password validation, rate limit login và session revoke được triển khai trong auth module.
## 10 2 Trello Authorization
V3 chọn OAuth 2.0 confidential client với authorization code và PKCE; backend giữ client secret, code verifier và thực hiện code exchange [R3]. Transaction có state ngẫu nhiên, TTL khởi điểm 10 phút, gắn user/session, dùng một lần và callback URL đã đăng ký. Extension chỉ nhận trạng thái kết nối, không nhận Trello access/refresh token.
Scopes giới hạn cho đọc identity/Board và ghi card; offline_access dùng khi cần refresh. Đối chiếu scopes với tài liệu và cấu hình app khi triển khai. Lưu token mã hóa bằng khóa ngoài DB; refresh được serialize cho từng connection và lưu token mới atomically để tránh dùng refresh token cũ đồng thời.
Đăng nhập hệ thống và kết nối Trello là hai bước riêng. Từ chối consent trả trạng thái DENIED về transaction; không xóa meeting. Token revoke hoặc refresh không phục hồi được đưa connection REAUTH_REQUIRED. Disconnect dừng các job chưa dispatch, xóa token và thông báo item đã dispatch cần kết nối lại để đối soát.
## 10 3 Transcript và retention
Trước analysis, UI thông báo provider nhận transcript và thời gian lưu nguồn. Đính kèm file tạo preview ở backend; chỉ nút Phân tích mới cho phép gửi LLM. Việc đưa dữ liệu vào evaluation cần opt-in riêng, không suy ra từ consent phân tích.
Mặc định thiết kế giữ nguồn 30 ngày kể từ tạo revision. Job đang chạy hoặc sync UNKNOWN có thể trì hoãn purge tối đa 24 giờ để kết thúc/cancel và giữ dữ liệu tối thiểu; hết hạn vẫn scrub nguồn, xử lý đối soát bằng mã task/card. Cảnh báo retention không có nghĩa được giữ source vô hạn.
Purge xóa raw, normalized, segments, quotes, chunk output và phần evidence trong snapshots. Task do user duyệt và card mapping giữ để tiếp tục xem và chống create lại; nội dung task cũng có thể nhạy cảm và được bảo vệ theo owner. User có thể xóa meeting khi không còn job active/UNKNOWN chưa xử lý; xóa nội bộ không tự xóa card Trello.
User yêu cầu xóa khi job đang chạy thì cancel và chờ dừng publish; job đã dispatch card chuyển quy trình đối soát. Backup có thời gian giữ riêng khởi điểm 30 ngày và dữ liệu xóa sẽ hết vòng backup theo lịch; không tuyên bố xóa tức thời khỏi mọi bản backup.
## 10 4 Prompt Injection và UI Rendering
Transcript là dữ liệu không tin cậy. Model không được cấp tool tạo card; service layer chỉ sử dụng structured fields được kiểm tra. Board/List/member ID chỉ đến từ lựa chọn đã xác minh, không đến từ câu lệnh trong transcript.
Task/evidence render như text, escape HTML; không dùng dangerouslySetInnerHTML cho nội dung AI. Link card chỉ mở sau khi validate URL/host Trello. Ngăn output đưa nội dung thành script hoặc lệnh thực thi.
## 10 5 Secrets và logging
LLM key, Trello client secret, encryption key, DB credentials và JWT signing key chỉ có ở backend/Secret Manager. Không commit secrets, không đóng gói vào extension, không log raw request/response dịch vụ ngoài. Key version hỗ trợ rotation; lỗi giải mã không làm token xuất hiện trong thông báo.

# 11 Công nghệ sử dụng
| Hạng mục | Lựa chọn thiết kế | Lý do |
| --- | --- | --- |
| Browser Extension | Chrome Manifest V3 và Side Panel | Workspace cạnh trình duyệt, quyền tối thiểu |
| Extension UI | React | Review state và component |
| Backend | Java 17+ và Spring Boot 3.x | Modular monolith và worker cùng ứng dụng |
| ORM | Spring Data JPA | Domain persistence và optimistic lock |
| Database | PostgreSQL | Quan hệ, JSONB và job queue trong DB |
| File parser | Apache POI và TXT parser | .docx/.txt cùng source map |
| AI | External LLM API qua LLMProvider | Một adapter MVP, có thể mở rộng |
| Trello | REST API và OAuth 2.0 confidential | Token backend only |
| Auth | Spring Security và JWT/session | Owner check và logout revoke |
| Deployment | Docker | Backend đóng gói cùng cấu hình riêng |
| Evaluation | Dataset và Python/Java scripts | Metric tái lập theo version |
Dependency versions cụ thể được khóa trong build file khi khởi tạo repo và kiểm tra tương thích, không suy ra tự động từ tên framework. Chọn model sau benchmark structured output, tiếng Việt, context, latency và chi phí. Extension bundle không tải remote code.

# 12 Triển khai và vận hành
## 12 1 Development
Chrome unpacked extension gọi localhost backend, PostgreSQL dùng Docker và provider credentials dùng riêng cho phát triển. Trello integration tests dùng Board thử nghiệm có member/quyền được kiểm soát; không chạy test create trên Board thật của user.
Mock LLM và Trello để kiểm thử timeout, response mất, rate limit và worker restart. Test end-to-end thực chỉ dùng dữ liệu giả hoặc dữ liệu có quyền sử dụng. Không đưa transcript người dùng vào fixture/repo.
## 12 2 Demo và Production
Deploy backend Docker sau reverse proxy HTTPS, PostgreSQL có backup và migration có version. API, worker và scheduler dùng cùng codebase; nếu tăng replica phải kiểm thử DB claim/lease và unique constraints, không dựa vào synchronized trong một JVM để chống trùng.
Health check tách ứng dụng sống, DB sẵn sàng và dependency ngoài. LLM/Trello unavailable không làm liveness fail liên tục; readiness/job status thể hiện khả năng xử lý. Graceful shutdown ngừng claim mới; item đã dispatch chưa có kết quả vẫn recovery vào UNKNOWN.
## 12 3 Configuration
| Nhóm | Biến cấu hình |
| --- | --- |
| Backend | DATABASE_URL, JWT_SIGNING_KEY, TOKEN_ENCRYPTION_KEY |
| Trello | TRELLO_CLIENT_ID, TRELLO_CLIENT_SECRET, TRELLO_CALLBACK_URL |
| Provider | LLM_PROVIDER_ID, LLM_MODEL, LLM_API_KEY, LLM_TIMEOUT |
| Pipeline | CHUNKING_ENABLED, OUTPUT_TOKEN_RESERVE, MAX_LLM_ATTEMPTS |
| Worker | ANALYSIS_CONCURRENCY, SYNC_CONCURRENCY, JOB_LEASE_SECONDS |
| Input | MAX_UPLOAD_BYTES, MAX_EXTRACTED_CHARS, DOCX_UNZIP_LIMIT |
| Privacy | TRANSCRIPT_RETENTION_DAYS, LOG_RETENTION_DAYS |
| Extension | BACKEND_ORIGIN, EXTENSION_ORIGIN_ALLOWLIST |
Model/prompt/schema versions được log cùng job. Timeout và lease phải thống nhất: lease được heartbeat gia hạn khi worker còn chạy; quá hạn vẫn không cho create lại một item DISPATCHED. Các cấu hình nhạy cảm lấy từ secret store, không hiển thị trong endpoint config UI.
## 12 4 Monitoring và runbook
Theo dõi job age, queue depth, P50/P95 latency, invalid output rate, UNKNOWN count, duplicate detected, token usage và cost. Alerts dựa trên ngưỡng benchmark; log không có text meeting. Trace ID nối API, worker và attempts.
UNKNOWN tăng thì tạm dừng tạo mới cho các task liên quan và kiểm tra reconciliation; không xử lý bằng retry create hàng loạt. Provider lỗi thì job retry có giới hạn; Trello token lỗi thì chuyển REAUTH_REQUIRED và giữ review. Database outage sau dispatch phải điều tra theo marker trước khi mở retry.

# 13 Xử lý lỗi và rủi ro
| Rủi ro | Hậu quả | Biện pháp thiết kế |
| --- | --- | --- |
| Timeout create | Có thể tạo card nhưng mất response | UNKNOWN, marker và reconciliation |
| Click hoặc sync đồng thời | Card trùng nội bộ | Idempotency và domain unique/lock |
| Sửa sau duyệt | Card khác nội dung đã chấp thuận | Snapshot bất biến và version checks |
| Đổi Board | Member/đích không còn hợp lệ | Destination version và kiểm tra lại |
| Worker/extension dừng | Mất tiến trình | DB job, lease, checkpoint và reload API |
| Chunk thiếu correction/cancel | Task sai cuối cuộc họp | Event schema, source sequence và reconciliation |
| LLM invalid/hallucination | Sai task hoặc field | Schema/source checks, warnings, review |
| Deadline mơ hồ | Card có hạn sai | deadline_raw, timezone và xác nhận diễn giải |
| Member tên gần giống | Giao sai người | SUGGESTED, alias xác nhận, member ID |
| 429 dịch vụ ngoài | Trễ hoặc lặp request | Retry-After, bounded backoff và concurrency limit |
| Token hết quyền | Create bị từ chối | REAUTH_REQUIRED và sửa trước dispatch |
| DOCX bất thường | Parser resource exhaustion | Giới hạn giải nén, entry và size |
| Transcript nhạy cảm | Lộ qua log/evidence/provider | Owner checks, policy, retention và opt-in |
| False positive | Tạo việc chưa được thống nhất | Rubric action item và manual review |
| Reanalysis cùng input | Task giống card đã tạo | Lịch sử, warning và mapping hiện có |
Phân loại retry phải được lập trong code theo loại lỗi và phase dispatch, không chỉ HTTP status. Đặc biệt 5xx từ create endpoint có thể là kết quả chưa rõ; mặc định UNKNOWN nếu chưa chứng minh chưa ghi. Không rollback tự động các card thành công trong batch.

# 14 Roadmap triển khai
## 14 1 Backend foundation
Khởi tạo repo, migration, auth/session, owner checks, error contract và module boundaries. Xây meeting/input/revision/segment cùng preview. Exit criteria là input .txt/.docx/paste và quyền tài nguyên được kiểm thử trước khi kết nối provider thật.
## 14 2 Job và pipeline
Triển khai DB queue, worker, lease/recovery, polling, cancellation và input version. Hoàn thiện normalization/source map và token budget. Kiểm thử restart analysis và re-open panel; không giữ job trong RAM làm nguồn trạng thái.
## 14 3 LLM và evaluation baseline
Một adapter, prompt/schema version, source validation và candidate persistence. Dựng dataset 30-50 transcript từ đầu; chạy baseline raw/processed, no-task, missing fields và prompt injection. Chốt model/policy và ngân sách dựa trên số đo.
## 14 4 Review và Extension
Side Panel có preview, progress, saved draft, evidence, warnings, manual task, autosave/version conflict và lịch sử. Đăng nhập lại khôi phục được meeting. Test thủ công sớm trên màn hình sidebar thật để kiểm tra mức độ dễ đọc.
## 14 5 Trello vertical slice
Kết nối OAuth, Board/List/member, resolution, deadline timezone, snapshot và approve-and-sync. Tạo một card thử nghiệm bằng payload đầy đủ, lưu mapping và mở URL. Hoàn thiện UNKNOWN/reconciliation và test lỗi trước khi demo batch.
## 14 6 Transcript dài
Xây event CREATE/UPDATE/CANCEL, overlap theo segment và consolidation. Chạy test correction/cancellation xa nhau, output reserve và lỗi một chunk. Chỉ bật CHUNKING_ENABLED khi tiêu chí chương 9 đạt; cập nhật giới hạn UI tương ứng.
## 14 7 Reliability và privacy
Kiểm thử concurrent tabs, repeated keys, token revoke, timeout sau dispatch, DB lỗi, worker kill và purge. Kiểm tra log/secret và đánh giá retention thực sự xóa mọi bản sao nguồn. Chỉ dùng Trello Board thử nghiệm.
## 14 8 User evaluation và demo
Đo workflow đầy đủ, gồm onboarding và AI wait; sửa usability rồi khóa phiên bản demo. Báo cáo quality, reliability, thời gian và các hạn chế mẫu. Demo có case thành công, ambiguous member và UNKNOWN có hành động xử lý rõ.
Mỗi chặng cần ghi việc đã làm, kiểm tra đã chạy, kết quả, vấn đề và context tiếp nối. Không ghi kiểm thử đã pass nếu mới chỉ hoàn thiện đặc tả.

# 15 Thay đổi từ phiên bản 2
| Nội dung v2 | Thiết kế v3 | Lý do |
| --- | --- | --- |
| Analyze trả COMPLETED trực tiếp | Input preview và analysis job 202 | Không phụ thuộc panel hoặc request dài |
| Resolve member sau confirm | Resolve theo Board trước xác nhận | Người dùng duyệt đúng người/đích |
| Confirm theo task ID | Snapshot theo version và destination | Không sync dữ liệu đã bị sửa |
| Một status gồm review và sync | Hai vòng đời độc lập | Retry vẫn giữ thông tin đã duyệt |
| Create rồi set due rồi lưu ID | Payload đầy đủ và lưu ID ngay | Giảm lỗi giữa các bước |
| Retry mọi task FAILED | FAILED an toàn và UNKNOWN riêng | Tránh create lại sau timeout |
| evidence text từ model | Segment references và source quote | Kiểm chứng được nguồn |
| due_date không có raw/timezone | deadline_raw, due_local và due_at | Giữ mơ hồ và thời điểm chính xác |
| needs_confirmation duy nhất | Warnings theo trường và resolution | UI biết người dùng cần sửa gì |
| Chunk chỉ trả candidate task | Event CREATE/UPDATE/CANCEL | Không mất quyết định hủy/sửa |
| API thiếu add/delete/recovery | API đầy đủ cho các thao tác UI | Luồng triển khai nhất quán |
| Retention chưa có giá trị và phạm vi | Default, purge scope và lifecycle | Có thể triển khai và kiểm thử |
Kiến trúc Extension, Spring Boot Modular Monolith, PostgreSQL, LLMProvider và human review được giữ. Extension không tự đọc nền tảng họp; Trello vẫn đồng bộ một chiều. Những thay đổi trên làm rõ hợp đồng triển khai và xử lý ngoại lệ của cùng sản phẩm.

# 16 Nguồn tham khảo kỹ thuật
Các nguồn dưới đây được kiểm tra ngày 05/10/2026. Chúng hỗ trợ khả năng API và vòng đời extension; giá trị cấu hình, retention và chính sách review trong SDS là lựa chọn thiết kế của phiên bản 3.0.
[R1] Chrome for Developers. The extension service worker lifecycle. https://developer.chrome.com/docs/extensions/develop/concepts/service-workers/lifecycle
[R2] Atlassian. Trello REST API Cards. Endpoint Create a new Card hỗ trợ idList, idMembers và due. https://developer.atlassian.com/cloud/trello/rest/api-group-cards/
[R3] Atlassian. OAuth 2.0 Confidential Client Usage. Authorization code, PKCE, backend client secret và token refresh. https://developer.atlassian.com/cloud/trello/guides/rest-api/oauth-2-confidential-client-usage/
[R4] Chrome for Developers. Side Panel API. https://developer.chrome.com/docs/extensions/reference/api/sidePanel
Tài liệu SDS v2 là cơ sở phạm vi và kiến trúc được cập nhật. Không đưa transcript hoặc thông tin đăng nhập thật vào các ví dụ JSON, testcase và tài liệu source code.
