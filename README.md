# AI Meeting to Task

Chuyển transcript thành công việc được người dùng duyệt rồi tạo trên Trello. Thiết kế chuẩn ở `docs/ai-meeting-to-task/AI_Meeting_to_Task_SDS_v3.docx`.

Hiện có backend foundation, DB analysis queue/lease/checkpoints, hai adapter OpenAI/Gemini, task nháp có version (Flyway V4) và Chrome React Side Panel **0.5.0**. Chặng 14.5–14.8 bổ sung: kết nối Trello (OAuth 2.0 + PKCE hoặc API key + token, token mã hóa AES-GCM ở backend), chọn Board/List, đối chiếu người phụ trách (gợi ý/trùng tên, không tự giao), gợi ý hạn theo ngày họp, snapshot bất biến + approve-and-sync có Idempotency-Key, worker tạo card với lease, trạng thái UNKNOWN + đối soát bằng mã `AI_MTT_REF` (Flyway V5); chunking transcript dài có checkpoint từng phần, PARTIAL_FAILED và resume, retention purge, xóa transcript/meeting, processing logs không chứa nội dung (V6); Trello giả, Browser E2E, dataset đánh giá và kịch bản demo. Mỗi chặng ghi kết quả thật tại `docs/ai-meeting-to-task/Implementation_Progress.md` và cập nhật `SDS_v3_Context.md`.

[Cây thư mục và chú thích từng file](docs/ai-meeting-to-task/Directory_Tree.md) — tra cứu cấu trúc, vai trò module và luồng đọc code.

## Chrome Extension

[Hướng dẫn build, Load unpacked, CORS và test extension](docs/ai-meeting-to-task/Extension_Setup.md).

[Hướng dẫn test màn hình review task (14.4)](docs/ai-meeting-to-task/Review_Test_Guide.md).

[Cấu hình Trello (14.5)](docs/ai-meeting-to-task/Trello_Setup.md) · [Test với Trello thật](docs/ai-meeting-to-task/Real_Trello_Test.md) · [Kịch bản demo với Trello giả](docs/ai-meeting-to-task/Demo_Script.md) · [Test toàn bộ 14.5–14.8](docs/ai-meeting-to-task/Full_Test_Guide.md) · [Đánh giá AI/usability](docs/ai-meeting-to-task/Evaluation_Guide.md) · [Browser E2E](tools/e2e/README.md).

Biến mới: `TOKEN_ENCRYPTION_KEY` (bắt buộc để lưu kết nối Trello, 32 byte base64), `TRELLO_API_KEY` hoặc `TRELLO_CLIENT_ID/SECRET/CALLBACK_URL`, tùy chọn `CHUNKING_ENABLED` (mặc định false), `SYNC_WORKER_ENABLED`, `RETENTION_ENABLED`, `TRELLO_TIMEOUT`, `JOB_LEASE_SECONDS`.

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
| GET | `/api/v1/meetings/{id}/tasks?analysisJobId=uuid` | Task nháp hiện hành: AI của job COMPLETED hiện hành + task thủ công; query guard optional |
| POST | `/api/v1/meetings/{id}/tasks` | 201 task thủ công (origin USER); hỗ trợ Idempotency-Key |
| PATCH | `/api/v1/tasks/{id}` | Sửa nháp theo expectedVersion; field vắng giữ nguyên, null xóa |
| DELETE | `/api/v1/tasks/{id}?expectedVersion=n` | 204 loại bỏ mềm (REJECTED) |
| POST | `/api/v1/tasks/{id}/restore` | Khôi phục task REJECTED về PENDING_REVIEW |
| GET | `/api/v1/tasks/{id}/evidence` | Câu nguồn, vị trí nguồn và cảnh báo |
| GET | `/api/v1/trello/config` | Chế độ kết nối khả dụng (OAuth/token), không trả secret |
| GET | `/api/v1/trello/connections` | Kết nối Trello của user (trạng thái, tên thành viên) |
| POST | `/api/v1/trello/connections/authorize` | Bắt đầu OAuth: trả authorizeUrl + transactionId (state + PKCE S256, 10 phút, dùng một lần) |
| GET | `/api/v1/trello/authorizations/{id}` | Trạng thái giao dịch OAuth để panel polling |
| GET | `/api/v1/trello/oauth/callback` | Callback công khai (HTML), đổi code lấy token |
| POST | `/api/v1/trello/connections/token` | Kết nối bằng token (chế độ API key) |
| DELETE | `/api/v1/trello/connections/{id}` | Ngắt kết nối; item chưa gửi chuyển lỗi TRELLO_DISCONNECTED |
| GET | `/api/v1/trello/connections/{id}/boards`, `/api/v1/trello/boards/{boardId}/lists\|members?connectionId=` | Duyệt Board/List/thành viên |
| GET/PUT | `/api/v1/meetings/{id}/destination` | Nơi tạo card theo version; bị khóa khi còn card đang tạo |
| POST | `/api/v1/meetings/{id}/resolve-members` | Đối chiếu người phụ trách: RESOLVED (alias)/SUGGESTED/AMBIGUOUS/MISSING |
| POST | `/api/v1/meetings/{id}/resolve-deadlines` | Gợi ý hạn từ ngày họp (chỉ gợi ý, người dùng xác nhận) |
| GET | `/api/v1/tasks/{id}/card-preview` | Payload card sẽ tạo |
| POST | `/api/v1/meetings/{id}/approve-and-sync` | Duyệt + snapshot + sync job (bắt buộc Idempotency-Key, tối đa 50 task) |
| GET | `/api/v1/sync-jobs/{id}`, `/api/v1/meetings/{id}/sync-jobs/latest` | Kết quả từng item, hành động cho phép |
| POST | `/api/v1/sync-items/{id}/retry` \| `reconcile` \| `link-card` \| `recreate` | Thử lại lỗi tạm thời; đối soát UNKNOWN; gắn card có sẵn; tạo lại chỉ sau khi đối soát không thấy |
| DELETE | `/api/v1/meetings/{id}/transcript` | Xóa nguồn transcript ngay (giữ task và liên kết card) |
| DELETE | `/api/v1/meetings/{id}` | Xóa meeting và dữ liệu liên quan; không xóa card trên Trello |

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

Suite hiện có 134 tests: 80 unit và 54 integration (9 foundation, 13 jobs, 4 LLM, 8 review task, 11 Trello sync, 3 chunking, 6 reliability/privacy). Ngày 10/10/2026 toàn bộ 134 đạt trên PostgreSQL 16 thật (Linux, user thường); integration Trello dùng HTTP Trello giả trong JVM. Extension 0.5.0 có 61 tests đạt; `npm run check` (Vite build + MV3 checks) cần chạy lại trên máy local. Browser E2E (extension thật trong Chromium + backend + PostgreSQL + Trello giả, AI theo quy tắc) đạt. Chưa thử Trello thật, OAuth Atlassian thật hoặc AI thật trên dataset; xem [Full Test Guide](docs/ai-meeting-to-task/Full_Test_Guide.md).

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

`backend/src/main/java/vn/aimtt/` có module `auth`, `meeting`, `transcript`, `job`, `llm`, `task`, `trello`, `sync`, `privacy`, `common`. Flyway quản lý schema, JPA chỉ validate; JDBC dùng transaction ngắn để claim/lease/checkpoint/publish. Tool hỗ trợ: `tools/fake_trello.py` (Trello giả + bơm lỗi), `tools/e2e/`, `evaluation/` (dataset tổng hợp, chạy và chấm điểm).

Retention worker xóa nguồn sau hạn (`sourceExpiresAt`, giữ thêm 24 giờ khi job/sync còn chạy), log xử lý sau 90 ngày, session hết hạn sau 7 ngày. Rate limit vẫn giữ trong một process. Chunking mặc định tắt; chỉ bật khi đã đánh giá trên dataset. Chưa đủ điều kiện production: cần HTTPS, khóa bí mật quản lý tập trung và đánh giá AI thật trước demo ghi Trello thật.

Phiên bản được khóa theo SDS: [Spring Boot 3.5 system requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html), [Apache POI parser protections](https://poi.apache.org/components/configuration.html), [Zonky embedded PostgreSQL](https://github.com/zonkyio/embedded-postgres).
