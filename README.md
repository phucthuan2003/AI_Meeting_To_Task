# AI Meeting to Task

Chuyển transcript thành công việc được người dùng duyệt rồi tạo trên Trello. Thiết kế chuẩn ở `docs/ai-meeting-to-task/AI_Meeting_to_Task_SDS_v3.docx`.

Hiện triển khai chặng 14.1: backend Spring Boot modular monolith, auth/session, owner checks và nhập transcript có preview. Chưa có analysis worker, LLM, Chrome Side Panel hoặc Trello. Mỗi chặng ghi kết quả thật tại `docs/ai-meeting-to-task/Implementation_Progress.md` và cập nhật `SDS_v3_Context.md`.

## Chạy backend local

Để chạy thử bằng một lệnh trên terminal local (Docker Desktop đang mở):

```sh
python3 backend/scripts/run_local.py
```

Script tạo PostgreSQL riêng dùng dữ liệu tạm, chạy `verify`, thử luồng HTTP rồi giữ backend trên localhost (ưu tiên cổng 8080). URL health được in khi backend sẵn sàng. `Ctrl+C` dừng backend và DB của phiên demo; không dùng volume Compose hoặc DB hiện có. Secrets sinh ngẫu nhiên trong bộ nhớ; không cần tạo `.env`. Chưa có giao diện Extension nên phiên này kiểm tra API backend. Nếu Codex sandbox chặn Docker socket, chạy lệnh này trong terminal local của bạn.

Cần JDK 17–25, Docker Desktop và kết nối mạng ở lần tải dependencies đầu. Maven Wrapper nằm trong `backend/`, không cần cài Maven riêng.

1. Sao chép `.env.example` thành `.env`; tự đặt `DATABASE_PASSWORD` và tạo `JWT_SIGNING_KEY` bằng `openssl rand -base64 32`. Không commit `.env`.
2. Điền `EXTENSION_ORIGIN_ALLOWLIST` với origin extension thật (ví dụ `chrome-extension://<extension-id>`), hoặc origin frontend local. Cách nhau bằng dấu phẩy; không dùng wildcard.
3. Khởi động PostgreSQL và export biến môi trường trước khi chạy backend:

```sh
docker compose up -d postgres
set -a
. ./.env
set +a
cd backend
./mvnw spring-boot:run
```

Backend ở `http://localhost:8080`; health ở `/actuator/health`. PostgreSQL chỉ publish cổng trên `127.0.0.1`. Ngoài local development phải đặt API sau HTTPS. `JWT_SIGNING_KEY` thiếu, không phải base64 hoặc dưới 32 byte thì backend từ chối khởi động.

`.env` là shell assignment; nếu mật khẩu chứa ký tự đặc biệt, đặt trong dấu nháy đơn. Maven/Spring không tự nạp `.env`; lệnh export trên là cần thiết. Khi Docker chưa chạy, bật Docker Desktop trước `compose up`.

## Các API đã có

Hướng dẫn request/response, Postman/cURL, upload file, version conflict và owner tests: [API Test Guide](docs/ai-meeting-to-task/API_Test_Guide.md).

| Method | Path | Chức năng |
| --- | --- | --- |
| POST | `/api/v1/auth/register` | Email, password 10–72 ký tự và tối đa 72 byte UTF-8 |
| POST | `/api/v1/auth/login` | JWT 15 phút, sessionId, expiresAt |
| GET | `/api/v1/auth/me` | User hiện tại |
| POST | `/api/v1/auth/logout` | Revoke session, JWT cũ hết quyền ngay |
| POST | `/api/v1/meetings` | JSON paste hoặc multipart đúng một file TXT/DOCX |
| GET | `/api/v1/meetings` | History của owner; cursor, limit 1–100 |
| GET | `/api/v1/meetings/{id}` | Metadata, version, preview tối đa 4000 ký tự |
| GET | `/api/v1/meetings/{id}/transcript` | Segment và nguồn; cursor là sequence cuối, mặc định -1 |
| PATCH | `/api/v1/meetings/{id}/input` | Thay toàn bộ input/metadata bằng JSON, kiểm tra expectedVersion |

API ngoài register/login/health yêu cầu `Authorization: Bearer <accessToken>`. JWT hệ thống không có refresh token trong MVP. Logout hoặc hết hạn yêu cầu đăng nhập lại; draft đã lưu ở DB vẫn còn. Truy cập meeting của user khác trả 404.

Paste JSON:

```json
{
  "title": "Sprint Planning",
  "meetingDate": "2026-10-05",
  "timezone": "Asia/Ho_Chi_Minh",
  "transcriptText": "Nam: Mai sửa login nhé.\nMai: Được, nhưng chưa chốt hạn."
}
```

Response có `meetingId`, `inputVersion`, `transcriptRevision`, `preview`, `characterCount`, `segmentCount`, `warnings`, `sourceExpiresAt` và `analysisStatus: NOT_STARTED`. Input được lưu và preview; chưa có lời gọi provider.

File multipart dùng field `file`, metadata `title`, `meetingDate`, `timezone`. Không gửi kèm `transcriptText`, file thứ hai hoặc field khác. Giới hạn file 10 MiB, JSON body 1 MiB và văn bản 200000 Java UTF-16 code units. JSON được giới hạn cả Content-Length và stream trước deserialize. TXT chỉ UTF-8/BOM. DOCX kiểm tra extension/MIME/cấu trúc và bytes giải nén; đọc paragraph/bảng/nested table theo body order; headers/footers/comments không nhập vào transcript. Nhãn speaker được giữ trong text, `speaker` hiện null để pipeline xác minh; timestamp rõ trong text được giữ riêng.

Mỗi segment giữ `normalizedStart/End`, `sourceLocator.rawStart/End`, line/paragraph/cell và `sourceText` gốc. Offsets dùng Java UTF-16 và end-exclusive. Ánh xạ hiện ở cấp segment/dòng; không hứa ánh xạ từng ký tự sau NFC. Chưa có range evidence API.

PATCH dùng `expectedVersion` từ response mới nhất và `transcriptText` mới. Metadata trong request là bản thay thế đầy đủ; field bỏ trống/null được xóa. Lưu revision mới, giữ revision trước; request stale trả 409 `STALE_VERSION`. Guard job/sync active sẽ bổ sung khi triển khai vòng đời job.

Lỗi trả `timestamp`, `traceId`, `status`, `code`, `message`, `retryable`, `details`. Header `X-Trace-Id` khớp body. Không trả stacktrace, password hoặc text request trong thông báo lỗi.

## Kiểm thử

```sh
cd backend
./mvnw verify
```

Unit tests kiểm tra parser/normalization/source map và rate limit. Integration tests khởi động PostgreSQL 16.13 tạm qua Zonky để chạy Flyway, schema validation, API/auth/owner/concurrency; không dùng H2. DB tạm dừng khi test JVM thoát. macOS arm64 có dependency binary tương ứng; Windows/Intel/Linux amd64 dùng binary mặc định. Chạy với user thường, không chạy embedded PostgreSQL bằng root.

Nếu sandbox không cho phép PostgreSQL dùng shared memory, chạy tests với một DB PostgreSQL rỗng riêng (không trỏ DB chứa dữ liệu thật). Tests tự tạo account/meeting và giữ fixtures trong DB đó:

```sh
TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/ai_mtt_test \
TEST_DATABASE_USER=postgres \
TEST_DATABASE_PASSWORD=postgres \
./mvnw verify
```

Không coi `-DskipTests package` là kết quả pass của tests. Kết quả thực ở file tiến độ.

Đã chạy 23 tests đạt trên PostgreSQL 16.13 (14 unit và 9 integration). HTTP smoke chạy JAR đóng gói qua HTTP thật cũng đạt; có thể chạy lại từ thư mục project với DB test riêng:

```sh
SMOKE_DATABASE_URL=jdbc:postgresql://localhost:5432/ai_mtt_test \
SMOKE_DATABASE_USER=postgres \
SMOKE_DATABASE_PASSWORD=postgres \
python3 backend/scripts/http_smoke.py
```

Script khởi động JAR ở cổng 18080, dùng JWT key tạm sinh ngẫu nhiên, tạo account/meeting giả để kiểm tra rồi dừng JVM. Dùng `SMOKE_PORT` nếu cần chọn cổng khác; fixtures vẫn nằm trong DB test. Cần chạy `./mvnw verify` để có JAR trước.

## Cấu trúc và phần tiếp theo

`backend/src/main/java/vn/aimtt/` có module `auth`, `meeting`, `transcript`, `common`. PostgreSQL schema được quản lý bằng Flyway, JPA chỉ validate. Chặng tiếp theo thêm `job`, `ai`, `task`, `trello`, `evaluation` khi có hành vi tương ứng.

Chưa có purge scheduler; `sourceExpiresAt` hiện là deadline đã lưu, chưa chứng minh nguồn tự bị xóa sau 30 ngày. Rate limit đang giữ trong một process, cần enforcement dùng chung khi scale. Chưa benchmark password cost/input limits, chưa triển khai cleanup session/revision. Đây là backend phát triển; chưa đủ điều kiện production/demo Trello thật.

Phiên bản được khóa theo SDS: [Spring Boot 3.5 system requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html), [Apache POI parser protections](https://poi.apache.org/components/configuration.html), [Zonky embedded PostgreSQL](https://github.com/zonkyio/embedded-postgres).
