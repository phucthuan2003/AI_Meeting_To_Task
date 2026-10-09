# Kết nối OpenAI/Gemini và test phân tích cuộc họp

Cập nhật 09/10/2026, extension **0.3.0**. Backend đã có hai adapter, kiểm tra JSON/evidence, lưu kết quả trong PostgreSQL và trả đề xuất lên panel. Cần cấu hình key và chạy thử local để xác minh API thật. Phần sửa/duyệt công việc và Trello là bước tiếp theo.


## 0. Áp dụng bản sửa Gemini sau khi request đơn giản đã trả HTTP 200

**Bằng chứng hiện có:** người dùng gọi `gemini-3.8-flash:generateContent` chỉ với `contents` thành công. Request structured trước đó trả 400 chung chung; chưa xác định trường nào gây lỗi. Bản này dùng text generation, yêu cầu JSON trong prompt và giữ `maxOutputTokens` theo budget job (mặc định 4096). Đây không phải bảo đảm schema từ provider: backend vẫn từ chối JSON sai, evidence ngoài nguồn hoặc thông tin người nhận/hạn không được nguồn hỗ trợ. Chưa xác minh lời gọi live từ bản adapter mới.

1. Trong `.env` ở Code, giữ key đang hoạt động và `GEMINI_MODEL='gemini-3.8-flash'`.
2. Dừng backend bằng Ctrl+C, rồi chạy đầy đủ các lệnh ở mục 2 để nạp `.env` và khởi động code mới. Không chỉ chạy script Python.
3. Nếu extension đã là 0.3.0 với nút **Phân tích** và vùng đề xuất, chỉ đóng/mở lại panel; lần sửa backend này không cần build lại extension. Nếu còn giao diện “chưa có adapter”, thực hiện mục 3.
4. Mở meeting đã lưu, chọn **Gemini**, bấm **Phân tích** để tạo job mới. Không dùng **Làm mới trạng thái** hoặc **Thử lại job** của job lỗi cũ: refresh chỉ đọc lại, retry giữ snapshot cũ. Prompt mới là `meeting-events-v1-gemini-text-v1`; job Gemini snapshot cũ không chạy bằng contract mới và có thể báo POLICY_VERSION_UNAVAILABLE.
5. Đợi QUEUED → PROCESSING → COMPLETED; panel tự tải và hiển thị đề xuất. Kiểm tra mẫu hai công việc ở mục 4, sau đó đóng/mở panel để kiểm tra kết quả đã lưu.

Giới hạn token vẫn được gửi tới Google, response HTTP bị giới hạn 1 MiB, timeout/lease/cancel vẫn áp dụng. Không tự đổi provider hay gửi lại request lỗi. Script `probe_gemini_request.py` mặc định nay dùng text + schema trong prompt với dữ liệu giả; `--isolate` chỉ dành cho đối chiếu structured format cũ, không phải bước bắt buộc để dùng giao diện. Các mục 6.1–6.5 bên dưới ghi lại quá trình chẩn đoán trước bản sửa này.

## 1. Thêm key ở backend

Mở file `.env` đang dùng trong **Code**, cùng cấp `compose.yaml`; giữ nguyên DATABASE_URL/USER/PASSWORD, JWT_SIGNING_KEY và EXTENSION_ORIGIN_ALLOWLIST. Thêm các dòng sau, thay giá trị minh họa bằng key thật **chỉ trên máy bạn**:

```sh
OPENAI_API_KEY='THAY_BANG_OPENAI_KEY_THAT'
OPENAI_MODEL='gpt-4.1-mini-2025-04-14'
GEMINI_API_KEY='THAY_BANG_GEMINI_KEY_THAT'
GEMINI_MODEL='gemini-3.8-flash'
```

Bạn có thể cấu hình một hoặc cả hai. Provider chưa dùng thì để key trống, ví dụ `OPENAI_API_KEY=''`; không để nguyên chữ THAY_BANG… vì backend chỉ kiểm tra key có giá trị, chưa kiểm tra key có dùng được. Mỗi biến chỉ có một dòng assignment, không có khoảng trắng quanh dấu `=`. Không thêm API key vào extension hoặc gửi qua chat.

**OpenAI:** tạo key trong [API dashboard](https://platform.openai.com/api-keys), chọn project có quyền gọi model. Cách tạo/export key xem [OpenAI Quickstart](https://developers.openai.com/api/docs/quickstart). Adapter dùng Responses API và Structured Outputs; model mặc định là snapshot được liệt kê trong [GPT-4.1 mini](https://developers.openai.com/api/docs/models/gpt-4.1-mini).

**Gemini:** mở [Google AI Studio API Keys](https://aistudio.google.com/app/apikey), chọn/import project rồi tạo key cho Gemini API. Xem [hướng dẫn key chính thức](https://ai.google.dev/gemini-api/docs/api-key) nếu gặp lỗi quyền hoặc key bị chặn. Adapter hiện dùng `generateContent` dạng text: hướng dẫn và JSON schema nằm trong prompt, backend kiểm tra JSON/evidence trước khi lưu. Request chỉ có `contents` và `generationConfig.maxOutputTokens` theo [API reference](https://ai.google.dev/api/generate-content); không gửi responseFormat/systemInstruction/thinkingConfig. Default code vẫn là `gemini-2.5-flash`, nhưng `.env` ở trên chọn `gemini-3.8-flash` mà người dùng đã gọi thành công. Quyền/quota phụ thuộc project.

Giữ `gemini-3.8-flash` nếu request đơn giản của bạn đã trả HTTP 200. Backend lưu model/prompt/schema theo từng job; đổi biến môi trường chỉ có tác dụng với job mới sau khi restart. Gemini model alias vẫn có thể được nhà cung cấp cập nhật; snapshot lưu ID không đảm bảo cố định weights phía provider.

## 2. Khởi động lại backend

Mở Docker Desktop. Trong terminal đang chạy backend cũ, bấm **Ctrl+C** trước. Sau đó chạy lần lượt, mỗi dòng là một lệnh:

```sh
cd '/Users/phucthuan/phucthuan/Tài Liệu Học Năm 5/Công nghệ UD Doanh Nghiệp/Code'
docker compose up -d postgres
docker compose ps
set -a
source ./.env
set +a
cd backend
ANALYSIS_WORKER_ENABLED=true ./mvnw spring-boot:run
```

`postgres` cần healthy. Flyway áp dụng **V3__analysis_results.sql** sau V1/V2 để lưu kết quả; không xóa database/volume. Đợi log backend khởi động thành công rồi giữ terminal mở. Sửa `.env` khi JVM đang chạy không cập nhật cấu hình JVM; cần nạp lại và restart như trên.

Trong terminal thứ hai kiểm tra:

```sh
curl -sS http://127.0.0.1:8080/actuator/health
```

Cần thấy `{"status":"UP"}`. Nếu lỗi Compose không tìm thấy config, quay về đúng Code. Nếu `.env` không tìm thấy, đang đứng trong backend/extension. Nếu lỗi SCRAM password, đọc phần [khởi động extension](Extension_Setup.md); không tạo lại password khi DB đã dùng password khác.

## 3. Build và Reload extension

Trong terminal thứ hai:

```sh
cd '/Users/phucthuan/phucthuan/Tài Liệu Học Năm 5/Công nghệ UD Doanh Nghiệp/Code/extension'
npm ci
npm run check
```

Mở `chrome://extensions`, Reload extension đang load từ `extension/dist`, rồi đóng/mở panel và đăng nhập. Manifest cần phiên bản **0.3.0**. Nếu extension ID thay đổi, cập nhật exact origin ở `.env` và restart backend. Nếu dùng port/origin khác 127.0.0.1:8080, build lại với `API_ORIGIN` theo Extension_Setup.

Mở meeting → **Chọn AI**:

- Provider có key: không còn hậu tố “chưa kết nối”; nút là **Phân tích**. Thông báo cho biết transcript/metadata sẽ gửi đến provider bạn chọn.
- Provider không có key: vẫn “chưa kết nối”; nút **Tạo job chuẩn bị**, chưa gửi dữ liệu ra provider và kết thúc PROVIDER_NOT_CONFIGURED.

`providerReady=true` chỉ nghĩa là backend đã nạp key, chưa chứng minh key/quota/model/network hoạt động. Nếu cả hai vẫn chưa kết nối, kiểm tra đã source đúng `.env`, restart JVM và tải lại panel; không cần in key để chẩn đoán.

## 4. Test phân tích thật

Tạo meeting mới với ngày họp **09/10/2026**, múi giờ **Asia/Ho_Chi_Minh** và nội dung giả:

```text
Nam: Mai hoàn thành màn hình đăng nhập trước 17:00 ngày 12/10/2026.
Nam: Huy kiểm thử API trước 10:00 ngày 13/10/2026.
```

1. Bấm **Lưu & xem preview**; kiểm tra 2 segments.
2. Chọn provider đã cấu hình → đọc thông báo gửi dữ liệu → bấm **Phân tích**.
3. Panel theo dõi QUEUED → PROCESSING → COMPLETED nếu provider trả dữ liệu hợp lệ. Transcript ngắn có thể chuyển trạng thái rất nhanh. Khi gọi AI, stage là “Đang gọi AI và kiểm tra kết quả”.
4. Với mẫu này, kiểm tra kỳ vọng **hai đề xuất**: màn hình đăng nhập/Mai/hạn 17:00 ngày 12/10 và kiểm thử API/Huy/hạn 10:00 ngày 13/10. Đây là kỳ vọng kiểm thử, không phải kết quả AI đã đo.
5. Mở **Bằng chứng từ transcript**, đối chiếu đúng segment/người/hạn. Các quote do server đọc từ segment thật; JSON đúng vẫn có thể sai ngữ nghĩa nên phải kiểm tra nội dung.
6. Đóng/mở panel hoặc đăng nhập lại cùng account: kết quả đã lưu được đọc lại, không gọi AI lần nữa. Nếu gặp lỗi GET kết quả, bấm **Tải lại kết quả** chỉ đọc DB.
7. Chọn provider còn lại rồi bấm Phân tích để thử adapter thứ hai; thao tác này tạo job mới và gửi một request AI khác. Kết quả hiện hành là job mới; job trước giữ snapshot.

**Job cũ `foundation-v1` vẫn báo PROVIDER_NOT_CONFIGURED dù vừa thêm key.** Tạo lượt **Phân tích mới** để dùng `llm-v1`; retry giữ snapshot cũ nên không nâng policy. Không cần xóa meeting đang lưu.

## 5. Các trường hợp cần kiểm tra thêm

| Nội dung/thao tác | Kỳ vọng cần đối chiếu |
| --- | --- |
| `Nam: Hôm nay chỉ trao đổi thông tin, chưa giao công việc nào.` | Có thể COMPLETED với 0 đề xuất; không tạo task mẫu |
| `Nam: Cần cập nhật tài liệu, chưa chốt người làm hoặc hạn.` | Nếu trích xuất được task, người/hạn thiếu là null và có cảnh báo |
| `Nam: Mai viết tài liệu trước 17:00 ngày 12/10/2026.` rồi `Nam: Việc viết tài liệu chuyển cho Huy, hạn đổi thành 10:00 ngày 13/10/2026.` | Một task sau cập nhật: Huy/hạn mới, evidence giữ nguồn cập nhật |
| Giao task rồi thêm `Nam: Hủy việc viết tài liệu vừa giao.` | CREATE/CANCEL được giữ trong events, task đã hủy không ở danh sách đề xuất |
| Hạn `thứ Sáu`/`ngày mai`/chỉ có ngày | Giữ deadlineRaw, cảnh báo; không tự chốt UTC/giờ mặc định |
| Bấm Hủy khi đang gọi AI | CANCEL_REQUESTED → CANCELLED nếu hủy trước commit; kết quả trả muộn bị bỏ. Nếu đã COMPLETED trước yêu cầu, giữ kết quả đã commit |
| Account B đọc meeting/task của A | 404, không trả kết quả A |
| Thay input sau phân tích | Revision mới không hiển thị kết quả cũ; phân tích lại mới có đề xuất hiện hành |

Task hiện là đề xuất **PENDING_REVIEW**, chưa resolve thành viên Trello. `dueLocal`/`dueAt` chỉ là đề xuất khi deadlineRaw có giờ `HH:mm` trước ngày `DD/MM/YYYY` và timezone hợp lệ với một UTC offset. Thời gian tương đối, DST mơ hồ, định dạng khác giữ raw/cảnh báo. Chưa có nút sửa, duyệt hay gửi card.

## 6. Nếu job lỗi

| Mã lỗi | Cách xử lý |
| --- | --- |
| PROVIDER_NOT_CONFIGURED | Kiểm tra key đã nạp và job mới có policy llm-v1; job nền cũ giữ nguyên |
| PROVIDER_AUTH_ERROR | Kiểm tra key/project/quyền model; cập nhật key, restart rồi tạo job mới |
| PROVIDER_RATE_LIMIT | Kiểm tra quota/rate limit trên dashboard; chờ rồi bấm Thử lại job nếu được cho phép |
| PROVIDER_TIMEOUT / PROVIDER_CONNECTION_ERROR / PROVIDER_UNAVAILABLE | Kiểm tra mạng/provider; retry có giới hạn, không tự chuyển sang provider khác |
| PROVIDER_OUTPUT_INCOMPLETE | Provider hết output budget/chưa trả đầy đủ; thử transcript ngắn hơn |
| PROVIDER_RESPONSE_INVALID | JSON/schema/evidence không hợp lệ; không publish task, giữ transcript để kiểm tra/rephân tích |
| PROVIDER_REQUEST_REJECTED / PROVIDER_REFUSED | Kiểm tra model/schema hoặc nội dung; lỗi được trả gọn, không trả key/raw vendor body |
| TRANSCRIPT_OVER_BUDGET / RESULT_OVER_BUDGET | Rút ngắn input; chunking chưa triển khai |
| POLICY_VERSION_UNAVAILABLE | Job dùng prompt/schema cũ không còn hỗ trợ; tạo job mới từ input hiện hành |

Không có auto retry HTTP hay auto fallback. Hủy là best effort; request đã gửi có thể vẫn tính phí. Mất response/worker chết sau dispatch có thể khiến retry/recovery gọi AI lại; lease chỉ chặn công bố kết quả của worker cũ, không đảm bảo exactly-once ở provider.

Giới hạn khởi điểm: serialized source tối đa 49152 byte, output 4096 tokens, timeout 60 giây, response/result 1 MiB, tối đa 100 events. Ngân sách toàn payload dùng UTF-8 byte upper bound + schema/prompt/reserve/safety; chưa tokenizer/chunking/benchmark. Token usage do provider báo khi kết quả thành công được lưu nullable, không suy ra token thật từ preparedSegments. Latency là thời gian request adapter, không phải toàn job. Chưa lưu usage của các lần lỗi.

## 6.1. Gemini 3.8 và PROVIDER_REQUEST_REJECTED — lịch sử chẩn đoán

Model ID đầy đủ là `gemini-3.8-flash`; đây là model được Google công bố và hỗ trợ structured outputs: [model page](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash). Ngày 09/10 người dùng đã cấu hình đúng ID này trong .env. Chưa biết model được JVM/job cũ nạp hay HTTP status thực của lỗi người dùng gửi, nên chưa kết luận lỗi do model/key/schema cụ thể.

Ở bản cũ, backend đã cập nhật `generationConfig.responseFormat.text` với `mimeType=APPLICATION_JSON` và `schema` theo [API reference](https://ai.google.dev/api/generate-content#TextResponseFormat). Các trường cũ responseMimeType/responseJsonSchema thuộc cấu hình deprecated. Không gửi thinkingBudget của 2.5 cho model 3.8. Thay đổi này sửa payload theo API hiện tại; chưa có lời gọi live để khẳng định đây là nguyên nhân duy nhất của lỗi trước.

Dừng JVM cũ, source .env và restart theo mục 2, sau đó chọn Gemini và tạo lượt Phân tích mới. Model của job là snapshot; sửa file .env không thay model job đã tạo. Lỗi PROVIDER_REQUEST_REJECTED cũ vẫn giữ trong lịch sử.

Nếu tiếp tục lỗi, terminal backend có dòng dạng sau (minh họa, chưa đo request của bạn):

```text
LLM request rejected jobId=... provider=gemini model=gemini-3.8-flash httpStatus=400 vendorStatus=INVALID_ARGUMENT
```

HTTP 400 cần kiểm tra payload/tham số/precondition; HTTP 404 cần kiểm tra model được pin và quyền/khả năng truy cập model trên endpoint. Backend không trả raw vendor message hoặc log key/transcript. vendorStatus chỉ lấy từ enum allowlist; UNSPECIFIED nghĩa là chưa có mã trạng thái an toàn để hiển thị. Dòng log cũ chưa có thông tin này; cần request mới sau restart. Không có log trace chi tiết phía vendor ở bản trước dù error message cũ hướng dẫn đọc log.

Có thể kiểm tra metadata model từ terminal ở **Code** mà không gửi transcript hoặc gọi generateContent:

```sh
set -a
source ./.env
set +a
python3 backend/scripts/check_gemini_model.py
```

Lệnh GET [models.get](https://ai.google.dev/api/models#method:-models.get) dùng key ở header, không in key/raw response, không theo redirect. Nếu HTTP 200 và generateContent=true thì project/key đọc được metadata; chưa chứng minh schema hoặc phân tích sẽ thành công. Khi lỗi chỉ gửi model/httpStatus/vendorStatus hoặc output gọn của script, không gửi .env/JWT/key.

## 6.2. Khi log có HTTP 400 / INVALID_ARGUMENT — lịch sử chẩn đoán

Log người dùng cung cấp ngày 09/10, 21:58 xác nhận `model=gemini-3.8-flash httpStatus=400 vendorStatus=INVALID_ARGUMENT`. Job/JVM này đã nạp đúng model; log hiện chưa có thông tin trường hoặc nguyên nhân Google từ chối. Không kết luận chỉ từ HTTP 400 rằng key/quota hợp lệ hoặc lỗi chắc chắn ở model/schema.

Schema gửi riêng Gemini nay chuyển priority nullable enum thành `anyOf` gồm string enum LOW/MEDIUM/HIGH và nhánh null, bỏ maxLength khỏi payload provider do keyword này không liệt kê trong [subset schema Gemini](https://ai.google.dev/gemini-api/docs/generate-content/structured-output). Backend vẫn kiểm tra giới hạn/string/enum/evidence như trước; schema canonical và OpenAI không đổi. Đây là sửa tương thích, chưa chứng minh nguyên nhân của request lỗi trước.

Log backend mới thêm `reason` và `fieldHints`: chỉ labels cố định lấy từ ErrorInfo/BadRequest hoặc nhận dạng thông điệp, không in vendor message. fieldHints là gợi ý nhận dạng tên trường trong lỗi, không đảm bảo tất cả tên liệt kê đều bị sai. Ví dụ API_KEY_INVALID cần xử lý key/project; INVALID_FIELD_VALUE với mimeType cần kiểm tra định dạng; SCHEMA_ENUM_VALUE chỉ ra enum; UNSPECIFIED nghĩa là chưa xác định được từ diagnostics hiện có.

Để thử payload schema mới mà không cần khởi động lại backend trước, ở Code chạy:

```sh
set -a
source ./.env
set +a
python3 backend/scripts/probe_gemini_request.py
```

Script gửi **một POST generateContent**, chỉ dùng transcript giả Mai/viết tài liệu; không đọc meeting/database hoặc tự retry. Có thể tính quota/phí theo tài khoản. Config/schema theo adapter mới, prompt rút gọn để chẩn đoán tham số; không thay thế extraction end-to-end. Output chỉ model, HTTP status, vendorStatus/reason/fieldHints hoặc finishReason; không in key/transcript/vendor body.

Nếu HTTP 200 thì provider nhận payload probe: restart backend với .env theo mục 2 rồi tạo lượt Phân tích mới. STOP chưa chứng minh kết quả semantically đúng hoặc đã lưu DB. Nếu vẫn HTTP 400, gửi output gọn của script hoặc dòng log backend mới có reason/fieldHints để sửa theo bằng chứng. Không cần thay model hoặc tạo lại key khi chưa xác định nguyên nhân.

## 6.3. Script báo NETWORK_OR_RESPONSE_ERROR — lịch sử chẩn đoán

Thông báo cũ gộp TLS, DNS, timeout và JSON parse nên chưa xác định được nguyên nhân. Ngày 09/10 agent kiểm tra Python 3.11.9 ở `/Library/Frameworks/Python.framework/Versions/3.11/bin/python3`: default cafile/capath không tồn tại, SSL context nạp 0 CA; certifi đã có sẵn. Đây là bằng chứng cấu hình CA của Python có vấn đề, chưa phải response live xác nhận exception cụ thể. Backend Java từng nhận HTTP 400 nên cần phân biệt lỗi TLS của script với request rejection của Java.

Hai script nay dùng gemini_http.py: tạo SSL context xác minh đầy đủ; nếu không có CA mặc định/directory và không có SSL_CERT_FILE/SSL_CERT_DIR do người dùng cấu hình, nạp certifi sẵn có. Kiểm tra offline trên máy đã nạp 143 CA, check_hostname=true và CERT_REQUIRED. Không thay trust store toàn máy. Nếu Python khác chưa có certifi hoặc trust store phù hợp, xem [cài Python trên macOS](https://docs.python.org/3/using/mac.html) và [SSL context](https://docs.python.org/3/library/ssl.html#ssl.create_default_context).

Ở terminal Code đã nạp .env, kiểm tra metadata trước:

```sh
python3 backend/scripts/check_gemini_model.py
```

Output mới có TLS_CA_SOURCE/loaded_CA_count, rồi HTTP status hoặc lỗi cụ thể: TLS_CERTIFICATE_VERIFY_FAILED, TLS_HANDSHAKE_FAILED, DNS_RESOLUTION_FAILED, REQUEST_TIMEOUT, LOCAL_NETWORK_PERMISSION_DENIED, CONNECTION_REFUSED/INTERRUPTED, HTTP_RESPONSE_READ_FAILED, INVALID_JSON_RESPONSE hoặc UNEXPECTED_RESPONSE_STRUCTURE. Tên lỗi là labels cố định; không echo raw exception/key.

Nếu metadata HTTP 200 và generateContent=true, chạy probe ở mục 6.2 để kiểm tra payload. Nếu vẫn lỗi kết nối, giữ output mới để xử lý đúng lớp lỗi trước. GET metadata không gọi generateContent. Chưa có bằng chứng live sau bản sửa CA; không kết luận lỗi 400 của backend đã hết.

Tests script chạy bằng `python3 -m unittest discover -s backend/scripts/tests -v`; 8 tests đạt với transport giả, gồm CA fallback/explicit trust store, TLS/DNS/timeout, redirect, JSON và HTTP 400.

## 6.4. Metadata thành công nhưng probe vẫn 400, reason=UNSPECIFIED — lịch sử chẩn đoán

Người dùng đã xác minh HTTPS với CERTIFI_FALLBACK/143 CA, GET metadata HTTP 200 và generateContent=true. POST probe vẫn HTTP 400 INVALID_ARGUMENT, reason=UNSPECIFIED, fieldHints=[]. Điều này xác nhận lỗi TLS của script đã được khắc phục trên lượt chạy đó; chưa xác định nguyên nhân request bị từ chối. GET thành công không thay thế việc kiểm tra POST/schema/quota.

Bộ lọc labels trước đây bỏ mất thông điệp Google chưa nhận dạng được. Script đã thêm chế độ details, chỉ dành cho request dữ liệu giả của probe:

```sh
python3 backend/scripts/probe_gemini_request.py --details
```

Đây vẫn là một POST và không tự retry. Khi lỗi, thêm dòng vendorMessage chứa error.message và tối đa hai mô tả field violation, mỗi dòng tối đa 1500 ký tự. Script che key hiện hành (cả URL encoded), dạng Google key/Bearer/header credential và bỏ ký tự điều khiển. Chế độ mặc định và log backend vẫn dùng labels; không áp dụng free-form vendor message cho meeting thật. Gửi dòng vendorMessage để sửa theo nguyên nhân cụ thể; không tiếp tục đổi model/schema chỉ từ UNSPECIFIED.

11 tests Python đạt, gồm redaction/giới hạn text và giữ details tắt mặc định. Chưa có kết quả live với --details; chưa chốt root cause lỗi 400.

## 6.5. Google chỉ trả “Request contains an invalid argument.” — lịch sử chẩn đoán

Người dùng đã chạy --details và Google vẫn trả thông điệp chung, không có tên trường. Chưa đủ bằng chứng kết luận lỗi ở key/model/schema. Để đối chiếu trong một lần chạy:

```sh
python3 backend/scripts/probe_gemini_request.py --isolate --details
```

Lệnh gửi tối đa **6 POST với dữ liệu giả**, có thể tính quota/phí. In CASE/HTTP cho từng bước, không tự retry và không đổi backend/.env:

1. TEXT_BASELINE: input đơn giản, maxOutputTokens=512, chưa có JSON schema. Nếu không HTTP 200 thì dừng; cần xem lại request cơ bản/model/quyền/cấu hình.
2. TINY_SCHEMA_RESPONSE_FORMAT_ENUM: schema nhỏ với responseFormat và APPLICATION_JSON.
3. TINY_SCHEMA_RESPONSE_FORMAT_MIME_LITERAL: cùng schema/input/budget nhưng mimeType=application/json theo [mẫu REST Google](https://ai.google.dev/gemini-api/docs/generate-content/structured-output).
4. TINY_SCHEMA_LEGACY_JSON_SCHEMA: cùng schema nhỏ, dùng responseMimeType/responseJsonSchema.
5. FULL_SCHEMA: dùng format đã được nhận (ưu tiên dạng MIME literal nếu đạt), thay schema nhỏ bằng schema meeting; các phần khác giữ theo baseline.
6. FULL_SYNTHETIC_PROBE: schema/format đã được nhận với system/input/config của probe đầy đủ.

Dừng khi gặp lỗi mạng, auth/quota/service trong lúc so format; chỉ HTTP 400 mới tiếp tục phép so sánh còn lại. Mọi case tối đa một lần. HTTP 200 kể cả MAX_TOKENS chỉ chứng minh request được nhận, không chứng minh extraction hợp lệ. FULL_SCHEMA_REJECTED là bước thất bại, không tự suy mọi lỗi tại đó đều do schema; đối chiếu HTTP/vendorMessage đi kèm. Script không fallback provider trong production.

Gửi các dòng CASE/HTTP/ACCEPTED_FORMATS/SELECTED_PROBE_FORMAT/ISOLATION và vendorMessage. Kết quả sẽ chỉ ra dạng payload nào endpoint thực tế nhận; chưa đổi adapter theo một kết quả giả định. 15 Python tests đạt với mocks, bao phủ giới hạn số request/early stop/so format và giữ nguyên payload nguồn. Chưa có kết quả live --isolate.

## 7. Tests và đánh giá tiến độ

Agent đã chạy **66 backend unit tests** và **40 extension tests/build MV3** đạt. Transport/provider dùng fixtures, không phát sinh gọi AI thật. Đã viết **26 integration cases** (9 foundation, 13 job, 4 LLM) dùng PostgreSQL thật; suite bị sandbox chặn mở socket nên chưa chạy được assertions V2/V3. API/key/model/quota thật, Chrome interaction, correction quality và baseline dev/held-out 30–50 mẫu vẫn cần kiểm chứng; chưa tuyên bố hoàn tất SDS 14.3.

Trên terminal local của bạn, từ backend có thể chạy:

```sh
LC_ALL=C LANG=C ./mvnw verify
```

Integration tests ép tắt worker tự chạy và dùng transport giả với key giả; không gửi transcript tới provider. Embedded PostgreSQL cần quyền mở socket. Nếu dùng TEST_DATABASE_URL theo [Job Test Guide](Analysis_Job_Test_Guide.md), dùng DB test riêng, không trỏ tới ai_mtt đang lưu meeting của bạn.

Ghi Actual/Pass/Fail cho từng case mục 4–5 kèm provider/model/job ID/error code, không kèm key/JWT. Context mới tại [Implementation Progress](Implementation_Progress.md) và [SDS Context](SDS_v3_Context.md). Sau khi test kết nối thật, bước kế tiếp là baseline chất lượng rồi giao diện sửa/duyệt đề xuất theo SDS 14.4.
