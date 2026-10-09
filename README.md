# AI Meeting to Task

Chuyển transcript thành công việc được người dùng duyệt rồi tạo trên Trello. Thiết kế chuẩn ở `docs/ai-meeting-to-task/AI_Meeting_to_Task_SDS_v3.docx`.

Hiện có backend foundation, DB analysis queue/lease/checkpoints và Chrome React Side Panel 0.3.0. Hai adapter OpenAI/Gemini gọi AI khi backend có key; kết quả được kiểm tra schema/evidence và lưu thành đề xuất cần duyệt. Job foundation hoặc provider chưa cấu hình vẫn báo PROVIDER_NOT_CONFIGURED. Chưa có thao tác sửa/duyệt task hoặc Trello. Mỗi chặng ghi kết quả thật tại `docs/ai-meeting-to-task/Implementation_Progress.md` và cập nhật `SDS_v3_Context.md`.

## Chrome Extension

[Hướng dẫn build, Load unpacked, CORS và test extension](docs/ai-meeting-to-task/Extension_Setup.md).

[Hướng dẫn cấu hình key và test AI thật](docs/ai-meeting-to-task/LLM_Setup.md). Khởi động lại backend để Flyway áp dụng V2/V3, build rồi Reload extension. [Job/poll/cancel/retry/restart](docs/ai-meeting-to-task/Analysis_Job_Test_Guide.md) vẫn có guide riêng. Worker mặc định bật; đặt ANALYSIS_WORKER_ENABLED=false khi cần quan sát QUEUED/cancel trong development.

```sh
cd extension
npm ci
npm run check
```

Load `extension/dist` trong `chrome://extensions`, lấy extension ID rồi chạy backend với `EXTENSION_ORIGIN_ALLOWLIST='chrome-extension://YOUR_EXTENSION_ID'`. Build mặc định gọi `http://127.0.0.1:8080`; dùng `API_ORIGIN` khi build để đổi origin. JWT chỉ giữ trong Chrome session; backend lưu transcript, browser chỉ giữ meeting ID theo account. Đóng panel sẽ mất input chưa lưu.

## Chạy backend local

Để chạy thử bằng một lệnh trên terminal local (Docker Desktop đang mở):

```sh
python3 backend/scripts/run_local.py
```

Script tạo PostgreSQL riêng dùng dữ liệu tạm, chạy `verify`, thử luồng HTTP rồi giữ backend trên localhost (ưu tiên cổng 8080). URL health được in khi backend sẵn sàng. `Ctrl+C` dừng backend và DB của phiên demo; không dùng volume Compose hoặc DB hiện có. Secrets sinh ngẫu nhiên trong bộ nhớ; không cần tạo `.env`. Truyền `EXTENSION_ORIGIN_ALLOWLIST` từ môi trường khi dùng extension. Nếu Codex sandbox chặn Docker socket, chạy lệnh này trong terminal local của bạn.

Cần JDK 17–25, Docker Desktop và kết nối mạng ở lần tải dependencies đầu. Maven Wrapper nằm trong `backend/`, không cần cài Maven riêng.

1. Dùng `.env` hiện có ở Code; khi setup mới, tạo DATABASE_URL/USER/PASSWORD theo compose.yaml, JWT_SIGNING_KEY bằng `openssl rand -base64 32` và EXTENSION_ORIGIN_ALLOWLIST. Không commit `.env`. Key AI là các biến riêng theo LLM_Setup; không thay mật khẩu DB đã dùng.
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
| GET | `/api/v1/analysis-policies` | Hai lựa chọn OpenAI/Gemini và trạng thái sẵn sàng; không trả API key |
| POST | `/api/v1/meetings/{id}/analysis-jobs` | Enqueue 202 theo inputVersion; hỗ trợ Idempotency-Key |
| GET | `/api/v1/jobs/{id}` | Trạng thái, tiến độ nguồn/chunks, lỗi và snapshot |
| POST | `/api/v1/jobs/{id}/cancel` | Hủy queued/yêu cầu hủy processing |
| POST | `/api/v1/jobs/{id}/retry` | Retry lỗi retryable, giữ snapshot/checkpoint |
| GET | `/api/v1/meetings/{id}/tasks?analysisJobId=uuid` | Đọc đề xuất của job COMPLETED hiện hành; query guard optional |

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

PATCH dùng `expectedVersion` từ response mới nhất và `transcriptText` mới. Metadata trong request là bản thay thế đầy đủ; field bỏ trống/null được xóa. Lưu revision mới, giữ revision trước; request stale trả 409 `STALE_VERSION`. Job active khóa sửa bằng 409 RESOURCE_BUSY. Revision mới xóa pointer kết quả hiện hành; job lịch sử giữ snapshot cũ. Guard sync sẽ thêm ở chặng Trello.

Lỗi trả `timestamp`, `traceId`, `status`, `code`, `message`, `retryable`, `details`. Header `X-Trace-Id` khớp body. Không trả stacktrace, password hoặc text request trong thông báo lỗi.

## Kiểm thử

```sh
cd backend
./mvnw verify
```

Unit tests kiểm tra parser/normalization/source map và rate limit. Integration tests khởi động PostgreSQL 16.13 tạm qua Zonky để chạy Flyway, schema validation, API/auth/owner/concurrency; không dùng H2. DB tạm dừng khi test JVM thoát. macOS arm64 có dependency binary tương ứng; Windows/Intel/Linux amd64 dùng binary mặc định. Chạy với user thường, không chạy embedded PostgreSQL bằng root.

Suite hiện có 66 unit tests và 26 integration tests (9 foundation, 13 jobs, 4 LLM). Unit/package đạt; thử integration suite bị sandbox chặn mở socket PostgreSQL trước assertions nên chưa xác minh V2/V3/JDBC/concurrency/recovery. Extension 0.3.0 có chọn provider, tiến độ và đề xuất/evidence; 40 tests/build MV3 đạt với fixtures và React server-render. Chưa gọi API AI thật hoặc test Chrome mới. HTTP smoke ép key trống, kiểm tra preparation/enqueue/cancel/restart JVM; mới kiểm tra syntax.

Nếu sandbox không cho phép PostgreSQL dùng shared memory, chạy tests với một DB PostgreSQL rỗng riêng (không trỏ DB chứa dữ liệu thật). Tests tự tạo account/meeting và giữ fixtures trong DB đó:

```sh
TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/ai_mtt_test \
TEST_DATABASE_USER=postgres \
TEST_DATABASE_PASSWORD=postgres \
./mvnw verify
```

Không coi `-DskipTests package` là kết quả pass của tests. Kết quả thực ở file tiến độ.

Lượt triển khai ngày 05/10 đã chạy 23 tests đạt trên PostgreSQL 16.13 (14 unit và 9 integration), cùng HTTP smoke từ JAR. Ngày 09/10 chỉ chạy lại 14 unit tests đạt; integration/HTTP bị chặn Docker socket. Có thể chạy lại HTTP smoke từ thư mục project với DB test riêng:

```sh
SMOKE_DATABASE_URL=jdbc:postgresql://localhost:5432/ai_mtt_test \
SMOKE_DATABASE_USER=postgres \
SMOKE_DATABASE_PASSWORD=postgres \
python3 backend/scripts/http_smoke.py
```

Script khởi động JAR ở cổng 18080, dùng JWT key tạm sinh ngẫu nhiên, tạo account/meeting giả để kiểm tra rồi dừng JVM. Dùng `SMOKE_PORT` nếu cần chọn cổng khác; fixtures vẫn nằm trong DB test. Cần chạy `./mvnw verify` để có JAR trước.

## Cấu trúc và phần tiếp theo

`backend/src/main/java/vn/aimtt/` có module `auth`, `meeting`, `transcript`, `job`, `llm`, `common`. Flyway quản lý schema, JPA chỉ validate; JDBC dùng transaction ngắn để claim/lease/checkpoint/publish. Adapters/schema validation/candidate persistence đã có; còn kiểm chứng API thật, baseline chất lượng dev/held-out và ngân sách model thực để hoàn tất 14.3. Chunking, sửa/duyệt task và Trello chưa có.

Chưa có purge scheduler; `sourceExpiresAt` hiện là deadline đã lưu, chưa chứng minh nguồn tự bị xóa sau 30 ngày. Rate limit đang giữ trong một process, cần enforcement dùng chung khi scale. Chưa benchmark password cost/input limits, chưa triển khai cleanup session/revision. Đây là backend phát triển; chưa đủ điều kiện production/demo Trello thật.

Phiên bản được khóa theo SDS: [Spring Boot 3.5 system requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html), [Apache POI parser protections](https://poi.apache.org/components/configuration.html), [Zonky embedded PostgreSQL](https://github.com/zonkyio/embedded-postgres).
