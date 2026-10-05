# Tiến độ triển khai AI Meeting to Task

Cập nhật 05/10/2026, Asia/Ho_Chi_Minh. Đặc tả chuẩn: SDS v3.

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
| 14.2 Job và pipeline | Tiếp theo, chưa triển khai |
| 14.3 LLM và baseline | Chưa triển khai |
| 14.4 Review và Extension | Chưa triển khai |
| 14.5 Trello vertical slice | Chưa triển khai |
| 14.6 Transcript dài | Chưa triển khai |
| 14.7 Reliability và privacy | Mới có một số checks nền; chưa hoàn tất |
| 14.8 User evaluation/demo | Chưa triển khai |

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
