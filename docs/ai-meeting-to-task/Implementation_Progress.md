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
