# Kiểm thử analysis job — chặng 14.2

Cập nhật 09/10/2026. Chặng này có DB queue, snapshot input/metadata, claim/lease, checkpoint chuẩn bị nguồn, polling, cancel/retry và khóa sửa input. Backend nay có OpenAI/Gemini adapters và đề xuất task. **Guide này kiểm tra policy chuẩn bị `foundation-v1` với key AI tạm tắt**: chuẩn bị nguồn rồi FAILED/PROVIDER_NOT_CONFIGURED. Để gọi AI thật, dùng [LLM Setup](LLM_Setup.md).

## 1. Cập nhật backend và extension

Nếu panel đã hiện 2/2 segments và PROVIDER_NOT_CONFIGURED thì job chuẩn bị đã chạy đúng. Để phân tích thật, cấu hình key ở backend, restart và tạo job llm-v1 mới theo LLM Setup; job foundation cũ giữ nguyên. Các lệnh dưới tạm ghi đè hai key thành trống cho phiên JVM kiểm thử preparation, không sửa .env.

Để chạy lại bản hiện tại từ đầu, mở Docker Desktop và hai terminal riêng: terminal A giữ backend, terminal B build extension hoặc gọi health. Nếu backend cũ đang chạy, dừng bằng Ctrl+C tại terminal đó; không chạy hai backend cùng cổng 8080. Trong terminal A, dùng đường dẫn đầy đủ sau để tránh lỗi compose/.env khi đang ở home hoặc extension:

```sh
cd '/Users/phucthuan/phucthuan/Tài Liệu Học Năm 5/Công nghệ UD Doanh Nghiệp/Code'
pwd
docker compose up -d postgres
docker compose ps
```

postgres cần Up và health chuyển healthy. Nếu no configuration file provided thì chưa đứng đúng Code; compose.yaml nằm tại đây. Dùng .env đã cấu hình, giữ nguyên password/JWT key/extension origin:

```sh
set -a
source ./.env
set +a
cd backend
OPENAI_API_KEY='' GEMINI_API_KEY='' ANALYSIS_WORKER_ENABLED=true ./mvnw spring-boot:run
```

Flyway áp dụng V2 để thêm jobs/checkpoints/pointer và V3 để lưu kết quả AI. Không xóa volume/database hoặc sửa migration V1. Chờ application khởi động và giữ terminal A mở. Nếu thiếu biến, quay về Code để source `.env`; file không nằm trong backend. Trong terminal B kiểm tra:

```sh
curl -sS http://127.0.0.1:8080/actuator/health
```

Kết quả cần thấy {"status":"UP"}. Nếu Could not connect thì backend chưa chạy hoặc port khác; kiểm tra log terminal A trước khi thao tác panel. Hướng dẫn này dùng cổng mặc định 8080; nếu đã cấu hình PORT khác thì thay URL và API_ORIGIN khi build.

Sau đó trong terminal B:

```sh
cd '/Users/phucthuan/phucthuan/Tài Liệu Học Năm 5/Công nghệ UD Doanh Nghiệp/Code'
cd extension
npm ci
npm run check
```

Ở chrome://extensions, Reload extension đang load từ extension/dist, rồi đóng/mở lại panel và đăng nhập. Bản extension mới là 0.3.0, có phần **Tiến trình xử lý cuộc họp** và ô **Chọn AI** với OpenAI/Gemini. Nếu policy API trả 404 hoặc thông báo backend chưa hỗ trợ chọn AI, xác nhận backend mới đã khởi động; build extension không tự restart backend.

## 2. Test trên Side Panel

1. Mở meeting đã lưu; xác nhận preview/segments đúng.
   Nếu cần meeting mới: Nhập mới → dán mẫu bên dưới → múi giờ Asia/Ho_Chi_Minh → Lưu & xem preview. Ngày họp cần chọn theo nội dung nếu dùng các cụm thời gian tương đối.
2. Ở **Chọn AI**, chọn **OpenAI** hoặc **Gemini**. Cả hai hiện ghi chưa kết nối; đọc thông báo rồi bấm **Tạo job chuẩn bị**. Trong phiên backend đã tạm tắt key theo mục 1, nút này chưa gửi dữ liệu tới provider.
3. Job được enqueue bằng HTTP 202; panel đọc trạng thái từ backend. Chờ/processing có thể rất ngắn với transcript nhỏ nên UI có thể thấy ngay FAILED.
4. Kết quả mong đợi: mã **PROVIDER_NOT_CONFIGURED**, số segments đã chuẩn bị bằng số segments của meeting, completedChunks vẫn 0. Không có danh sách công việc ở chặng này.
5. Đóng/mở panel hoặc đăng xuất/đăng nhập lại cùng account: meeting và job cuối được lấy lại từ backend, không tạo job mới.
6. Thay input khi job đã kết thúc: revision mới có analysisStatus NOT_STARTED và currentAnalysisJobId null. Job cũ vẫn là lịch sử riêng, không trở thành kết quả của revision mới.

Kiểm tra lựa chọn: chọn OpenAI và tạo job → dòng **AI của job: OpenAI**. Khi job kết thúc, chọn Gemini và tạo job mới → Job ID khác, **AI của job: Gemini**; đọc job cũ qua API vẫn là openai. Đóng/mở panel khôi phục provider từ job hiện hành. Khi job active hoặc POST đang gửi/chưa biết kết quả, ô chọn bị khóa. Retry giữ provider cũ; để đổi provider phải tạo job mới sau khi job hiện tại kết thúc/hủy. Không tự fallback sang AI khác.

GET analysis-policies trả đúng hai lựa chọn, có providerId/displayName/processingPolicyId/providerReady/description. IDs gửi API là openai hoặc gemini (chữ thường), policy foundation-v1. Backend vẫn nhận unconfigured từ client cũ để tương thích các job trước 0.2.1 nhưng không hiển thị lựa chọn thứ ba. Nếu response tạo job mất, giữ nguyên provider và Idempotency-Key cho lần gửi lại; đổi provider với cùng key bị IDEMPOTENCY_CONFLICT.

Nút Thử lại chỉ hiện khi error.retryable=true. PROVIDER_NOT_CONFIGURED không retryable: lặp cùng snapshot chưa có provider sẽ không giải quyết được lỗi. Adapters/schema/validation đã có; cấu hình key và tạo job mới để dùng theo LLM Setup.

Transcript giả để thử:

```text
Nam: Mai hoàn thành màn hình đăng nhập trước 17:00 ngày 12/10/2026.
Nam: Huy kiểm thử API trước 10:00 ngày 13/10/2026.
```

Với keys tạm tắt, job chỉ chuẩn bị hai segments. Để test khóa/hủy, làm mục 3; để trích xuất task bằng AI thật, restart với keys .env theo LLM Setup.

## 3. Test queued/cancel và restart dễ quan sát

Worker mặc định bật. Để job ở trạng thái QUEUED đủ lâu cho việc kiểm tra, dừng backend và khởi động lại từ terminal backend đã nạp `.env`:

```sh
OPENAI_API_KEY='' GEMINI_API_KEY='' ANALYSIS_WORKER_ENABLED=false ./mvnw spring-boot:run
```

1. Tạo job trong panel → QUEUED.
2. Nút Thay input ở panel đang theo dõi phải bị khóa. Nếu panel khác chưa tải trạng thái, gửi PATCH vẫn bị backend từ chối 409 RESOURCE_BUSY.
3. Đóng/mở panel → cùng Job ID, vẫn QUEUED.
4. Bấm Hủy job → CANCELLED. Thay input được mở lại. Cancel lặp lại trả trạng thái hiện tại, không tạo job.
5. Tạo một job mới, giữ QUEUED; dừng backend rồi chạy lại:

```sh
OPENAI_API_KEY='' GEMINI_API_KEY='' ANALYSIS_WORKER_ENABLED=true ./mvnw spring-boot:run
```

Job còn trong DB được worker xử lý và dừng với PROVIDER_NOT_CONFIGURED. Giữ nguyên DB và JWT key qua restart; nếu session đã hết 15 phút thì đăng nhập lại. Không dùng script demo có DB tạm nếu cần giữ dữ liệu sau khi Ctrl+C script.

Cancel PROCESSING trả CANCEL_REQUESTED, worker xác nhận CANCELLED ở lần kiểm tra tiếp theo. Worker chết sau yêu cầu hủy được xử lý khi lease hết hạn. Hủy là best effort; khi có provider thật, request đã dispatch có thể vẫn được tính phí.

## 4. API và Postman/cURL

Tất cả endpoint sau dùng Bearer JWT và kiểm tra owner qua meeting:

| Method | Path | Response |
| --- | --- | --- |
| GET | /api/v1/analysis-policies | 200 policies server cho phép; không chứa API key |
| POST | /api/v1/meetings/{id}/analysis-jobs | 202 enqueue theo expectedInputVersion |
| GET | /api/v1/jobs/{jobId} | 200 progress/snapshot/error/resource links |
| POST | /api/v1/jobs/{jobId}/cancel | 202 trạng thái hiện tại/yêu cầu hủy |
| POST | /api/v1/jobs/{jobId}/retry | 202 cùng Job ID/checkpoint nếu retryable |

GET meeting bổ sung currentAnalysisJobId nullable và analysisStatus thực từ job hiện hành. Enqueue/pointer không tăng inputVersion. Chỉ PATCH input/revision mới tăng version.

Request tạo job (version phải lấy từ GET meeting mới nhất):

```json
{
  "expectedInputVersion": 7,
  "providerId": "openai",
  "processingPolicyId": "foundation-v1"
}
```

Trong Postman: tạo biến jobId, analysisKey; analysisKey là UUID mới cho một lần phân tích chủ động. Header Idempotency-Key dùng lại khi gửi lại **đúng request đó** sau response bị mất. Không dùng cùng key cho input/policy khác. Backend tự sinh key nếu client bỏ header, nhưng khi đó retry POST không có bảo đảm idempotency giữa hai requests.

Với các biến BASE_URL, TOKEN và MEETING_ID đã có từ API Test Guide:

```sh
curl -i "$BASE_URL/api/v1/analysis-policies" \
  -H "Authorization: Bearer $TOKEN"
```

```sh
ANALYSIS_KEY=$(uuidgen)
curl -i -X POST "$BASE_URL/api/v1/meetings/$MEETING_ID/analysis-jobs" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $ANALYSIS_KEY" \
  -d '{"expectedInputVersion":7,"providerId":"openai","processingPolicyId":"foundation-v1"}'
```

Thay số 7 bằng version thật trước khi gửi. Copy jobId từ response vào biến JOB_ID để đọc/hủy/retry:

```sh
curl -i "$BASE_URL/api/v1/jobs/$JOB_ID" \
  -H "Authorization: Bearer $TOKEN"
```

```sh
curl -i -X POST "$BASE_URL/api/v1/jobs/$JOB_ID/cancel" \
  -H "Authorization: Bearer $TOKEN"
```

```sh
curl -i -X POST "$BASE_URL/api/v1/jobs/$JOB_ID/retry" \
  -H "Authorization: Bearer $TOKEN"
```

Cancel/retry không nhận input/metadata mới; body trống hoặc {}. Retry giữ revision/metadata/provider/policy/model/prompt/schema/budget của job, không đọc lại metadata mới và không reset checkpoint. Job thuộc input cũ không retry được.

## 5. Trạng thái, ràng buộc và lỗi

| Case | Expected |
| --- | --- |
| POST cùng key và cùng version/provider/policy | Cùng Job ID, kể cả job đã terminal; không enqueue lại |
| Cùng key nhưng expectedInputVersion khác | 409 IDEMPOTENCY_CONFLICT |
| Key khác khi meeting có job active | 409 RESOURCE_BUSY |
| Version tạo job cũ | 409 STALE_VERSION |
| PATCH input khi QUEUED/PROCESSING/CANCEL_REQUESTED | 409 RESOURCE_BUSY |
| User khác GET/cancel/retry/start tài nguyên của owner | 404 RESOURCE_NOT_FOUND |
| Hơn 2 job active cùng user | 429 USER_JOB_LIMIT, Retry-After |
| Lease quá số lần claim cho phép | FAILED LEASE_RECOVERY_EXHAUSTED, retryable=true |
| Retry lỗi không retryable | 409 JOB_NOT_RETRYABLE |
| Retry vượt 3 lần thủ công | 409 RETRY_LIMIT |
| Nguồn đã bị scrub | SOURCE_UNAVAILABLE; không gửi input mất nguồn tới pipeline |
| Input vượt budget chuẩn bị | FAILED TRANSCRIPT_OVER_BUDGET; không cắt transcript âm thầm |
| Job foundation hoặc provider chưa cấu hình | FAILED PROVIDER_NOT_CONFIGURED, retryable=false |

Worker claim bằng SELECT FOR UPDATE SKIP LOCKED trong transaction ngắn; lease 30 giây, tối đa 3 claims trong một vòng recovery. Heartbeat/checkpoint/fail kiểm tra UUID lease owner, attempt và thời điểm hết lease bằng đồng hồ DB. Worker cũ không thể ghi trạng thái sau khi job đã được worker mới claim hoặc bị hủy. Một worker scheduled hiện xử lý tuần tự; nhiều process có thể claim các job khác nhau.

Checkpoint chuẩn bị lưu lastSequence/preparedSegments/estimatedInputTokens, không lưu bản sao transcript. Đây **chưa phải** chunk-result checkpoint của LLM. Source được đọc từ revision đã pin theo batch 100 segments; model/prompt/schema/budget đã pin trong job. Input và metadata không được chỉnh khi active.

Estimator development dùng upper bound theo UTF-8 bytes/JSON escaping cùng metadata và reserve, không dùng số ký tự thay cho tokenizer. Context 131072 và reserve 8192 là guard của policy foundation, **không phải context window đã xác minh của model thật**. Policy llm-v1 kiểm tra toàn serialized source/prompt/schema/output reserve/safety trước dispatch theo byte upper bound; xem các giới hạn và phần benchmark còn lại trong LLM Setup. Chunking vẫn chưa bật; totalChunks chỉ được đặt khi nguồn chuẩn bị xong, completedChunks không tăng cho bước chuẩn bị và không hiển thị phần trăm giả.

Polling UI chỉ tự retry GET, 2 giây khi active; lỗi đọc dùng backoff có giới hạn rồi tạm dừng. Không tự retry POST tạo/hủy/retry job. Nếu mất response tạo job, key của lần tạo được giữ trong RAM cho lần bấm lại; đóng/mở panel thì GET meeting trả currentAnalysisJobId để khôi phục. Input chưa lưu không được persist.

## 6. Kiểm thử tự động và kết quả thật

Chạy trên DB **test riêng**, không trỏ verify vào DB có dữ liệu cần giữ:

```sh
cd backend
./mvnw verify
```

Nếu không dùng được embedded PostgreSQL, cấu hình TEST_DATABASE_URL/TEST_DATABASE_USER/TEST_DATABASE_PASSWORD theo README. Hoặc dùng script một bước từ Code, Docker Desktop có quyền socket:

```sh
python3 backend/scripts/run_local.py
```

Script chạy full tests rồi HTTP smoke và giữ server demo. HTTP smoke mới kiểm tra enqueue idempotent, guard input, cancel, rồi **restart JVM thật** để đọc lại queue từ cùng DB/JWT key và xác nhận failure do chưa có provider. Tests không tạo tài khoản/meeting thật của người dùng.

Suite hiện có 63 unit + 26 integration tests (9 foundation, 13 jobs, 4 LLM). Ngày 09/10/2026: unit/package đạt; integration suite bị sandbox chặn socket PostgreSQL nhúng trước assertions, chưa xác minh DB V2/V3/HTTP mới. 40 tests extension/build MV3 0.3.0 đạt với fixtures và React server-render. Người dùng đã báo preparation 2/2 và PROVIDER_NOT_CONFIGURED ở bản trước; chưa có Chrome/API AI thật cho bản mới. Không ghi các case DB/restart/Chrome dưới đây thành Pass khi chưa chạy.

| Nhóm | Actual |
| --- | --- |
| Migration V2/FK/unique active và JDBC mapping | Chưa kiểm chứng PostgreSQL thật trong lượt này |
| Concurrent enqueue, claim SKIP LOCKED, lease fencing/checkpoint | Có integration tests; blocked runtime |
| Cancel/retry/owner/quota qua HTTP | Có integration tests; blocked runtime |
| JVM restart trong HTTP smoke mới | Syntax đạt; chưa chạy runtime |
| Reopen/polling/input lock trên Chrome với backend mới | Client tests đạt; chưa test Chrome thật |
| Tạo job → chuẩn bị input → hiển thị PROVIDER_NOT_CONFIGURED trên panel | Người dùng báo đúng expected ngày 09/10: 2/2 segments, phân tích 0/1, version 1, attempt 1; chưa suy ra các case khác đã đạt |

Nguồn kỹ thuật: [PostgreSQL row locking/SKIP LOCKED](https://www.postgresql.org/docs/16/sql-select.html), [Spring transaction proxies](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html), [Spring scheduling](https://docs.spring.io/spring-framework/reference/integration/scheduling.html). Đặc tả dự án: SDS §5.4–5.6, §6.4, §7.5, §14.2.
