# Context tiếp nối dự án AI Meeting to Task

Cập nhật ngày 10/10/2026, múi giờ Asia/Ho_Chi_Minh.

## Yêu cầu của người dùng

- Đọc SDS v2, đánh giá luồng extension và đề xuất sửa những điểm chưa phù hợp.
- Tạo và gửi lại file SDS v3.
- Mỗi chặng làm việc phải có đánh giá tiến trình và context tiếp nối: việc đã làm, kết quả kiểm tra, vấn đề còn lại và bước tiếp theo.
- Tiếp tục công việc trong project Code. Người dùng đã chọn tạo một chat mới trong Code kèm context khi không thể chuyển nguyên lịch sử chat bằng công cụ.

## Tài liệu và vị trí hiện tại

- Nguồn: `/Users/phucthuan/phucthuan/Tài Liệu Học Năm 5/Công nghệ UD Doanh Nghiệp/AI_Meeting_to_Task_SDS_v2.docx`.
- SHA256 nguồn: `473b20facc165b4cb19c1b0c0ece924ba73c19d450fdd299a8e14ab8e55d7bf3`.
- Deliverable: `/Users/phucthuan/Documents/Codex/2026-10-05/o/outputs/AI_Meeting_to_Task_SDS_v3.docx`.
- Context này: `/Users/phucthuan/Documents/Codex/2026-10-05/o/outputs/SDS_v3_Context.md`.
- Nội dung dựng tài liệu: `/Users/phucthuan/Documents/Codex/2026-10-05/o/work/sdsv3/content.md`.
- Script dựng và kiểm tra: `work/sdsv3/build_sdsv3.py`, `work/sdsv3/check_sdsv3.py` trong workspace nói trên.
- Project Code: `/Users/phucthuan/phucthuan/Tài Liệu Học Năm 5/Công nghệ UD Doanh Nghiệp/Code`.
- Project ID: `6bd9587b-d7f8-43d0-adbd-e1250457a5f1`; thư mục này trống ở lần kiểm tra ban đầu và chưa là Git repository; hiện đã bổ sung bộ tài liệu bàn giao.

## Trạng thái sản phẩm

Đã hoàn thiện đặc tả v3; foundation 14.1 đã kiểm thử ở lượt trước. Ngày 09/10/2026 đã có DB jobs/lease/checkpoints/cancel/recovery, hai adapters OpenAI Responses/Gemini generateContent, schema meeting-events-v1, CREATE/UPDATE/CANCEL validation/reconciliation và V3 lưu đề xuất cần duyệt. Extension 0.3.0 có chọn AI, tiến độ, đọc đề xuất/evidence và khôi phục kết quả. 66 backend unit tests/package và 40 extension tests/build MV3 đạt. 26 integration cases bị chặn socket PostgreSQL khi khởi tạo, chưa xác minh V2/V3/HTTP/Chrome/API AI thật. Người dùng từng báo preparation 2/2 + PROVIDER_NOT_CONFIGURED. Provider có key dùng llm-v1; foundation job cũ không tự nâng cấp. Chưa có sửa/duyệt task/Trello, dataset baseline dev/held-out, chunking hoặc benchmark model budgets. Chưa đủ evidence runtime/quality để tuyên bố hoàn tất 14.2/14.3/14.4 hay 30 acceptance cases SDS.

Giữ nguyên kiến trúc Browser Extension Manifest V3/React Side Panel, Spring Boot Modular Monolith, PostgreSQL, External LLM API qua LLMProvider và Trello REST API. MVP dùng paste/.txt/.docx, không thu âm, không STT, không tự đọc Teams/Meet/Zoom và không đồng bộ hai chiều.

## Các lựa chọn thiết kế của v3

1. Tạo meeting và preview trước; chỉ gửi transcript LLM khi người dùng bấm Phân tích.
2. Analysis chạy qua DB job, trả HTTP 202, có polling, checkpoint, lease, cancellation và recovery. Side Panel/service worker không sở hữu tiến trình nền.
3. Chọn connection, Board/List và resolve member trước xác nhận. Một tên gần khớp chỉ là SUGGESTED; alias được user xác nhận có phạm vi connection/Board.
4. Nút Tạo N card thực hiện approve-and-sync theo task versions và destinationVersion. Backend tạo immutable snapshot và sync item trong một transaction.
5. Tách review status PENDING_REVIEW/APPROVED/REJECTED khỏi sync status NOT_SYNCED/QUEUED/SYNCING/SYNCED/FAILED/UNKNOWN.
6. QUEUED/SYNCING/UNKNOWN khóa sửa task và destination. SYNCED chỉ xem và mở Trello trong MVP.
7. Create card gửi payload đầy đủ và lưu card ID ngay. Khóa domain và Idempotency-Key chặn duplicate nội bộ.
8. Timeout hoặc worker/DB lỗi sau dispatch là UNKNOWN; đối soát bằng marker logical task ID trước khi create lại. Không bảo đảm tuyệt đối exactly-once cho mọi sự cố Trello.
9. Evidence dùng segment references, quote lấy từ nguồn. Warning theo trường; JSON hợp lệ không chứng minh ngữ nghĩa đúng.
10. Deadline giữ raw, due_local, due_at UTC và timezone. Chỉ ngày đã xác nhận dùng giờ khởi điểm 17:00 được hiển thị; mơ hồ phải user xử lý.
11. Priority thiếu căn cứ là null; MVP đưa priority vào description, không tự tạo label/custom field.
12. Chunking giữ CREATE/UPDATE/CANCEL và sequence; overlap không đủ để bảo đảm correction xa nhau. PARTIAL_FAILED không publish như COMPLETED.
13. Kết nối Trello chọn OAuth 2.0 confidential client, code + PKCE; token và refresh ở backend, cập nhật refresh atomically.
14. Các mặc định file 10 MiB, text 200000 ký tự, source retention 30 ngày, log 90 ngày và concurrency/attempt budgets là lựa chọn khởi điểm để benchmark, không phải giới hạn đã đo.
15. Evidence không tự gửi Trello; user có lựa chọn riêng và preview. Evaluation dùng consent/opt-in riêng.
16. Purge phải xử lý raw, normalized, segments, chunk output và evidence copies; source FK không được cascade xóa task đã duyệt. Xóa meeting không tự xóa card Trello.

## Đánh giá theo chặng

### Chặng đọc và đánh giá v2

Đọc toàn bộ nội dung và bảng/sơ đồ; render nguồn để xác minh vị trí. Luồng tổng thể phù hợp, nhưng review/member order, request analysis dài, xác nhận phiên bản và retry chưa đủ rõ để triển khai ổn định. Đã đề xuất sửa tương ứng.

### Chặng đặc tả v3

Đã viết 16 chương, 22 functional requirements, vòng đời analysis/task/sync, hợp đồng API, schema dữ liệu, privacy và 30 acceptance cases. Giữ phạm vi sản phẩm; mở rộng phần xử lý lỗi và concurrency. Rà soát thêm destination locking, source purge và khả năng scrub snapshot sau retention để tránh mâu thuẫn giữa các chương.

### Chặng dựng và kiểm tra tài liệu

Dựng từ bản nguồn, giữ A4/margins/header/footer và các package parts không liên quan; cập nhật version, nội dung và styles. Bản đầu có trang quá thưa nên đã điều chỉnh dòng chảy và độ rộng bảng. Phát hiện duplicate heading style IDs từ nguồn/cách mapping tên style; đã sửa chuẩn hóa tên và kiểm tra lại.

Đã render bản cuối 35 trang và duyệt từng trang; kết quả chi tiết ở phần QA cuối dưới đây. Đây là kiểm tra tài liệu, không thay thế kiểm thử extension, backend, OAuth và Trello.

## Công việc tiếp theo trong Code

Deliverables và bản nguồn nội dung đã được sao chép vào `docs/ai-meeting-to-task/` trong Code. Chat mới cần đọc SDS/context và xác nhận đã nhận đủ bối cảnh. Không tự tuyên bố đã chuyển nguyên lịch sử chat; chat mới nhận bản tổng hợp và các tài liệu.

Người dùng yêu cầu tiếp tục code/context và end user tự chọn AI. Adapters/schema/persistence/readonly candidates đã triển khai; đọc LLM_Setup.md để cấu hình key ở backend, restart Flyway V3, build/Reload 0.3.0 và tạo lượt llm-v1 mới. Readiness chỉ dựa key có giá trị; không xác minh key/quota/model. Job pin provider/model/prompt/schema/budgets; chưa fallback/auto HTTP retry. Transport heartbeat lease trong thời gian chờ, mất lease/cancel không publish, commit kết quả và COMPLETED cùng transaction. GET tasks owner-scoped chỉ hiện kết quả job/revision hiện hành. Chạy local verify/HTTP smoke rồi API AI/Chrome checklist; tiếp theo baseline 30–50 mẫu dev/held-out và sửa/duyệt task trước Trello. Không hỏi lại chọn một provider cố định; không thu thập key vào chat/context/commit. Mọi đề xuất là PENDING_REVIEW; deadline parse giới hạn giờ trước ngày DD/MM/YYYY+timezone, mơ hồ giữ raw.

Mỗi lượt tiếp theo cập nhật một progress/context record: mục tiêu, thay đổi thật, checks đã chạy, kết quả, phần chưa kiểm chứng và bước kế tiếp. Thông báo tiến độ ngắn gọn bằng tiếng Việt; tránh hỏi lại quyền cho việc đã được người dùng cho phép.

Bước 22 hiện tại: người dùng chạy request Python chỉ contents với gemini-3.8-flash và nhận HTTP 200/Bằng 2. Đã đổi Gemini adapter sang text generation: instructions + schema canonical + dữ liệu trong user prompt, chỉ giữ generationConfig.maxOutputTokens để giới hạn output. Không gửi responseFormat/systemInstruction/thinkingConfig; validator nghiêm ngặt/evidence/lease/persistence giữ nguyên. Gemini prompt version mới meeting-events-v1-gemini-text-v1, OpenAI không đổi; cần restart backend, mở panel/chọn Gemini/bấm Phân tích tạo job mới. Không dùng retry snapshot cũ. 66 backend unit/package và 16 Python tests đạt; lời gọi thật qua adapter mới, PostgreSQL và panel chưa được xác minh. Script probe mặc định theo text shape mới; --isolate là diagnostics structured cũ. Chưa biết chính xác tham số structured nào gây HTTP 400.

## Giới hạn chuyển chat

Công cụ Codex hiện có không hỗ trợ đổi project của chat đang mở; Computer Use cũng từ chối thao tác với ứng dụng Codex. Người dùng đã chấp thuận tạo chat mới trong project Code. Không chỉnh sửa trực tiếp cơ sở dữ liệu/state nội bộ của Codex để lách giới hạn này.

## Nguồn kỹ thuật đã kiểm tra

- Chrome service worker lifecycle: https://developer.chrome.com/docs/extensions/develop/concepts/service-workers/lifecycle
- Trello Cards API: https://developer.atlassian.com/cloud/trello/rest/api-group-cards/
- Trello OAuth 2.0 confidential client: https://developer.atlassian.com/cloud/trello/guides/rest-api/oauth-2-confidential-client-usage/
- Chrome Side Panel API: https://developer.chrome.com/docs/extensions/reference/api/sidePanel

## QA cuối cùng và context bàn giao

- Bản cuối: 35 trang, 16 chương, 22 functional requirements, 28 bảng, 30 acceptance cases.
- Đã xem đủ 35 trang render cuối, kiểm tra khả năng đọc, chữ tiếng Việt, bảng, sơ đồ, header/footer và ngắt trang; không thấy cắt chữ hoặc chồng nội dung.
- 8 ví dụ JSON parse hợp lệ. Mục lục 16 chương khớp số trang render cuối.
- Không còn duplicate style IDs, tracked changes hoặc comment anchors. Các phần package không thuộc thay đổi được giữ nguyên; file v2 không bị sửa.
- SHA256 DOCX v3: `18e4a5040b97f27d687e809e8b726765a31ba8f0098af8c00f06a522ca603a05`.
- Đánh giá kết quả: hoàn tất bản thiết kế và kiểm tra tài liệu. Các điểm chính từ v2 đã có phương án cụ thể ở luồng, API, dữ liệu và acceptance matrix.
- Chưa kiểm chứng: chất lượng trích xuất trên dataset, latency/chi phí, chạy extension/backend, OAuth thực, Trello create/recovery. Các giới hạn khởi điểm phải được benchmark.
- Bộ bàn giao trong Code: `docs/ai-meeting-to-task/AI_Meeting_to_Task_SDS_v3.docx`, `SDS_v3_Context.md`, `SDS_v3_Content.md`. File Content là nguồn nội dung đặc tả, không phải mã nguồn extension.
- Chat mới được tạo theo yêu cầu rõ ràng của người dùng để tiếp nối context trong Code; không khẳng định nhập nguyên mọi tin nhắn/tool output của chat cũ.
- Bước tiếp theo: xác nhận nhận đủ tài liệu trong chat mới; khi người dùng yêu cầu code, triển khai theo roadmap chương 14 và ghi đánh giá/context sau mỗi chặng.

## Chặng tiếp nhận trong chat Code ngày 05/10/2026

- Mục tiêu: nhận bản tổng hợp bàn giao từ chat “Đánh giá luồng hoạt động extension”, đọc ba tài liệu và xác định bước tiếp theo; chưa triển khai mã nguồn.
- Chat tiếp nối: “Tiếp nối AI Meeting-to-Task SDS v3”, thread ID `01a10bd0-6bc9-7192-b271-383499904e3e`, host local. Chat nhận bản tổng hợp và tài liệu, không nhận toàn bộ lịch sử chat cũ.
- Đã làm: đọc Context và Content; trích xuất toàn bộ nội dung body/bảng của DOCX, đối chiếu tuần tự với nguồn Markdown và đọc roadmap chương 14. Cập nhật context tại Code để dùng cho các lượt tiếp theo.
- Checks thực sự đã chạy: liệt kê file trong Code; `git status` xác nhận thư mục chưa là Git repository; so sánh byte/SHA256 với bản bàn giao cũ; kiểm tra ZIP/XML của DOCX, style IDs và dấu vết tracked changes/comment anchors; đếm chương/FR/AT/bảng; parse các JSON examples; đối chiếu nội dung nguồn với DOCX.
- Kết quả: Code có đủ ba file tài liệu và chưa có source extension/backend. DOCX và Content giống byte bản gốc; SHA256 DOCX khớp `18e4a5040b97f27d687e809e8b726765a31ba8f0098af8c00f06a522ca603a05`. Context ở bản gốc có thêm dòng thông báo chat đã tạo sau lúc sao chép; phần trước dòng này khớp bản tại Code. Thông tin chat đã được ghi nhận ở chặng này.
- Kết quả cấu trúc/nội dung: ZIP không có entry hỏng; XML đọc được; 16 chương, 22 FR, 30 acceptance cases, 28 bảng; 8 JSON examples parse hợp lệ; cả 618 dòng nội dung nguồn sau bỏ markup đều xuất hiện trong DOCX theo đúng thứ tự. Không có duplicate style IDs, tracked changes hoặc comment anchors trong document body.
- Phần chưa kiểm chứng trong lượt này: không render lại hoặc kiểm tra lại bố cục 35 trang. Kết quả visual QA 35 trang là kết quả bàn giao từ chặng trước. Không chạy acceptance cases trên hệ thống, benchmark AI, extension/backend, OAuth hoặc Trello thật; không kiểm tra lại các nguồn web kỹ thuật.
- Bước kế tiếp khi người dùng yêu cầu triển khai: chương 14.1 Backend foundation — khởi tạo repo/build, module boundaries, PostgreSQL migrations, auth/session, owner checks và error contract; xây meeting/input/revision/segment với preview cho paste/.txt/.docx. Exit criteria: kiểm thử input và quyền tài nguyên trước provider thật. Sau đó tiếp tục 14.2 job/pipeline, 14.3 LLM baseline, 14.4 Extension/review và 14.5 Trello vertical slice; chunking, reliability/privacy và user evaluation ở 14.6–14.8.

## Chặng code đầu tiên ngày 05/10/2026

- Yêu cầu mới: bắt đầu code; sau mỗi bước có context đánh giá tiến độ. Người dùng đã mở Docker Desktop khi integration tests cần PostgreSQL thật.
- Việc đã làm: khởi tạo Git local nhánh `main` (chưa commit/push), backend Maven Wrapper 3.9.16/Spring Boot 3.5.16/Java target 17; Flyway V1 cho user/session/meeting/revision/segment; auth JWT + BCrypt + session revoke; owner checks; trace/error contract; CORS exact allowlist; POST paste/TXT/DOCX → preview, GET history/metadata/segment/source, PATCH thay input theo version và revision mới.
- Giới hạn/parser: JSON body 1 MiB với kiểm tra declared length và stream; file 10 MiB, extracted text 200000 UTF-16 units, ZIP 20 MiB/entry và 50 MiB tổng/1000 entries/ratio. TXT UTF-8 strict/BOM; POI giữ paragraph/bảng/nested table body order, bỏ header/footer/comment có warning. Lưu source locator và raw range ở cấp dòng/segment; không suy speaker identity, nhãn nguồn vẫn còn trong text. Range sau NFC chưa có map từng ký tự.
- Checks đã chạy và đạt: Maven Wrapper `verify` trên PostgreSQL 16.13 Docker riêng; 23 tests, 0 failures/errors/skips (14 unit + 9 integration). Bao gồm migration/schema validation, ký/validate JWT thật, audience/owner/expiry, BCrypt, logout, owner isolation, paste/TXT/DOCX, pagination/source map, input conflict, hai request sửa đồng thời, CORS và body/parser limits. Build executable JAR thành công.
- Kiểm tra bổ sung: JAR chạy qua HTTP thật với PostgreSQL 16.13, health/register/login/paste preview/read source/logout/revoked token đạt. Script tái lập nằm tại `backend/scripts/http_smoke.py`, dùng DB test riêng theo biến `SMOKE_DATABASE_*`, không in token. `docker compose config --quiet` đạt; `git diff --check` đã sạch sau cấu hình line endings cho Wrapper Windows.
- Lỗi đã sửa khi kiểm tra: DOCX không có comments khiến POI trả null; CORS source chưa được chọn đúng trong Spring Security; bổ sung qualifier chọn đúng bean. PostgreSQL embedded bị sandbox chặn shared memory, đã chuyển sang Docker với DB riêng thay vì dùng H2; PostgreSQL 14 có sẵn giúp kiểm tra sớm, kết quả cuối đạt trên 16.13. Các lỗi phát hiện trong lượt test đầu không bị che thành pass.
- Kết quả/đánh giá: đạt exit criteria chặng 14.1 về nhập/preview và quyền tài nguyên trước provider thật. Có backend chạy được, không chỉ scaffold. Không có lời gọi LLM/Trello và không tuyên bố hoàn tất MVP.
- Chưa kiểm chứng/triển khai: Side Panel, job/lease/recovery/cancel, LLM quality/cost/latency, task/evidence range/review, OAuth/Trello, UNKNOWN/reconciliation, purge/session cleanup và deployment/HTTPS. Có sourceExpiresAt nhưng chưa có purge scheduler; chưa được dùng dữ liệu thật rồi hứa xóa tự động. Rate limiter hiện trong một process; BCrypt/input limits chưa benchmark. Dockerfile đã viết, chưa build image backend hoặc kiểm chứng deployment.
- Tài liệu chạy: `README.md`; từng bước và checks tại `docs/ai-meeting-to-task/Implementation_Progress.md`. Secrets lấy từ môi trường, `.env` bị gitignore, `.env.example` không có secret thật. Runtime dependencies/cache và log chẩn đoán nội bộ ở thư mục tạm; test report/JAR ở `backend/target/` bị gitignore.
- Bước tiếp theo: 14.2 DB queue/analysis job, inputVersion cố định, claim/lease/recovery, polling/cancel và checkpoint. Chưa được đưa job chỉ trong RAM hoặc giả kết quả AI như COMPLETED. Sau đó 14.3 một LLM adapter/schema và dataset baseline, 14.4 review/Extension, 14.5 Trello vertical slice; 14.6–14.8 theo SDS.

## Lượt chạy lại ngày 05/10/2026

- Người dùng yêu cầu “chạy thử cho tôi đi”; giữ phạm vi backend hiện tại, không triển khai 14.2 trong lượt này.
- Môi trường sandbox đã thay đổi: không còn quyền kết nối Docker socket. `docker info/ps/images` và script run local bị từ chối `operation not permitted`; chưa khởi động DB/backend hoặc chạy lại integration/HTTP. Không có công cụ cấp lại quyền socket trong lượt này.
- Unit test đầu gặp lỗi Mockito tự attach vào JVM. Đã thêm Surefire `-javaagent` trong `backend/pom.xml`, tham chiếu `${settings.localRepository}` và `${mockito.version}` do Spring Boot quản lý. Chạy lại offline: 14 unit tests, 0 failure/error/skip; package JAR thành công. Không đổi logic nghiệp vụ. Kết quả 23 tests + HTTP ở chặng trước vẫn là kết quả lịch sử, không phải đã chạy lại trong lượt này.
- Thêm `backend/scripts/run_local.py`, README hướng dẫn. Script chạy PostgreSQL Docker riêng → verify → HTTP smoke → giữ backend trên localhost, in URL health khi sẵn sàng, Ctrl+C dừng phiên demo/DB tạm. Secrets ngẫu nhiên trong bộ nhớ; không lấy DB/volume đang có. Syntax hai script và diff whitespace đã kiểm tra; gọi script thực tế chỉ đi tới nhánh lỗi Docker, chưa kiểm chứng toàn workflow script.
- Lệnh tiếp tục tại terminal local trong project: `python3 backend/scripts/run_local.py`, với Docker Desktop đang mở và quyền socket của user. Cần xác minh 9 integration tests/HTTP với cấu hình Surefire mới trước khi ghi kết quả chạy lại đầy đủ.
- Đánh giá và bước tiếp: unit/build đạt; chạy ứng dụng đang bị giới hạn quyền môi trường. Hoàn tất chạy local rồi tiếp tục chặng 14.2. Không nói backend hiện đang lắng nghe cổng 8080 khi chưa thấy health UP.

## Tài liệu test API ngày 05/10/2026

- Người dùng yêu cầu một docs để test APIs. Đã tạo `docs/ai-meeting-to-task/API_Test_Guide.md` (Markdown), thêm link trong README; không đổi source/backend scope.
- Guide có 10 endpoint hiện có, hướng dẫn Postman/cURL, request/response mẫu, biến token/meetingId/version, upload TXT/DOCX, hai loại pagination cursor, source map, PATCH full replacement/stale, owner A/B, logout và bảng validation/limits/CORS/report. Ghi rõ chưa có job/LLM/task/Trello/delete/purge API.
- Đã đối chiếu hợp đồng trực tiếp với controller/service/parser/security/error/config; tham khảo docs Postman chính thức cho scripts/variables. Checks tĩnh đạt: parse 10 JSON blocks, shell syntax 21 blocks, JavaScript syntax 6 blocks, Python fixture syntax, local links, 10 endpoint inventory, UTF-16 character/source range examples và diff whitespace.
- Chưa gửi HTTP requests/execute Postman scripts trong lượt tạo guide; mẫu report vẫn là Chưa chạy. Kết quả runtime lịch sử giữ như các chặng trước, không ghi thành case manual đã pass.
- Bước tiếp: chạy `python3 backend/scripts/run_local.py` trên terminal local có quyền Docker, dùng guide mục 4–6 để kiểm tra và ghi Actual/Pass/Fail/traceId, rồi tiếp tục 14.2 theo SDS.

## Kết nối extension ngày 09/10/2026

- Yêu cầu mới: kiểm tra API localhost rồi làm tiếp extension theo SDS; ưu tiên phần kết nối của 14.4 trước jobs/LLM. Không thay đổi đặc tả chuẩn hay giả analysis/task APIs.
- Backend: chạy lại offline 14 unit tests đạt; curl localhost 8080/18080 đều không có server. Docker Desktop mở nhưng sandbox chặn socket operation not permitted. 9 integration tests và HTTP smoke chưa chạy lại; backend hiện chưa được xác nhận health UP.
- Thêm extension/ React JavaScript + Vite, MV3 global Side Panel; worker cấu hình icon mở panel. Chỉ permissions storage/sidePanel và backend host được build; không content scripts/tabs/remote code. API_ORIGIN build-time mặc định http://127.0.0.1:8080; production phải HTTPS. CSP giới hạn connect-src đúng origin gồm port; Chrome host permission chỉ giới hạn hostname, không port.
- Session: accessToken/expiresAt/sessionId/user lưu chrome.storage.session với TRUSTED_CONTEXTS; local chỉ meetingId key theo backend origin và user ID, không sync/raw transcript/password/token durable. Reopen dùng me + GET meeting/segments từ backend. Logout revoke rồi clear, 401/expiry yêu cầu login lại và giữ pointer account. 404 clear pointer; version/revision khác không ghép nguồn. Input chưa lưu chỉ trong RAM, có thể mất khi đóng panel/hết phiên; có cảnh báo khi điều hướng bỏ sửa.
- UI/API đã nối: đăng ký/login/logout; paste/một TXT/DOCX; metadata ngày/múi giờ không bắt buộc, timezone gợi ý cần người dùng kiểm tra; preview/source với cursor 0 hợp lệ; history; saved meeting; PATCH full input/metadata kèm expectedVersion. Form thay input bắt đầu text trống để yêu cầu đầy đủ nội dung thay vì dùng preview bị cắt. Text/errors render bằng React escaping; 409 không overwrite; trace/Retry-After và lỗi timeout hiển thị, không tự retry ghi.
- run_local.py nay giữ exact EXTENSION_ORIGIN_ALLOWLIST truyền từ môi trường. Hướng dẫn: docs/ai-meeting-to-task/Extension_Setup.md, README. Build npm ci → npm run check; Load unpacked extension/dist → lấy ID → chạy backend với allowlist chrome-extension://ID. DB script demo tạm sẽ mất khi Ctrl+C; dùng Compose volume nếu cần persistence qua backend restart.
- Kết quả: npm ci offline từ cache, 23 client tests và build/artifact checks đạt. Tests gồm fetch/storage giả lập và React server-render text escaping, không thay PostgreSQL/Chrome integration. Test đầu compile JSX lỗi percent-encoded path tiếng Việt, đã sửa fileURLToPath và chạy lại đạt. Package lock khóa React 19.2.3/Vite 7.2.7/esbuild 0.25.12, dist/node_modules/.local được gitignore.
- Chưa kiểm chứng: native Chrome load/Side Panel worker/CSP, login/CORS/HTTP end-to-end, phiên expiry/reopen trên Chrome, hai cửa sổ và giao diện sidebar thật. Preview tĩnh được sinh bằng React với dữ liệu giả nhưng sandbox chặn bind server preview; Browser Use policy từ chối file URL nên không xem layout và không lách sang browser khác. Chưa có job progress, LLM, task/evidence review/autosave task, Trello hoặc purge scheduler. Không ghi 14.4 hoàn tất.
- Bước tiếp: chạy checklist extension với backend thật; tiếp tục 14.2–14.3 để nối analysis progress/review/task sau đó. Progress chi tiết nằm trong Implementation_Progress.md Bước 6–7; không hỏi lại quyền cho các thay đổi source đã được user yêu cầu.

## Hỗ trợ cấu hình local ngày 09/10/2026

- Người dùng gửi log Maven startup thất bại: source .env báo command not found tại dòng password, PostgreSQL báo SCRAM nhưng không có password. Sau đó source/copy file trong backend báo thiếu file vì .env/.env.example ở Code.
- Kiểm tra file bằng metadata che giá trị; lần đầu xác nhận DATABASE_PASSWORD có khoảng trắng sau dấu bằng. Lần kiểm tra tiếp theo assignment đã đúng nên không ghi thay đổi vào .env. Không rotate credentials/xóa DB volume.
- Kiểm tra tĩnh cả 5 biến nonempty/literal assignment, JWT base64 >=32 bytes và origin chrome-extension://ID hợp lệ: đạt, không in secret. Thêm hướng dẫn source từ Code và check có password mà không echo nội dung trong Extension_Setup.md.
- Chưa xác nhận backend khởi động sau sửa; cần source lại trong terminal local (môi trường shell đang mở chưa tự cập nhật theo file), chạy Maven và thấy health UP trước khi ghi thành công kết nối extension. Chi tiết tiến độ Bước 8.

## Tiếp tục SDS 14.2 ngày 09/10/2026

- Đã triển khai Flyway V2, module job, policy/API enqueue/poll/cancel/retry; snapshot revision/inputVersion/metadata/budgets không đổi khi retry. Khóa owner → meeting → job và partial unique index chặn job active trùng; active job chặn PATCH. Job pointer không tăng inputVersion, revision mới xóa pointer nhưng giữ lịch sử job.
- Worker dùng PostgreSQL queue/lease/attempt generation, clock DB, FOR UPDATE SKIP LOCKED; checkpoint counters theo pinned source sequence; fencing worker cũ, cancellation trước ghi, recovery giới hạn attempts. Preparation guard dùng ước lượng UTF-8/JSON, chưa phải tokenizer thật hoặc chunking. Không giữ transaction khi chuẩn bị; khi có provider thật cần bổ sung heartbeat trong I/O và recheck full prompt/schema/output budget.
- Pipeline hiện luôn báo PROVIDER_NOT_CONFIGURED sau preparation. Policy API trả unconfigured/foundation-v1/providerReady=false. Không có key/model thật, extraction/tasks/evidence hoặc COMPLETED giả. Đây là source nền 14.2, chưa đạt exit criteria runtime; 14.3 chưa triển khai.
- Extension 0.2.0 có Tạo job chuẩn bị, khôi phục ID từ meeting, polling GET/backoff bounded, hủy/retry có điều kiện và khóa input; phản hồi bị fence theo meeting/revision/version, không enqueue lại khi reopen, không tự retry ghi. Service worker không sở hữu job.
- Verification mới: 34 unit backend và 35 client tests đạt; executable JAR và bundle MV3 build được. Full verify đã thử nhưng embedded PostgreSQL bị chặn ServerSocket với Operation not permitted, dẫn đến lỗi khởi tạo 21 integration tests. Không coi các cases này là pass. Docker socket cũng bị chặn và localhost health không truy cập được từ agent.
- Đã viết 12 job integration cases cộng 9 foundation; extended HTTP smoke kiểm tra queue/cancel/input lock và restart JVM giữ DB, mới kiểm tra syntax. Chạy trên terminal local bằng run_local.py; script tạo DB test riêng. Tài liệu thao tác với DB Compose của người dùng ở Analysis_Job_Test_Guide.md.
- Người dùng báo quan sát/thay segments và thử hai cửa sổ; chưa có đầy đủ Actual/Pass/Fail cho toàn checklist. Bản mới chưa được xác minh Chrome/CORS/PostgreSQL end-to-end. Không khôi phục .env.example người dùng đã xóa, không sửa/in secret .env.
- Context chi tiết ở Implementation_Progress.md Bước 9–10; hướng dẫn test ở Analysis_Job_Test_Guide.md. Bước kế tiếp: xác minh runtime V2, sau đó 14.3 LLM adapter/schema/baseline và task review theo SDS.

## Build local và audit dependency ngày 09/10/2026

- Người dùng gửi log local: npm ci và npm run check thành công, 35 tests/build/MV3 đạt ở dependency cũ; npm audit báo 6 high vulnerabilities. Báo cáo bổ sung có PostCSS/Rollup/source-map-js/Vite và đề xuất Vite 7.3.7 thay exact pin 7.2.7.
- Đã xác minh release/advisories; hướng cập nhật trong Extension_Setup.md: npm install --save-dev --save-exact vite@7.3.7, npm audit fix, npm run check, npm audit. Agent bị chặn DNS npm registry, cache thiếu bản vá; chưa thay package/lock hoặc xác nhận audit sạch. Không ghi dependency đã được sửa trước kết quả local mới. Chi tiết Bước 11 trong Implementation_Progress.md.

## Kết quả job thực tế do người dùng báo ngày 09/10/2026

- Side Panel hiển thị input version 1, worker nhận lần 1, chuẩn bị 2/2 segments, phân tích 0/1 và PROVIDER_NOT_CONFIGURED. Đây là kết quả mong đợi của policy foundation: case enqueue/preparation/read status qua panel đã được người dùng quan sát trên local.
- Chưa chứng minh cancel/recovery/retry/concurrency hay toàn integration suite; chưa có kết quả audit mới. Chặng 14.3 chưa có adapter/provider được chọn hoặc key được cấu hình. Không nhầm lỗi chưa cấu hình provider với lỗi kết nối extension. Chi tiết Bước 12 trong Implementation_Progress.md.

## Hướng dẫn chạy chi tiết và dependency mới

- Analysis_Job_Test_Guide.md đã có đường dẫn Code đầy đủ, hai terminal, Docker/Compose/env/backend health và build/Reload extension, mẫu hai segments và test worker tắt/cancel/restart. API key đơn lẻ chưa bật AI do adapter chưa triển khai.
- Kiểm tra filesystem cho thấy dependency đã cập nhật tại local: Vite 7.3.7, PostCSS 8.5.29, Rollup 4.64.3, source-map-js 1.2.2, picomatch 4.0.7. Agent chạy lại 35 tests extension và build/MV3 đạt trên bộ mới; chưa có audit sạch để xác nhận hết vulnerabilities. Context lịch sử ở Bước 11 vẫn phản ánh thời điểm trước upgrade; trạng thái mới ở Bước 13.

## OpenAI/Gemini tự chọn trong extension 0.2.1

- Theo yêu cầu, đã thêm Chọn AI với đúng hai choices OpenAI/Gemini; policy API trả tên/readiness/description, backend lưu providerId đã chọn vào snapshot job. Không có adapter của cả hai, readiness=false và preparation vẫn báo PROVIDER_NOT_CONFIGURED. Không fallback hoặc lưu API key ở extension.
- Selector yêu cầu chọn trước enqueue, khóa khi active/đang gửi/chưa rõ response. Provider của job hiển thị riêng; đổi option cho job mới không sửa job cũ; retry/idempotency giữ snapshot. Unconfigured chỉ hỗ trợ client cũ, không hiển thị choice thứ ba. Bản 0.2.1 và hướng dẫn test đã build/cập nhật.
- Checks mới: 36 unit backend + JAR và 37 client tests/build/MV3 đạt. Đã thử 13 job integration tests nhưng PostgreSQL nhúng bị chặn socket trước assertions; tổng 22 integration cases chưa xác minh toàn bộ. DB snapshot của hai providers và Chrome interaction cần test local. Chi tiết Bước 14 trong Implementation_Progress.md.
- Bước kế tiếp là test selector/reopen/job cũ, rồi code adapters/schema/validation/persistence với credentials backend. Người dùng đã yêu cầu hai options cho end user, không hỏi lại họ chọn một provider cố định.

Tiếp theo Bước 17: người dùng cung cấp log thật 21:58:13, gemini-3.8-flash/HTTP 400/INVALID_ARGUMENT. Đây xác nhận JVM/job đã dùng đúng model, chưa có vendor reason/field. Đã chuyển schema gửi riêng Gemini: priority enum nullable dùng anyOf(string enum/null), bỏ maxLength không liệt kê trong subset; schema canonical/validator OpenAI/backend giữ nguyên. Thêm reason/fieldHints cố định vào safe log và probe_gemini_request.py gọi một request dữ liệu giả để chẩn đoán. 63 unit/package và 6 offline probe cases đạt. Chưa có kết quả live sau sửa; cần output probe hoặc safe log reason/fieldHints, không suy từ 400 rằng key/quota chắc chắn hợp lệ.

Bước 19: probe của người dùng trả NETWORK_OR_RESPONSE_ERROR, chưa rõ exception. Kiểm tra local Python 3.11.9 cho thấy default CA paths không tồn tại/0 loaded CA, certifi sẵn có. Đã thêm gemini_http.py dùng default TLS verification, fallback certifi khi thiếu default CA và không có trust store chỉ định; offline load được 143 CA, hostname/chain verification giữ bật. Hai scripts phân biệt TLS/DNS/timeout/response errors; 8 tests Python đạt, chưa live network sau sửa. User chạy check_gemini_model.py GET trước rồi probe nếu metadata HTTP 200. Lỗi HTTP 400 Java trước đó vẫn chưa chốt root cause; không coi sửa CA Python là đã giải quyết lỗi Java.

Bước 20: người dùng xác minh metadata GET HTTP 200/generateContent=true, TLS certifi 143 CA; POST probe vẫn 400 INVALID_ARGUMENT, reason=UNSPECIFIED/fieldHints=[]. TLS script đã hoạt động ở lần thử này. Thêm probe --details để in error.message/field descriptions có che key/URL encoded/Bearer, giới hạn dòng; chỉ dùng request synthetic. 11 Python tests đạt. Chưa đổi tiếp Java/model/schema vì thiếu thông điệp lỗi cụ thể; cần vendorMessage của probe mới để xác định nguyên nhân, không suy GET thành công thành POST/schema đã hợp lệ.

Bước 21: --details live chỉ cho vendorMessage=Request contains an invalid argument. Chưa có nguyên nhân cụ thể. Đã thêm --isolate --details, tối đa 6 synthetic requests: baseline text/max512 → schema nhỏ với ENUM/MIME literal/legacy → full schema → full synthetic probe; dừng sớm lỗi baseline/network/auth/quota/service, không retry hay đổi backend/.env. Đối chiếu sự khác nhau giữa ví dụ REST application/json và enum reference APPLICATION_JSON. 15 tests Python mocks đạt; đang cần output live CASE/HTTP/ACCEPTED_FORMATS/ISOLATION, chưa sửa tiếp adapter hoặc kết luận root cause.

## Chặng 14.4 ngày 09/10/2026

Người dùng xác nhận 14.3 hoàn tất. Đã triển khai Flyway V4 tasks/task_evidence (version, review/sync status, deadline local+UTC+timezone, ai_suggestion, edited_fields, backfill kết quả cũ), API review (list/create/patch/reject/restore/evidence) và Side Panel 0.4.0 với autosave, xung đột phiên bản, task thủ công, cảnh báo server, bằng chứng, lịch sử có trạng thái. 103 backend tests (PostgreSQL 16 thật) và 55 extension tests đạt. Chờ người dùng chạy Review_Test_Guide trên Chrome thật; tiếp theo 14.5 Trello. Chi tiết: Implementation_Progress Bước 23.

## Chặng 14.5–14.8 ngày 10/10/2026

Người dùng xác nhận đã test hoàn chỉnh 14.4 và yêu cầu làm 14.5–14.8 rồi test toàn bộ.

Đã triển khai:

- **14.5 Trello (V5):**
  - Kết nối bằng OAuth 2.0 + PKCE hoặc API key + token. Token mã hóa AES-GCM bằng `TOKEN_ENCRYPTION_KEY`.
  - Chọn Board/List có version. Đối chiếu người phụ trách (SUGGESTED/AMBIGUOUS, không tự giao). Gợi ý hạn theo ngày họp.
  - Snapshot + approve-and-sync có Idempotency-Key. Worker dùng lease. UNKNOWN được đối soát bằng `AI_MTT_REF`, có retry/link-card/recreate có kiểm soát.
  - Panel 0.5.0.
- **14.6 Chunking (V6):** checkpoint từng phần, PARTIAL_FAILED/resume. Mặc định tắt bằng `CHUNKING_ENABLED=false`.
- **14.7 Privacy:** retention purge, xóa transcript/meeting, processing_logs không chứa nội dung.
- **14.8 Đánh giá và demo:** dataset seed-v1 (14 item tổng hợp), script đánh giá, mẫu usability, Trello giả, Browser E2E, Demo_Script, Full_Test_Guide, Evaluation_Guide.

Kết quả kiểm thử:

- 134 backend tests (PostgreSQL 16 thật), 61 extension tests và 8 Python tests đều đạt.
- Browser E2E đạt.

Chưa kiểm chứng: Trello/Atlassian thật, AI thật trên dataset, usability, Vite build trên macOS và migration trên DB của người dùng.

Chi tiết: Implementation_Progress Bước 24–28.


## Cập nhật báo cáo ngày 10/10/2026

Người dùng xác nhận đã tạo và kiểm tra card trên Trello thật từ extension. Chưa xác định phương thức kết nối, chưa đính kèm checklist/ảnh; không coi đây là xác nhận riêng OAuth Atlassian hoặc benchmark AI. Đã chạy lại npm run check trên máy hiện tại: 61 tests + Vite/MV3 build đạt. Báo cáo cho giảng viên ngày 11/10 tại Bao_Cao_Tien_Do_2026-10-10.md, gồm lịch sử, kiến trúc/luồng, kết quả có phân biệt nguồn, hạn chế, kế hoạch, demo và lời trình bày. Backend 134 tests/E2E vẫn dẫn nhật ký Bước 28, không tuyên bố chạy lại trong lượt tổng hợp; XML target local là reports cũ.


Ngày 10/10: thêm Directory_Tree.md chú thích từng file nguồn/cấu hình/test/tài liệu (192 file tại lúc tạo), giải thích artifact sinh tự động và bản đồ đọc code. README đã liên kết. Đây là tài liệu cấu trúc, không thay đổi runtime hoặc kết quả kiểm thử.
