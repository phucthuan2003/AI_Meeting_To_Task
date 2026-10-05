# Context tiếp nối dự án AI Meeting to Task

Cập nhật ngày 05/10/2026, múi giờ Asia/Ho_Chi_Minh.

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

Đã hoàn thiện đặc tả thiết kế v3. Ngày 05/10/2026, người dùng yêu cầu bắt đầu code; backend foundation theo chương 14.1 đã được triển khai và kiểm thử (chi tiết chặng dưới). Chưa có mã nguồn extension, analysis job/LLM, task/review hoặc Trello. Không được coi 30 acceptance test được viết trong SDS là các test hệ thống đã chạy hoặc đã pass.

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

Người dùng đã yêu cầu triển khai. Backend foundation và nhập → preview đã có; bước tiếp theo là chương 14.2 DB job/pipeline, rồi candidate → review → một card thử nghiệm theo roadmap. Chốt model, app OAuth/scopes, deployment origin và quality/latency targets khi có benchmark hoặc thông tin tài khoản cần thiết. Không thu thập secret trong file context hoặc commit vào repo.

Mỗi lượt tiếp theo cập nhật một progress/context record: mục tiêu, thay đổi thật, checks đã chạy, kết quả, phần chưa kiểm chứng và bước kế tiếp. Thông báo tiến độ ngắn gọn bằng tiếng Việt; tránh hỏi lại quyền cho việc đã được người dùng cho phép.

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
