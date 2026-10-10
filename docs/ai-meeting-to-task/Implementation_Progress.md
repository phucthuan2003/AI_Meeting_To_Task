# Tiến độ triển khai AI Meeting to Task

Cập nhật 10/10/2026, Asia/Ho_Chi_Minh. Đặc tả chuẩn: SDS v3.

## Bước 1 Khởi tạo nền tảng

- Mục tiêu: backend foundation theo chương 14.1; chưa tích hợp LLM/Trello.
- Thay đổi: Git repository local nhánh main; Spring Boot 3.5.16, Java target 17, Maven; PostgreSQL 16, Flyway V1 cho user/session/meeting/revision/segment; cấu hình secrets từ môi trường, CORS theo origin rõ ràng và giới hạn input.
- Môi trường: Java 24.0.2 có sẵn; Maven hệ thống chưa có; Docker daemon chưa chạy. Maven 3.9.16 được tải vào thư mục tạm và đã xác minh SHA512 để tạo Wrapper. Integration tests sẽ dùng PostgreSQL tạm qua Zonky, không dùng H2 giả lập.
- Checks đã chạy: kiểm kê công cụ, kiểm tra release/dependency metadata và checksum Maven, `git init -b main`.
- Kết quả: scaffold đã tạo; chưa build/test application ở bước này.
- Chưa kiểm chứng: migration, auth, parser/API, Docker Compose và môi trường deployment.
- Bước tiếp theo: auth/session + owner checks, parser có source map, meeting preview; sau đó chạy unit/integration tests.

## Quy tắc ghi tiến độ

Sau mỗi bước ghi mục tiêu, thay đổi thật, checks thực sự chạy, kết quả, phần chưa kiểm chứng và bước kế tiếp. Acceptance cases trong SDS là yêu cầu, chỉ ghi pass khi đã chạy test tương ứng. Không ghi secret hoặc transcript thật vào context.

## Bước 2 Auth và nhập transcript

- Mục tiêu: luồng API nhập → preview và cách ly owner trước provider thật.
- Thay đổi: đăng ký/login/me/logout, BCrypt cost 12, JWT HS256 tối đa 15 phút với issuer/audience và session DB kiểm tra mỗi request; rate limit login/register theo IP ở một process. Thêm trace/error contract, CORS allowlist; POST paste/multipart một file, GET meeting/history/segment và PATCH thay input theo version.
- Parser: UTF-8 strict/BOM; DOCX Apache POI đọc paragraph xen bảng và nested tables; preflight giới hạn bytes giải nén thực, entry count/ratio và từ chối macro. Normalization NFC giữ phủ định/correction; lưu raw và vị trí nguồn theo dòng/paragraph/cell, không suy speaker identity từ nhãn chưa xác minh.
- Checks đã chạy: Maven Wrapper generation thành công; Maven `-DskipTests package` thành công, compile Java target 17 và tạo executable JAR.
- Kết quả: source build được. Unit/integration tests đã viết và đang chạy, chưa ghi pass.
- Chưa kiểm chứng: PostgreSQL migration/API dưới test, concurrent update, app chạy qua HTTP thật và Docker Compose. Chưa có analysis job, LLM, Side Panel, task/review, Trello hoặc purge scheduler.
- Bước tiếp theo: chạy test trên PostgreSQL 16 tạm, sửa lỗi, kiểm tra build/HTTP và ghi kết quả cuối chặng 14.1.

## Bước 3 Kiểm thử và hoàn tất backend foundation

- Mục tiêu: đạt exit criteria 14.1 về nhập transcript và owner checks trước provider thật.
- Thay đổi sau test: sửa null comments của POI; reset static POI limits giữa unit tests; sửa Spring Security chọn đúng CORS source bằng qualifier. Thêm khóa row ngắn cho thay input đồng thời, giới hạn JSON body declared/stream, tests token audience/owner/expiry và HTTP smoke script. Có README, env example, Docker Compose và Dockerfile; không có secret thật.
- Khó khăn môi trường: PostgreSQL embedded không được tạo shared memory trong sandbox macOS. Người dùng mở Docker; image PostgreSQL 16.13 được tải và DB test riêng khởi động. PostgreSQL 14 có sẵn được dùng để kiểm tra sớm; kết quả cuối dưới đây là trên 16.13.
- Checks thực sự đã chạy: Maven Wrapper `verify`, 23 tests (14 unit + 9 integration), Flyway migration và JPA schema validation trên PostgreSQL 16.13, build JAR; JAR chạy qua HTTP với health/register/login/paste preview/source/logout/revoke. Validate Compose config; kiểm tra diff whitespace.
- Kết quả: 23 tests đạt, 0 failure/error/skip. HTTP smoke đạt. Compile target Java 17, kiểm thử bằng Java 24.0.2 có sẵn. Các lỗi ban đầu đã sửa rồi chạy lại; không ghi acceptance matrix toàn MVP là pass.
- Đánh giá: backend foundation đạt phạm vi chặng 14.1. User có thể đăng nhập, nhập paste/TXT/DOCX, xem source/preview/history và thay input có version check; owner khác bị 404, session đã revoke bị 401.
- Chưa kiểm chứng: image backend từ Dockerfile/deployment/HTTPS; benchmark BCrypt/input limits; JDK 17 runtime thực (bytecode target 17 đã build). Chưa có worker/LLM/Side Panel/task/review/Trello, purge hoặc session cleanup. Retention hiện mới lưu expiresAt. Source map ở cấp segment/dòng, chưa map từng ký tự sau NFC. Rate limit chưa dùng chung giữa replicas.
- Bước tiếp theo: 14.2 DB job/pipeline — enqueue 202, poll/cancel, claim/lease/recovery/checkpoint và guard inputVersion; rồi 14.3 LLM baseline. Giữ thiết kế LLMProvider và tiến trình DB, không giả extraction.

## Bảng chặng hiện tại

Kết quả chặng 14.1 dưới đây là của lượt triển khai trước. Lượt chạy lại sau đó chỉ xác nhận unit tests/build; Docker bị chặn quyền nên chưa xác nhận lại integration/HTTP.

| Chặng SDS | Trạng thái |
| --- | --- |
| 14.1 Backend foundation | Đạt nhập/preview/auth/owner trên PostgreSQL 16.13 |
| 14.2 Job và pipeline | Đã code DB queue/lease/checkpoint/cancel/recovery và chuẩn bị input; unit đạt, migration/integration/HTTP còn cần chạy local |
| 14.3 LLM và baseline | Người dùng xác nhận đã hoàn thiện (09/10/2026) |
| 14.4 Review và Extension | Người dùng xác nhận đã test hoàn chỉnh (10/10/2026) |
| 14.5 Trello vertical slice | Đã code V5 + OAuth/token + Board/List/member/deadline + snapshot/approve-and-sync + worker/UNKNOWN/đối soát + panel 0.5.0; đạt integration với Trello giả và Browser E2E; chưa thử Trello/Atlassian thật |
| 14.6 Transcript dài | Đã code chunking + checkpoint + dedupe overlap + PARTIAL_FAILED/resume (V6), mặc định tắt; AT11–13 đạt với provider giả; chưa đánh giá trên AI thật |
| 14.7 Reliability và privacy | AT18–30 có test tự động đạt (concurrency, key lặp, revoke, timeout, DB lỗi, worker chết, purge, injection, không fallback, log sạch) |
| 14.8 User evaluation/demo | Có dataset seed-v1 (14 item), script chạy/chấm điểm, mẫu usability crossover, Trello giả + kịch bản demo; chưa chạy AI thật hoặc người dùng thật |

## Bước 4 Chạy lại theo yêu cầu người dùng

- Mục tiêu ngày 05/10/2026: chạy thử backend hiện có, không bắt đầu chặng 14.2.
- Checks thực sự chạy: Java 24.0.2, Docker `info/ps/images`, unit tests offline từ Maven cache, package JAR, Python syntax check và gọi `backend/scripts/run_local.py`, `git diff --check`.
- Lỗi phát hiện/sửa: Mockito self-attach bị sandbox/JDK chặn. Thêm Surefire `-javaagent` dùng đúng Mockito version do Spring Boot quản lý và path repository Maven, nạp agent lúc khởi động test JVM. Không đổi logic nghiệp vụ.
- Kết quả cuối: 14 unit tests đạt (5 auth, 6 parser, 1 rate limit, 2 JSON body limits), 0 failure/error/skip; package JAR đạt; syntax hai script đạt; diff whitespace sạch.
- Blocker hiện tại: sandbox mới từ chối kết nối `/Users/phucthuan/.docker/run/docker.sock` với `operation not permitted`. Không có công cụ cấp lại quyền socket trong lượt này. Script local dừng ở Docker info trước khi tạo DB. Chưa khởi động backend, chưa chạy lại 9 integration tests hoặc HTTP smoke; kết quả 23 tests/HTTP trước đó không được ghi thành kết quả mới.
- Hỗ trợ chạy local: thêm `backend/scripts/run_local.py`. Lệnh từ thư mục project: `python3 backend/scripts/run_local.py`. Script tạo DB Docker riêng, chạy verify/HTTP smoke rồi giữ backend ở localhost, in URL health khi sẵn sàng; Ctrl+C dừng JVM và DB demo. Không dùng/xóa volume hoặc DB đang có. Secrets sinh trong bộ nhớ, không ghi vào context.
- Chưa kiểm chứng: toàn bộ script local qua terminal ngoài sandbox và integration/HTTP sau thay đổi cấu hình Surefire. Python syntax đã kiểm tra; hành vi thực tế hiện chỉ xác minh nhánh báo lỗi Docker.
- Bước kế tiếp: chạy lệnh local trên máy có quyền Docker để xác minh toàn luồng; sau đó tiếp tục 14.2 theo roadmap. README đã bổ sung lệnh chạy một bước và giới hạn của backend hiện tại.

## Bước 5 Tài liệu kiểm thử API

- Mục tiêu ngày 05/10/2026: tạo một tài liệu dùng để test các API đã có.
- Thay đổi: thêm `API_Test_Guide.md` trong thư mục này và link từ README; hướng dẫn 10 endpoint, setup local/Postman, cURL, request/response, lưu JWT/meetingId/version, paste/TXT/DOCX, pagination/source, stale/concurrent version, owner B và logout/revoke. Bổ sung bảng lỗi/giới hạn, CORS và mẫu ghi Actual/Pass/Fail.
- Checks thực sự chạy: đối chiếu controller/service/parser/security/error/config; đọc tài liệu Postman chính thức cho variables/test scripts; parse 10 JSON blocks, `bash -n` cho 21 shell blocks, Node VM compile cho 6 JavaScript blocks, kiểm tra Python fixture syntax, local links, 10 endpoint trong inventory, preview/source UTF-16 counts và `git diff --check`.
- Kết quả: checks tĩnh đạt; sửa ví dụ characterCount/range để khớp transcript mẫu. Không thay đổi source nghiệp vụ và không chạy lại tests backend cho thay đổi tài liệu.
- Chưa kiểm chứng: gửi các request trong guide tới backend/execute scripts trong Postman. Bảng report để Chưa chạy, không biến expected results thành kết quả đo. Docker socket vẫn bị chặn ở lượt trước; không kiểm tra lại quyền trong lượt này.
- Bước tiếp theo: người dùng chạy local, làm luồng mục 4 rồi các case mục 5 và ghi kết quả theo mục 6; báo lỗi kèm status/code/traceId. Sau khi xác minh API hiện tại, tiếp tục 14.2.

## Bước 6 Kiểm tra API localhost trước khi nối extension

- Mục tiêu ngày 09/10/2026: làm tiếp theo yêu cầu người dùng, kiểm tra backend rồi ưu tiên phần kết nối extension theo SDS.
- Checks thực sự chạy: curl health 127.0.0.1:8080 và :18080; Docker info; Maven Wrapper offline chạy TranscriptParserTest, LoginRateLimiterTest, AuthServiceTest, RequestBodyLimitFilterTest.
- Kết quả: 14 unit tests đạt, không failure/error/skip. Cả hai cổng localhost chưa có server lắng nghe. Docker Desktop đang mở nhưng sandbox từ chối Docker socket với operation not permitted; không khởi động DB/backend từ lượt này.
- Chưa kiểm chứng: chạy lại 9 integration tests và HTTP smoke. Kết quả 23 backend tests/HTTP lịch sử ở Bước 3 không phải kết quả mới. Không bỏ qua kiểm thử rồi ghi API localhost đã đạt.
- Bước kế tiếp: tiếp tục code phần extension độc lập trên hợp đồng API hiện có; cần chạy end-to-end trên máy có quyền Docker sau khi build.

## Bước 7 Kết nối React Side Panel với API hiện có

- Mục tiêu: phần kết nối của SDS §4.4/§14.4 theo ưu tiên mới của người dùng; không coi cả chặng review đã hoàn tất.
- Thay đổi: thêm extension/ với React 19.2.3, Vite 7.2.7, esbuild 0.25.12 và package lock; MV3 sidePanel/storage, backend host permission được sinh theo API_ORIGIN, CSP cùng origin, JavaScript bundle local. Service worker chỉ cấu hình mở panel và trusted session access; không giữ job nghiệp vụ.
- Luồng đã code: register/login/me/logout; nhập paste/một file TXT/DOCX và metadata; lưu→preview; xem normalized/source segments có pagination; owner history; mở lại meeting đã lưu từ backend; PATCH input đầy đủ bằng expectedVersion; 401/expiry yêu cầu login lại, 409 giữ form/tải lại, hiển thị trace/Retry-After; không tự retry request ghi.
- Privacy: token/user ở chrome.storage.session trusted contexts; local chỉ pointer meeting theo origin + user ID; không lưu raw transcript, file, password hoặc token vào durable browser storage. Text từ backend được React escape. Input chưa lưu chỉ trong RAM; đóng panel hoặc hết phiên có thể mất. Form thay input yêu cầu nhập lại đầy đủ, không ghi đè bằng preview 4000 ký tự hoặc các source segments chưa tải đủ.
- Backend helper: run_local.py giữ EXTENSION_ORIGIN_ALLOWLIST được truyền từ môi trường. API origin mặc định 127.0.0.1:8080; build lại khi backend origin/port khác. Thêm Extension_Setup.md và links README/API guide.
- Checks thực sự chạy: npm ci offline từ cache có sẵn; 23 Node tests đạt (20 API/storage/input/meeting contract và 3 React render checks); npm run build/manifest-artifact checks đạt. Sửa test JSX compile dùng fileURLToPath để đường dẫn tiếng Việt/khoảng trắng không bị percent-encoded. Cài dependency/build không cần tải remote runtime code. Python syntax, shell snippets/local links và source whitespace đạt.
- Đánh giá: source và bundle sẵn sàng để Load unpacked. Tests client dùng fetch/storage fixtures và server-render React, không phải tích hợp PostgreSQL/Chrome thật. Chưa có AI/job progress/task evidence review/autosave task/Trello; chưa hoàn tất 14.4.
- Chưa kiểm chứng: Load unpacked/CSP/service worker trong Chrome thật, login/CORS và luồng end-to-end với backend, hai cửa sổ, sidebar thật, expiry qua Chrome storage. Đã sinh React preview tĩnh bằng dữ liệu giả nhưng server preview bị sandbox chặn bind port; Browser Use từ chối file URL theo policy, nên chưa xem layout trực quan. Không tiếp tục lách policy bằng browser khác. Các case trong Extension_Setup.md vẫn để Chưa chạy.
- Bước kế tiếp: chạy backend với exact extension origin, load extension/dist và thực hiện checklist; sau đó 14.2 DB jobs/pipeline, 14.3 LLM baseline để tiếp tục progress/review/task/Trello.

## Bước 8 Hỗ trợ khởi động local từ log người dùng

- Mục tiêu ngày 09/10/2026: xử lý lỗi khởi động backend khi người dùng nạp .env rồi chạy Maven.
- Bằng chứng: log người dùng có command not found tại .env dòng password, sau đó PostgreSQL báo SCRAM authentication nhưng không được cung cấp password. Các lần source/copy tiếp theo chạy trong backend nên không tìm thấy .env/.env.example ở Code.
- Thay đổi: kiểm tra .env mà không in secret; lần đầu xác nhận khoảng trắng sau dấu bằng ở DATABASE_PASSWORD. Lần kiểm tra tiếp theo file đã có assignment đúng, không cần ghi thay đổi vào .env. Bổ sung troubleshooting vị trí file/source trong Extension_Setup.md. Không thay mật khẩu DB, key JWT hoặc xóa volume.
- Checks: kiểm tra cả 5 settings có giá trị, assignment literal đúng, JWT base64 giải mã tối thiểu 32 byte, extension origin đúng dạng chrome-extension://ID. Các checks này đạt, không in giá trị bí mật.
- Đánh giá: lỗi syntax/source path đã được xác định; file hiện tại đã hết lỗi spacing. Chưa chạy lại backend/DB từ sandbox; người dùng cần source lại trong terminal local rồi chạy Maven. Log gửi lên chưa chứng minh startup thành công, health vẫn cần xác minh UP.
- Bước tiếp: ở Code nạp .env, kiểm tra password có được nạp bằng thông báo có/không (không echo password), chạy backend; mở health rồi kiểm tra login/CORS qua extension.

## Bước 9 DB job và chuẩn bị pipeline theo SDS 14.2

- Mục tiêu ngày 09/10/2026: tiếp tục sau các lượt người dùng test extension; tạo tiến trình backend lưu bền, cố định input và kiểm soát worker. Người dùng báo đã xem segments và thay input ở hai cửa sổ; chưa có báo cáo đủ toàn bộ checklist để ghi end-to-end pass.
- Thay đổi: Flyway V2 thêm analysis_jobs/checkpoints và current job pointer. Enqueue 202 theo expectedInputVersion, policy do backend quản lý, Idempotency-Key theo meeting; owner checks, một job hoạt động/meeting và quota hai job/user. Input bị khóa khi QUEUED/PROCESSING/CANCEL_REQUESTED; pointer job không làm tăng inputVersion.
- Worker: claim bằng FOR UPDATE SKIP LOCKED; lease UUID và attempt generation dùng giờ DB; heartbeat/checkpoint trong transaction ngắn, chuẩn bị nguồn ngoài transaction; resume từ sequence đã lưu sau lease expiry. Worker cũ không được ghi sau khi mất lease. Hủy queued ngay, xử lý cancellation đang chạy trước khi ghi checkpoint; bounded attempts/manual retry. Job tiếp tục độc lập với session/panel.
- Preparation: đọc pinned revision theo batch 100 segments, lưu counters không sao chép transcript; ước lượng UTF-8/JSON input và metadata, chừa ngân sách output. Đây là guard chuẩn bị, chưa phải tokenizer/prompt thực hoặc chunking chặng 14.6. Adapter thật cần kiểm tra lại toàn payload khi triển khai 14.3.
- API: GET analysis-policies, POST meetings/{id}/analysis-jobs, GET jobs/{id}, POST jobs/{id}/cancel và /retry. Job trả status/stage/counters/errors; retry chỉ giữ nguyên snapshot và checkpoint nếu input hiện hành vẫn khớp. Chưa có LLM adapter: pipeline thực báo FAILED/PROVIDER_NOT_CONFIGURED, không sinh task hoặc giả COMPLETED.
- Checks thực sự chạy: 34 backend unit tests đạt (14 nền + 8 service + 9 runner + 3 cấu hình); build/compile đạt. Đã thử Maven verify đầy đủ nhưng PostgreSQL embedded bị sandbox chặn mở socket với Operation not permitted; 21 integration cases chưa thể kiểm chứng DB. Docker socket cũng bị chặn, localhost health chưa truy cập được trong agent.
- Tests đã viết, chưa chạy được DB: 9 foundation + 12 job integration trên PostgreSQL thật; bao phủ snapshot/idempotency/owner/input lock/cancel/recovery/lease fencing/quota/concurrency/retry và job sau logout. HTTP smoke đã mở rộng enqueue/cancel/input lock và restart JVM với queue giữ trong DB, mới kiểm tra Python syntax.
- Đánh giá: source nền job đã có; chưa đạt exit criteria runtime của 14.2. Kết quả PostgreSQL V1 lịch sử không chứng minh migration V2, transaction JDBC/JPA hoặc recovery mới.
- Bước tiếp: chạy verify/HTTP smoke trên terminal local có quyền Docker theo Analysis_Job_Test_Guide.md; sửa lỗi thực tế nếu có. Sau đó nối một LLM adapter/schema và baseline theo 14.3 trước review/task/Trello.

## Bước 10 Extension 0.2 và tài liệu test job

- Thay đổi: Side Panel thêm panel tạo job chuẩn bị, đọc policy, khôi phục currentAnalysisJobId từ meeting và GET polling; hiển thị stage/counters thật, hủy và retry khi backend cho phép. Khóa thay input trong khi job hoạt động; fencing theo meeting/revision/version để bỏ phản hồi cũ. Idempotency-Key chỉ giữ trong RAM cho yêu cầu đang gửi; request ghi không tự retry.
- Polling: đọc mỗi hai giây khi active, backoff có giới hạn khi mất kết nối, dừng khi terminal/đóng panel/đổi meeting; reopen chỉ đọc trạng thái, không enqueue lại. B cached chưa biết job mới từ A cần tải lại meeting; backend vẫn kiểm tra RESOURCE_BUSY khi sửa.
- Checks thực sự chạy: 35 extension tests đạt; build và MV3 artifact checks đạt, phiên bản 0.2.0. Tests dùng fixtures và React server render, chưa chứng minh interaction/CSP/CORS hoặc lease recovery trên Chrome/PostgreSQL thật.
- Kiểm tra cuối: package JAR mới nhất đạt; 11 JSON blocks, 38 shell snippets, 14 local links trong các test/setup guides và Python scripts syntax đạt; manifest/package cùng 0.2.0; git diff --check sạch.
- Tài liệu: thêm Analysis_Job_Test_Guide.md cho restart/Flyway V2, thử queue/cancel khi worker tắt, recovery sau restart và expected PROVIDER_NOT_CONFIGURED; cập nhật README/API/Extension guides và context. Không ghi secret, không phục hồi .env.example đã bị người dùng xóa.
- Đánh giá: code và bundle 0.2 sẵn để Load unpacked sau khi restart backend; việc xác minh migration V2 và luồng Chrome thật còn cần chạy local. Chưa có extraction AI, review/evidence tasks hoặc Trello.

## Bước 11 Đối chiếu build local và cảnh báo dependency

- Bằng chứng mới từ terminal người dùng: npm ci thành công; npm run check đạt 35/35 tests, Vite build/MV3 checks đạt cho extension 0.2.0. Đây là kiểm chứng tests/build local, chưa phải Chrome/backend end-to-end.
- Npm báo 6 high vulnerabilities. Người dùng gửi phần audit có PostCSS, Rollup, source-map-js và Vite; Vite hiện exact pin 7.2.7, audit đề xuất 7.3.7. Đã kiểm tra release 7.3.7 và advisories chính thức, đối chiếu version trong lockfile.
- Agent thử npm audit nhưng registry bị chặn DNS ENOTFOUND; cache không có bản vá cần thiết. Chưa thay dependency hoặc lockfile, chưa có audit sạch và chưa test bundle với dependency đã vá.
- Thay đổi: bổ sung Extension_Setup.md hướng dẫn npm install --save-dev --save-exact vite@7.3.7 → npm audit fix → npm run check → npm audit, rồi Reload panel. Không chạy audit fix --force và không giả integrity trong lockfile.
- Bước kế tiếp: chạy các lệnh trên terminal local có mạng, đối chiếu audit mới và tests/build sau upgrade; sau đó kiểm tra job V2 trên backend/Chrome. LLM 14.3 vẫn chưa triển khai.

## Bước 12 Người dùng xác minh job chuẩn bị trên Side Panel

- Bằng chứng ngày 09/10/2026: nội dung panel người dùng gửi hiển thị inputVersion 1, attemptCount 1, preparedSegments 2/2, completedChunks 0/1 và PROVIDER_NOT_CONFIGURED.
- Đánh giá: case tạo job → worker chuẩn bị nguồn → panel đọc failure do chưa có adapter đã cho kết quả đúng trên môi trường local của người dùng. 0/1 là chưa phân tích LLM, không phải thiếu segment; không có task được tạo.
- Phạm vi xác minh: kết quả UI do người dùng báo, không phải integration suite do agent chạy. Chưa có bằng chứng mới về cancel/retry/restart/lease fencing/concurrency hoặc npm audit sau cập nhật; không ghi toàn chặng 14.2 đạt.
- Bước tiếp: chọn provider cho adapter LLM ở 14.3 và cấu hình key ở backend, rồi kiểm thử extraction/schema/evidence với dữ liệu mẫu. API key không gửi qua chat và không đưa vào extension/context.

## Bước 13 Hướng dẫn chạy và kiểm tra lại sau cập nhật dependency

- Theo yêu cầu hướng dẫn chi tiết, mở rộng Analysis_Job_Test_Guide.md: đường dẫn Code đầy đủ, hai terminal, Compose → nạp .env → worker bật → health → build/Reload → transcript mẫu → kết quả expected → tắt worker để test queue/cancel/restart. Phân biệt job hiện tại với AI thật; chỉ thêm key chưa thể bật LLM vì adapter chưa có.
- Filesystem hiện có Vite 7.3.7, PostCSS 8.5.29, Rollup 4.64.3, source-map-js 1.2.2 và picomatch 4.0.7 trong lockfile. Không thay đổi dependency thêm trong lượt này. Agent chạy npm run check trên bộ dependency mới: 35 tests và build/MV3 checks đạt. Chưa có kết quả npm audit mới, không khẳng định hết cảnh báo.
- Checks tài liệu: bash -n các shell blocks trong guide đạt. Chưa chạy lại PostgreSQL/backend từ agent; kết quả preparation thực tế vẫn là bằng chứng do người dùng gửi ở Bước 12. Bước tiếp: test cancel/restart nếu cần hoàn tất runtime cases, rồi chọn provider và triển khai 14.3.

## Bước 14 Hai lựa chọn AI cho người dùng

- Yêu cầu: người dùng tự chọn giữa hai provider thay vì chốt một provider cho toàn ứng dụng. Đã thêm ô Chọn AI trong panel với OpenAI/Gemini; cần chọn chủ động trước khi tạo job mới. Backend công bố đúng hai options có displayName và providerReady, kiểm tra IDs và lưu providerId thực vào job thay vì hardcode unconfigured.
- Snapshot: job cũ giữ provider, retry giữ nguyên lựa chọn và idempotency conflict khi cùng key đổi provider. Reopen lấy lựa chọn của job từ backend; selector khóa khi job active/POST đang gửi hoặc chưa rõ response. Client cũ unconfigured vẫn được backend chấp nhận cho compatibility nhưng không có option thứ ba trong UI. Không có fallback sang provider khác hoặc API key trong browser.
- Giao diện: hiển thị AI của job riêng với lựa chọn cho job mới, bỏ stage label lặp status Chưa hoàn tất, thông báo provider chưa kết nối. Cả hai providerReady=false; chỉ chuẩn bị input rồi PROVIDER_NOT_CONFIGURED. Đây là phần lựa chọn provider, chưa triển khai adapter hoặc hoàn tất 14.3.
- Verification: 36 backend unit tests đạt và package JAR mới nhất build được; 37 extension tests/build/MV3 đạt ở 0.2.1. Có tests cả hai provider, invalid IDs, snapshot argument, idempotency conflict, selector/render và request payload không fallback/retry tự động.
- DB: đã thêm một integration case GET hai options → enqueue từng provider → snapshot persistence → cancel → cùng key đổi provider phải 409. Thử AnalysisJobIntegrationTest (13 cases) bị lỗi khởi tạo PostgreSQL nhúng do SocketException Operation not permitted, chưa chạy được assertions. Tổng suite hiện có 22 integration cases gồm 9 foundation, 13 jobs; chưa xác minh SQL insert/provider snapshot qua DB hoặc Chrome thật. HTTP smoke đã dùng gemini và kiểm tra snapshot sau restart, mới kiểm tra syntax.
- Tài liệu: cập nhật README/API/Extension/Job guides và context. Bước kế tiếp: restart backend, build/Reload extension và test lựa chọn trên local; sau đó triển khai LLM adapters/schema/validation/persistence với key ở backend. Không cần hỏi lại người dùng chọn một provider cố định.

## Bước 15 Adapters thật và kiểm tra kết quả AI

- Yêu cầu ngày 09/10/2026: tiếp tục từ hai lựa chọn chưa kết nối. Triển khai cả OpenAI Responses và Gemini generateContent qua LlmProvider, cấu hình key/model ở backend; dùng skill OpenAI Docs và tài liệu Google chính thức để đối chiếu payload Structured Outputs. Không gọi API thật, không lấy/in key trong .env.
- Snapshot: provider có key công bố llm-v1; job pin provider/model/prompt/schema/context/output reserve. Readiness chỉ kiểm tra key có giá trị. Foundation jobs/client unconfigured tiếp tục preparation và PROVIDER_NOT_CONFIGURED. Job cũ không tự nâng cấp; replay cùng Idempotency-Key trả job đã chấp nhận kể cả sau khi key bị gỡ, key khác cần cấu hình hợp lệ.
- Transport: fixed HTTPS vendor hosts, key ở header, không theo redirect, giới hạn body 1 MiB, connect/request timeout và gia hạn lease trong lúc chờ. Không auto retry/fallback. Cancel/mất lease chặn publish; kết quả và COMPLETED commit cùng transaction ngắn. Recovery/retry có thể gọi provider lại sau dispatch nên không hứa exactly-once hoặc không tính phí khi hủy.
- Extraction: schema meeting-events-v1, prompt coi source là dữ liệu untrusted, CREATE/UPDATE/CANCEL theo thứ tự. Validate trường/enum/null/duplicate key/trailing text, source UUID/sequence và evidence theo field; tên người/hạn raw phải có trong source. Quote do server lấy từ segment. Reconcile cập nhật/hủy theo task_ref; refs không xác định thành warning. Không tuyên bố schema/evidence kiểm tra được toàn ngữ nghĩa.
- Persistence: V3 analysis_results lưu events/candidates/warnings và usage nullable/latency của kết quả thành công. GET meetings/{id}/tasks owner-scoped đọc job COMPLETED hiện hành, optional analysisJobId chặn stale response. Đổi input tách revision mới khỏi kết quả cũ. Chưa có mutable task entities/version/edit/approve/sync.
- Deadline: chỉ đề xuất local/UTC cho giờ trước ngày DD/MM/YYYY với timezone xác định; thiếu/mơ hồ giữ raw/null và warning. Mọi candidate PENDING_REVIEW và needsConfirmation=true, chưa resolve member; không tự tạo Trello card. Giới hạn input serialized 49152 bytes, output 4096, timeout 60s là lựa chọn khởi điểm, chưa tokenizer/chunking hoặc benchmark.
- Checks: 57 backend unit tests đạt, 0 fail/error/skip; package JAR đạt. Bao phủ request/headers của hai adapters, lỗi/refusal/truncation, validation/correction/cancel/unknown source, transport heartbeat/cancel/timeout, budget/source continuity, current-job result guard và idempotency sau key removal. Fixtures dùng key giả, không mở socket hay gọi provider thật.
- Chưa kiểm chứng: đã thử cả 26 integration cases (9 foundation, 13 jobs, 4 LLM); PostgreSQL nhúng bị SocketException Operation not permitted khi khởi tạo nên assertions không chạy. V3 SQL/atomic publish/HTTP adapter pipeline với DB còn cần local verify. Chưa API thật, dataset dev/held-out 30–50 mẫu, baseline chất lượng/latency/cost; chưa tuyên bố hoàn tất SDS 14.3.

## Bước 16 Panel đề xuất và tài liệu chạy local

- Extension 0.3.0: provider có key dùng nút Phân tích; COMPLETED tải readonly candidates qua GET với meeting/revision/inputVersion/job fencing. Hiển thị task/người/hạn raw, thời gian đề xuất cần xác nhận, cảnh báo và evidence thật; zero-task có thông báo riêng. Kết quả sai owner/job hoặc response muộn không trở thành dữ liệu meeting hiện hành. Reopen đọc DB; Tải lại kết quả chỉ GET.
- Checks: 40 extension tests đạt, 0 fail; Vite 7.3.7 build và MV3 artifact checks đạt, manifest/package cùng 0.3.0. Tests bổ sung result endpoint/auth/query và React render/escaping/null/zero-task. Đây là fetch/storage fixtures + server render, chưa Chrome interaction/CSP/AI thật.
- Tài liệu: thêm LLM_Setup.md hướng dẫn hai key, giữ .env/database đang dùng, restart V3, build/Reload, tạo job llm-v1 mới, sample/expected task/evidence, errors/cancel/retry và giới hạn. Cập nhật README/API/Extension/Job guides/SDS context; guide preparation tạm override key trống để các expected results vẫn đúng. Không khôi phục .env.example đã bị người dùng xóa.
- Kiểm tra tĩnh: bash -n cho 53 shell snippets, parse 12 JSON blocks, local links, Python scripts AST/schema JSON, manifest/package/lock cùng 0.3.0 và git diff --check đạt. Không execute các hướng dẫn cấu hình/key trong sandbox.
- Đánh giá: code/build sẵn để kiểm thử local, chưa có sửa/duyệt task hoặc Trello. Bước tiếp theo: local verify + API AI/Chrome theo LLM Setup, baseline chất lượng với dev/held-out, rồi sửa/duyệt task theo SDS 14.4. Các result lưu JSON có evidence copies cần đưa vào purge ở chặng retention; scheduler chưa triển khai.

## Bước 17 Chẩn đoán Gemini 3.8 bị từ chối request

- Bằng chứng ngày 09/10: người dùng báo PROVIDER_REQUEST_REJECTED khi dùng Gemini, đã đổi model sang 3.8. Chỉ đọc dòng GEMINI_MODEL của .env, xác nhận gemini-3.8-flash; không in/đổi key. Đối chiếu Google model page xác nhận ID có tồn tại và hỗ trợ structured outputs. Chưa biết HTTP status/job model được JVM pin cho lỗi trước, nên chưa kết luận nguyên nhân là model hay key/schema.
- Thay đổi: Gemini generateContent dùng generationConfig.responseFormat.text.schema/mimeType=APPLICATION_JSON theo API reference thay cho responseMimeType/responseJsonSchema deprecated. Giữ model job đã pin; không downgrade .env của người dùng, không tự retry/fallback. Không thêm thinkingBudget 2.5 vào model 3.8.
- Diagnostics: request lỗi log jobId/provider/model/httpStatus/vendorStatus từ enum allowlist; không log raw vendor message/body, key hoặc transcript. Thêm check_gemini_model.py GET metadata, fixed HTTPS/no redirects, chỉ output model/HTTP status/method support. Đọc được metadata chưa chứng minh schema hoặc phân tích thành công. Message trước hướng dẫn log trace trong khi chưa lưu chi tiết vendor; log mới giải quyết phần chẩn đoán thiếu này.
- Checks: 59 backend unit tests và package đạt, gồm regression payload Gemini 3.8 với schema thật, không có fields cũ/thinkingBudget và log không lộ dữ liệu nhạy cảm. Script chạy 4 offline cases (metadata success/404/network error/missing key) với transport giả, đạt; shell snippets trong LLM Setup và diff whitespace đạt. Không chạy lại client tests vì không đổi extension. Không gọi API/metadata Google thật trong agent hoặc chạy lại PostgreSQL integration.
- Đánh giá/bước tiếp: đã sửa payload theo API hiện tại và bổ sung cách lấy bằng chứng; chưa xác nhận hết lỗi request của người dùng. Restart JVM với .env đã nạp, tạo job mới, thử Gemini; nếu còn lỗi lấy dòng sanitized log mới và output metadata để tách HTTP 400 tham số/precondition với 404 model/quyền truy cập. LLM Setup mục 6.1 và SDS context đã cập nhật, giữ các kết quả lịch sử.

## Bước 18 Gemini HTTP 400 và schema tương thích

- Bằng chứng: log thật người dùng 09/10 21:58:13, job 9899a918-17fa-4640-b1bc-ccb0280ad65e, model gemini-3.8-flash/HTTP 400/INVALID_ARGUMENT. Xác nhận job/JVM dùng đúng model; chưa có vendor reason/field violation, chưa biết nguyên nhân chính xác. Không coi 400 chứng minh key hợp lệ.
- Thay đổi: schema gửi riêng Gemini dùng anyOf(string enum/null) cho priority, bỏ maxLength không liệt kê trong subset. Schema canonical, OpenAI và validator backend giữ nguyên, vẫn cho phép priority null và kiểm tra đầy đủ maxlength/evidence. Không đổi model/key .env, không auto retry hoặc downgrade.
- Diagnostics: thêm ProviderDiagnostics cho ErrorInfo reason enum và field hints từ các tên cố định, không echo arbitrary message/path/description. Log request lỗi nay có reason/fieldHints. Gợi ý không phải khẳng định tất cả tên xuất hiện đều invalid. Thêm probe_gemini_request.py gửi một request dữ liệu giả với config/schema mới; prompt rút gọn, không đọc meeting/DB, không auto retry, user chạy trong terminal có key. Request probe có thể tiêu thụ quota/phí.
- Checks: 63 backend unit tests, 0 fail/error/skip và package JAR đạt. Test schema conversion giữ canonical, nullable priority/evidence required; diagnostics key-invalid/enum/field/malformed labels không echo dữ liệu. Probe chạy 6 offline cases success/key error/enum error/field value/network/missing key, đạt; không gọi mạng thật. Python AST/shell snippets/diff whitespace đạt; không đổi extension, không chạy lại DB integration.
- Đánh giá: đã sửa các điểm tương thích và chuẩn bị chẩn đoán có thể chạy ngay; chưa có response live sau thay đổi để xác nhận lỗi được xử lý. Cần output probe hoặc safe log mới reason/fieldHints. Nếu probe HTTP 200, restart backend và tạo job mới để kiểm tra extraction/persistence; nếu 400 dùng reason/field cụ thể cho bước sửa tiếp. LLM Setup mục 6.2 và context đã cập nhật.

## Bước 19 Sửa chẩn đoán mạng và CA của Python

- Bằng chứng: người dùng chạy probe nhưng chỉ nhận NETWORK_OR_RESPONSE_ERROR. Script cũ gộp lỗi network/SSL/timeout/JSON nên thông báo không đủ chẩn đoán. Kiểm tra local Python 3.11.9 xác nhận default cafile/capath không tồn tại và SSL context có 0 CA; certifi được cài và có bundle. Đây là lỗi cấu hình CA quan sát được, chưa có traceback thật của lần probe để chốt nguyên nhân duy nhất.
- Thay đổi: thêm gemini_http.py dùng chung cho GET metadata và POST probe; tạo context xác minh TLS, nạp certifi khi default store rỗng/không có CA directory và không có SSL_CERT_FILE/DIR chỉ định. Không đổi trust store toàn máy. In nguồn/count CA và các labels lỗi riêng cho TLS/certificate/DNS/timeout/permission/connection/HTTP read/JSON/structure, không echo raw exception.
- Checks: SSL context mới trên máy nạp 143 CA qua CERTIFI_FALLBACK, check_hostname=true, verify_mode=CERT_REQUIRED. 8 tests Python đạt (CA fallback/preserve explicit/default stores/no redirects/classified wrapped errors/GET metadata TLS/POST invalid JSON/HTTP rejection). Python AST, shell snippets guide và diff whitespace đạt. Không thay Java/extension hoặc gọi Gemini thật; không chạy lại Maven/client tests cho sửa Python này.
- Đánh giá/bước tiếp: Python probe có thể gặp lỗi CA riêng trong khi Java đã kết nối được và nhận HTTP 400; không gộp hai vấn đề. User chạy check_gemini_model.py từ terminal đã nạp .env để kiểm tra HTTPS bằng GET, rồi probe nếu metadata thành công. Cần output live sau sửa để tiếp tục xác định root cause HTTP 400. Context/LLM Setup mục 6.3 đã cập nhật.

## Bước 20 Lấy thông điệp Google khi labels không nhận dạng được

- Bằng chứng: user chạy metadata thành công HTTP 200/generateContent=true với certifi 143 CA. Probe POST vẫn HTTP 400/INVALID_ARGUMENT, reason=UNSPECIFIED/fieldHints=[]. Kết nối TLS script đã hoạt động trên môi trường người dùng; nguyên nhân request vẫn thiếu bằng chứng chi tiết. Bộ lọc fixed labels hiện chưa nhận dạng được lỗi này.
- Thay đổi: probe_gemini_request.py thêm --details để đọc error.message/field violation description cho request synthetic, che key hiện hành/các dạng URL encoded/Google key/Bearer/header credentials, bỏ control chars và giới hạn 3 dòng × 1500 ký tự. Không đổi model/schema/Java dựa vào phỏng đoán; default probe và backend vẫn dùng fixed labels.
- Checks: 11 tests Python đạt, gồm unknown cause được hiển thị khi có details, key redaction/URL encoding/line bounds và free-form text tắt mặc định. Python syntax, shell snippets LLM Setup và diff whitespace đạt. Chưa gọi API live hoặc chạy lại backend/client tests vì chỉ thay Python diagnostics.
- Bước tiếp: user chạy python3 backend/scripts/probe_gemini_request.py --details trong terminal đã nạp .env, gửi vendorMessage đã che key; sửa đúng tham số/quyền/quota theo thông điệp thực. Chưa tuyên bố giải quyết HTTP 400; không coi tests mocked là live pass. LLM Setup mục 6.4/context đã cập nhật.

## Bước 21 Đối chiếu từng phần request khi vendor không nêu trường lỗi

- Bằng chứng: --details live vẫn HTTP 400/INVALID_ARGUMENT với vendorMessage=Request contains an invalid argument. Chưa có field/reason cụ thể; không đủ kết luận root cause. Google REST example dùng application/json trong responseFormat, API reference dùng enum APPLICATION_JSON; cần đối chiếu endpoint thật thay vì đổi payload tiếp theo phỏng đoán.
- Thay đổi: --isolate --details chạy tối đa 6 synthetic cases: baseline text/max512, schema nhỏ với ba format enum/literal/legacy, full schema trên format đã được nhận, full synthetic payload. Mỗi case một request, dừng nếu baseline không thành công hoặc gặp network/auth/quota/service khi so format; chỉ 400 tiếp tục phép so sánh. Không tự retry, không thay .env/adapter/job, không coi HTTP 200/MAX_TOKENS là semantic success. Tối đa 6 requests có thể tiêu thụ quota/phí, mô tả rõ trong CLI/guide.
- Checks: 15 Python tests đạt, gồm không gửi tiếp sau baseline fail/429, giới hạn 6 calls, chọn format từ response mocked, giữ canonical payload không đổi và không gửi full input khi full schema thất bại. Python AST/shell guide snippets/diff whitespace đạt. Không chạy API live từ agent hoặc lặp Maven/client tests khi chỉ sửa Python diagnostics.
- Bước tiếp: cần output live --isolate để xác định lỗi bắt đầu tại baseline, format, schema hoặc phần full payload. Sau đó sửa adapter theo evidence và thử job thật. Chưa khẳng định đã sửa HTTP 400 hoặc model/key không hợp lệ. LLM Setup mục 6.5/context đã cập nhật.


## Bước 22 Đưa cách gọi Gemini dạng text vào luồng phân tích của giao diện

- Bằng chứng: người dùng tự gọi Python requests với model gemini-3.8-flash, header x-goog-api-key và contents, nhận HTTP 200/Bằng 2. Model/key/đường truyền dùng được cho request đó; chưa chứng minh structured schema được nhận hoặc xác định chính xác field gây 400.
- Thay đổi: GeminiProvider gửi hướng dẫn + schema canonical + source JSON trong contents, chỉ giữ generationConfig.maxOutputTokens từ job; bỏ responseFormat/systemInstruction/thinkingConfig. JSON text hoặc một fence bao toàn bộ kết quả được chuyển qua validator cũ; không tự trích JSON từ prose, sửa JSON lỗi hay bỏ kiểm tra evidence. Giữ output budget, timeout/1 MiB limit/lease/cancel, không fallback provider hoặc tự gửi lại. OpenAI không đổi.
- Snapshot: policy pin prompt riêng Gemini meeting-events-v1-gemini-text-v1; worker chặn job snapshot cũ bằng POLICY_VERSION_UNAVAILABLE thay vì đổi ngầm request. Người dùng restart backend rồi chọn Gemini/bấm Phân tích tạo job mới; extension 0.3.0 đã có polling COMPLETED → tải đề xuất nên không cần thay frontend.
- Checks: 66 backend unit tests, 0 failure/error/skip và package JAR đạt. Có kiểm tra pinned model/token cap/credential header, pipeline thực dùng adapter với HTTP response giả, JSON/fence → candidate Mai/hạn UTC/evidence/PENDING_REVIEW; prose/trailing/duplicate keys/sai schema/ID giả/người nhận ngoài nguồn bị từ chối; snapshot Gemini cũ không dispatch. 16 Python tests đạt; probe mặc định dùng text shape mới, --isolate giữ diagnostics structured cũ. Không gọi Google thật từ agent; không chạy lại DB integration bị sandbox chặn socket hoặc extension tests khi frontend không đổi.
- Đánh giá: hoàn tất sửa code và kiểm tra offline, chưa tuyên bố chạy thành công trên panel với Gemini thật. Bước tiếp: restart/source .env, xác minh health UP, tạo job Gemini mới với mẫu hai công việc, đợi COMPLETED và kiểm tra đề xuất/lưu sau reopen. Nếu còn lỗi, lấy safe log mới gắn job mới để phân biệt HTTP rejection với output JSON/timeout. LLM Setup mục 0 có các bước áp dụng; không bắt người dùng lặp lại diagnostics cũ trước khi test UI.

## Bước 23 Review task và Side Panel theo SDS 14.4

- Mục tiêu ngày 09/10/2026: người dùng xác nhận 14.3 đã xong, yêu cầu làm tiếp từ 14.4 — preview, progress, saved draft, evidence, warnings, manual task, autosave/version conflict, lịch sử, đăng nhập lại khôi phục meeting.
- Backend: Flyway V4 `tasks` (origin AI/USER, version, review_status/sync_status tách rời, deadline_raw/due_local/due_at/timezone + resolution, member_resolution, ai_suggestion giữ giá trị AI gốc, edited_fields, create_key/hash) và `task_evidence` (segment reference, quote đọc từ segment khi hiển thị, FK SET NULL cho purge). V4 backfill task từ analysis_results COMPLETED cũ. Job COMPLETED ghi task trong cùng transaction với kết quả. Package `task`: GET list (AI của job hiện hành + USER), POST manual (Idempotency-Key), PATCH (expectedVersion, field vắng giữ/null xóa, chặn trường hệ thống 400, trelloMemberId 422 tới 14.5, DST 422), DELETE soft reject, restore, GET evidence (locator). Khóa meeting → task; compare-and-update version, 409 STALE_VERSION kèm currentVersion. Warnings do server tính theo trường, có cờ blocking; readyForApproval. History thêm analysisStatus/pending/rejected. ApiException hỗ trợ details. Bỏ AnalysisResultService (response GET tasks đổi sang list task).
- Extension 0.4.0: ReviewPanel thay CandidateList — thẻ task với badge AI/Thủ công/Đã sửa, cảnh báo, bằng chứng + vị trí nguồn, editor (tên, mô tả, người phụ trách + Không giao người, hạn: chưa quyết định/đặt ngày giờ mặc định 17:00 + múi giờ/không đặt hạn, dùng đề xuất AI, ưu tiên, đính kèm bằng chứng), autosave debounce 800 ms một request mỗi lúc, xung đột hiển thị giá trị server và cho chọn giữ/bỏ, không tự retry. Thêm task thủ công, loại bỏ/khôi phục, chọn task, nút Tạo N card khóa kèm lý do. Xác nhận trước khi Phân tích lại; cảnh báo rời trang khi còn sửa chưa lưu.
- Checks thực sự chạy (agent, Linux + PostgreSQL 16 thật, user thường): `mvnw verify` 103 tests đạt, 0 fail — lần đầu 26 integration cũ chạy được, cộng 8 integration review mới và 5 unit rules. Extension 55 tests đạt (esbuild 0.28 thay binary darwin chỉ cho test). Bundle main.jsx bằng esbuild đạt; render ReviewPanel trong Chromium headless 380px với API giả: sửa → lưu → cảnh báo mất, xung đột → giữ thay đổi lưu thành công, không lỗi console.
- Chưa kiểm chứng: `npm run check` (Vite/Rollup) trên máy người dùng, Chrome thật + backend thật + AI thật, migration V4 trên DB đang có dữ liệu của người dùng (backfill đã test trên dữ liệu tạo trong test).
- Môi trường: mạng agent chặn Maven/npm registry; dùng bản sao `~/.m2` và `extension/node_modules` của người dùng (đọc), file tạm nằm ở `Code/.local/` (gitignored).
- Bước tiếp: người dùng chạy Review_Test_Guide R1–R17; sau đó 14.5 Trello vertical slice (OAuth, Board/List/member, resolve, snapshot, approve-and-sync).

## Bước 24 Trello vertical slice theo SDS 14.5

- Mục tiêu ngày 10/10/2026: người dùng xác nhận đã test xong 14.4 và yêu cầu làm 14.5–14.8 rồi test toàn bộ.
- Backend:
  - Flyway V5 thêm các bảng: `trello_connections` (mỗi user tối đa một kết nối ACTIVE/REAUTH), `authorization_transactions`, `meeting_destinations` (có version), `member_aliases`, `task_snapshots` (bất biến), `sync_jobs` (unique theo Idempotency-Key), `sync_items` (unique index: mỗi task chỉ một item còn hiệu lực), `sync_attempts`, `audit_events`.
  - Kết nối Trello:
    - Hai cách: OAuth 2.0 (state + PKCE S256, dùng một lần, hạn 10 phút, callback HTML công khai) và API key + token.
    - Token mã hóa AES-256-GCM bằng `TOKEN_ENCRYPTION_KEY`.
    - Refresh chạy tuần tự. Nếu refresh lỗi, kết nối chuyển REAUTH_REQUIRED.
  - Destination:
    - Kiểm tra List thuộc Board và cả hai đang mở.
    - Đổi Board thì tăng version, bỏ member không còn trên Board, và bị khóa khi còn sync đang chạy.
  - Member resolution:
    - Không phân biệt dấu. Kết quả là alias → RESOLVED, khớp một người → SUGGESTED, khớp nhiều người → AMBIGUOUS.
    - Không bao giờ tự giao.
  - Deadline: gợi ý theo ngày họp, không theo ngày upload. Người dùng phải xác nhận.
  - Approve-and-sync:
    - Chạy trong một transaction: khóa meeting/task theo thứ tự, kiểm tra version và blocker, tạo snapshot + item, ghi audit.
    - Cùng key + cùng body trả lại job cũ; cùng key + body khác trả 409.
  - Worker:
    - Dùng lease. Validate List/Board/member trước khi gửi.
    - Commit DISPATCHED trước khi gọi tạo card.
    - Timeout, 5xx hoặc lưu DB lỗi sau khi tạo → UNKNOWN, không bao giờ tự tạo lại.
    - Đối soát tìm card trên Board bằng `AI_MTT_REF=<taskId>` trong mô tả card.
  - Hành động thủ công: retry (chỉ lỗi tạm thời), reconcile, link-card, recreate (chỉ sau khi đối soát không thấy, cần xác nhận rủi ro trùng, có audit).
  - Task FAILED được sửa hoặc loại bỏ: item cũ chuyển SUPERSEDED và task về PENDING_REVIEW.
- Extension 0.5.0:
  - Khối Trello: kết nối OAuth hoặc token, ngắt kết nối, chọn Board/List.
  - Trong từng task: chọn thành viên, xác nhận gợi ý, ghi nhớ tên, dùng gợi ý hạn.
  - Nút Tạo N card bị khóa kèm lý do. Màn hình xác nhận hiện Board › List, người được giao, ngày-giờ-múi giờ và xem trước mô tả.
  - Khối kết quả tạo card: Đã tạo / Lỗi / Chưa rõ kết quả, có hành động tương ứng. Chỉ mở link card dạng https trello.com.
- Checks: xem Bước 28.
- Chưa kiểm chứng: Trello thật (token mode), OAuth Atlassian thật (scope và URL token của app thật có thể khác mặc định), giới hạn rate thật.

## Bước 25 Transcript dài theo SDS 14.6

- Thay đổi:
  - `AnalysisPipeline` chạy một lần gọi khi transcript vừa ngân sách và chưa có checkpoint. Nếu không, chia phần theo ranh giới segment, có overlap 2 segment, tối đa 20 phần.
  - Mỗi phần nhận known_tasks (giới hạn byte) và quy tắc CHUNK_RULES. Validator từ chối CREATE trùng ref đã biết.
  - Kết quả từng phần lưu checkpoint trong `chunk_results` (V6). Event trùng ở vùng overlap được loại theo evidence.
  - Kết quả cuối được reconcile theo thứ tự segment.
  - Một phần lỗi → PARTIAL_FAILED, không publish task. Retry chạy tiếp từ phần lỗi.
  - Khi `CHUNKING_ENABLED=false` (mặc định), transcript vượt ngân sách trả `TRANSCRIPT_OVER_BUDGET`.
  - Mỗi lần gọi LLM ghi `processing_logs` (phần, token, latency, mã lỗi), không có nội dung.
- Checks: ChunkPlanTest và ChunkingIntegrationTest (AT11 sửa ở phần sau, AT12 hủy ở phần cuối, AT13 một phần lỗi + resume, transcript ngắn vẫn một lần gọi) đều đạt với provider giả.
- Chưa kiểm chứng: chất lượng chunking với OpenAI/Gemini thật. Theo SDS 9.7, chỉ bật trên môi trường thật sau khi chạy `vi-12-long` và các case sửa/hủy xa trên dataset.

## Bước 26 Reliability và privacy theo SDS 14.7

- Thay đổi:
  - PrivacyService:
    - Xóa transcript: 409 khi đang phân tích.
    - Xóa meeting: 409 khi đang phân tích hoặc còn sync chưa xong. Không đụng Trello.
    - Purge: xóa raw/normalized, segment, chunk output; scrub `analysis_results` chỉ còn usage; xóa trích dẫn trong snapshot và trong mô tả đã lưu.
  - RetentionWorker: xóa nguồn quá hạn, cho thêm 24 giờ grace nếu còn công việc đang chạy; xóa processing_logs sau 90 ngày, session sau 7 ngày, giao dịch OAuth hết hạn.
  - Panel có mục xóa transcript/xóa meeting kèm xác nhận.
- Checks (đạt):
  - ReliabilityPrivacyIntegrationTest:
    - AT29 purge: không còn bản sao nguồn; task và liên kết card còn.
    - Quy tắc xóa.
    - AT28: lệnh trong transcript chỉ là dữ liệu.
    - AT30: provider lỗi không chuyển sang provider khác.
    - Log không chứa transcript, token hay key (OutputCapture).
  - Các case AT18–27 trong TrelloSyncIntegrationTest.
- Chưa kiểm chứng: purge trên DB thật có dữ liệu lâu ngày; rate limit nhiều replica.

## Bước 27 User evaluation và demo theo SDS 14.8

- Thay đổi:
  - `evaluation/`:
    - Dataset `seed-v1` gồm 14 transcript tổng hợp, chia dev/test.
    - `run_eval.py` chạy qua backend thật bằng tài khoản riêng.
    - `evaluate.py` tính micro/macro P/R/F1, assignee, deadline, evidence, cancellation, unsupported field, latency/token theo nhóm.
    - Mẫu CSV usability crossover và `usability_report.py`.
  - `tools/fake_trello.py`: Trello giả dùng stdlib, có OAuth + PKCE, trang Board/card và bơm lỗi (timeout/5xx sau tạo, reject, 429, revoke).
  - `tools/e2e/`: build extension và kịch bản Playwright.
  - Tài liệu: Trello_Setup, Demo_Script (3 case: thành công, trùng tên, UNKNOWN), Evaluation_Guide, Full_Test_Guide; README và API_Test_Guide mục 8.
- Lưu ý: dataset 14 item còn dưới mức 30–50 mà SDS 14.3 đề xuất. Chưa có số đo AI thật hay usability. Không ghi chỉ số nào là kết quả đã đạt.

## Bước 28 Test toàn bộ 14.5–14.8

- Checks thực sự chạy (agent, Linux, JDK 21, PostgreSQL 16 thật, user thường, ngày 10/10/2026):
  - `mvnw -o verify`: **134 tests, 0 failure/error/skip**, BUILD SUCCESS.
    - 80 unit.
    - 54 integration: 9 foundation, 13 jobs, 4 LLM, 8 review, 11 Trello sync, 3 chunking, 6 reliability/privacy.
  - Extension: `node --test` **61/61 đạt**. Bundle esbuild ra manifest 0.5.0 đạt.
  - Python: 2 test Trello giả và 6 test đánh giá đạt.
  - **Browser E2E đạt** ("E2E PASS", không có lỗi console). Môi trường: extension thật trong Chromium + backend (E2eServer, AI theo quy tắc) + PostgreSQL + Trello giả. Kịch bản:
    - Đăng ký, dán transcript, phân tích.
    - OAuth (PKCE S256), Board/List.
    - Mai SUGGESTED, Long AMBIGUOUS, gợi ý hạn Thứ Sáu 09/10/2026 17:00.
    - Tạo 2 card: đúng member, due `2026-10-09T10:00:00Z`, có marker.
    - Timeout sau tạo → UNKNOWN, không có nút Thử lại → đối soát → SYNCED, `createCalls`=3 (không có card trùng).
    - Reload khôi phục trạng thái; xóa meeting thì card vẫn còn.
- Lỗi phát hiện và đã sửa trong lúc test: refresh token rollback (chuyển markReauth ra ngoài transaction), callback HTML thiếu UTF-8, fold tiếng Việt trong DeadlineSuggester, bean LlmConfigurationTest (ObjectProvider), kiểu tổng token chunk (`::bigint`), cùng một số lỗi trong chính test.
- Chưa kiểm chứng:
  - Trello thật và OAuth Atlassian thật.
  - AI thật trên dataset; usability với người dùng.
  - `npm run check` (Vite) trên macOS.
  - Migration V5/V6 trên DB đang dùng của người dùng.
- Bước tiếp cho người dùng:
  1. Khởi động lại backend (V5/V6) với `TOKEN_ENCRYPTION_KEY`.
  2. Chạy `npm ci && npm run check`, rồi Reload extension.
  3. Chạy Demo_Script với Trello giả, rồi Full_Test_Guide mục 2.
  4. Thử Board Trello thật bằng token.
  5. Chạy run_eval/evaluate với provider thật, rồi tổ chức usability crossover.


## Bước 29 Tổng hợp báo cáo cho giảng viên ngày 11/10/2026

- Ngày 10/10: rà soát tài liệu và code hiện tại (extension 0.5.0, backend V1–V6), viết Bao_Cao_Tien_Do_2026-10-10.md: lịch sử triển khai, kiến trúc, luồng nhập/phân tích/review/Trello, cơ chế version/lease/idempotency/UNKNOWN/privacy, giới hạn, kế hoạch, kịch bản demo và bản phát biểu.
- Người dùng xác nhận đã tạo và kiểm tra card trên Trello thật từ extension. Đây là xác nhận kiểm thử thủ công; chưa có ảnh/checklist chi tiết và chưa xác định token mode hay OAuth. Không suy ra OAuth Atlassian thật hoặc độ chính xác AI trên dataset đã đạt.
- Chạy lại trên máy hiện tại: npm run check — 61/61 tests đạt, Vite 7.3.7 build đạt, MV3 verified với backend http://127.0.0.1:8080. Không thay code ứng dụng.
- Đối chiếu bằng chứng: 134 tests backend và Browser E2E là kết quả ghi trong Bước 28 (Linux, PostgreSQL thật, Trello/AI giả ở E2E), chưa chạy lại trong lượt này. Các XML target local còn lượt cũ với lỗi khởi tạo PostgreSQL, không dùng làm report mới. Cần lưu bộ report theo commit để nghiệm thu.
- Bước tiếp: lưu ảnh/checklist demo Trello thật, tái lập backend reports, chạy baseline AI và usability.


## Bước 30 Cây thư mục có chú thích từng file

- Ngày 10/10: tạo Directory_Tree.md từ danh sách file hiện có, chú thích riêng 192 file nguồn/cấu hình/test/tài liệu, bao gồm chính tài liệu mới. Đối chiếu các class, API, export và script; phân biệt production/test/demo.
- Bổ sung bảng file cục bộ/build/cache, giải thích Controller/Service/Store/Worker/Provider và bản đồ file theo luồng nhập, phân tích, review, tạo card, retention. Ghi rõ manifest được sinh khi build; GeminiSchema chỉ còn dùng trong test tương thích; chưa có module ghi âm/STT.
- Kiểm tra mọi file trong phạm vi đều có mô tả, cấu trúc theo đường dẫn thật và diff whitespace. Chỉ sửa tài liệu, không chạy lại tests ứng dụng hoặc đọc giá trị .env. README có liên kết đến cây thư mục.
