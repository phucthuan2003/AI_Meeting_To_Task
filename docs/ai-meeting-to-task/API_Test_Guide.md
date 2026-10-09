# Hướng dẫn kiểm thử API AI Meeting to Task

Cập nhật 09/10/2026. Tài liệu mô tả backend foundation ở chặng 14.1, đối chiếu với controller, service, parser và cấu hình hiện tại. Dùng để test bằng Postman hoặc cURL: đăng ký → đăng nhập → nhập transcript → đọc preview/source → thay input → kiểm tra quyền → logout. DB analysis jobs có [guide riêng](Analysis_Job_Test_Guide.md); cấu hình/test hai adapter và đề xuất task ở [LLM Setup](LLM_Setup.md).

Các status/response bên dưới là **kết quả mong đợi**. Ví dụ response là dữ liệu minh họa, không phải bản ghi thực tế. Chạy xong mới ghi Pass/Fail. Đã code analysis job/policy, hai adapters và GET đề xuất task/evidence. Chưa có sửa/duyệt task, Trello, xóa meeting hoặc purge.

## 1 Chuẩn bị backend và dữ liệu

Từ thư mục `Code`, mở Docker Desktop và chạy:

```sh
python3 backend/scripts/run_local.py
```

Đợi dòng `Backend đang chạy: http://127.0.0.1:<port>/actuator/health`. Giữ terminal này mở và dùng terminal khác để chạy cURL. Cổng ưu tiên là 8080; nếu bị chiếm, script chọn cổng khác. `Ctrl+C` dừng phiên demo và DB tạm, dữ liệu demo không giữ sang lần chạy tiếp theo. Script chạy tests/HTTP smoke trước khi mở phiên API để bạn thử thủ công; các account của tests không thay thế account A/B ở hướng dẫn này.

Nếu cần dữ liệu giữ qua restart, dùng cách chạy với Compose và `.env` trong [README](../../README.md#chạy-backend-local).

Thiết lập biến trong terminal test; thay cổng bằng cổng vừa được in:

```sh
BASE_URL='http://127.0.0.1:8080'
API="$BASE_URL/api/v1"
TOKEN=''
MEETING_ID=''
```

Chuẩn bị hai email **chưa đăng ký** trong DB đang chạy, ví dụ `api-a-20261005@example.test` và `api-b-20261005@example.test`; password giả cho demo: `Test-api-2026!`. Nếu email đã tồn tại, đổi hậu tố. JWT hết hạn sau 15 phút: login lại và cập nhật token khi cần.

Transcript mẫu (ba dòng, một dòng có hai dấu cách để test normalization):

```text
Nam:  Mai sửa login nhé.
Mai: Chưa chốt hạn.
Long: Không đổi API.
```

## 2 Danh sách endpoint hiện có

| Method | Path | Auth | Thành công |
| --- | --- | --- | --- |
| GET | `/actuator/health` | Không | 200 khi app/DB sẵn sàng |
| POST | `/api/v1/auth/register` | Không | 201 |
| POST | `/api/v1/auth/login` | Không | 200 |
| GET | `/api/v1/auth/me` | Bearer JWT | 200 |
| POST | `/api/v1/auth/logout` | Bearer JWT | 204, body rỗng |
| POST | `/api/v1/meetings` | Bearer JWT | 201, JSON paste hoặc multipart file |
| GET | `/api/v1/meetings` | Bearer JWT | 200 |
| GET | `/api/v1/meetings/{id}` | Bearer JWT | 200 |
| GET | `/api/v1/meetings/{id}/transcript` | Bearer JWT | 200 |
| PATCH | `/api/v1/meetings/{id}/input` | Bearer JWT | 200, JSON replacement |
| GET | `/api/v1/analysis-policies` | Bearer JWT | 200, policy do server kiểm soát |
| POST | `/api/v1/meetings/{id}/analysis-jobs` | Bearer JWT | 202, enqueue theo inputVersion |
| GET | `/api/v1/jobs/{id}` | Bearer JWT | 200, progress/snapshot/error |
| POST | `/api/v1/jobs/{id}/cancel` | Bearer JWT | 202, hủy/yêu cầu hủy |
| POST | `/api/v1/jobs/{id}/retry` | Bearer JWT | 202 nếu lỗi retryable |
| GET | `/api/v1/meetings/{id}/tasks` | Bearer JWT | 200 task nháp hiện hành (AI + thủ công) |
| POST/PATCH/DELETE | `/meetings/{id}/tasks`, `/tasks/{id}`, `/tasks/{id}/restore`, `/tasks/{id}/evidence` | Bearer JWT | Review task — xem [Review Test Guide](Review_Test_Guide.md) |

GET meeting nay có currentAnalysisJobId nullable và analysisStatus thực. PATCH input bị từ chối 409 RESOURCE_BUSY nếu có job active; enqueue không tăng inputVersion. Chi tiết idempotency, owner, checkpoint/recovery/cancel xem guide job. GET tasks nhận optional analysisJobId: khác job hiện hành trả 409 STALE_VIEW; account khác trả 404. Từ V4 (extension 0.4.0) response là `{meetingId, inputVersion, transcriptRevision, analysis, tasks, counts}`: `analysis` null khi chưa phân tích, có `status` thật khi job chưa xong (khi đó `tasks` chỉ gồm task thủ công). Mỗi task có `version`, `origin`, `warnings` do server tính, `aiSuggestion`, `editedFields`, `evidence`. Lời gọi GET này không enqueue/gọi AI.

Sau khi job COMPLETED, trong Postman dùng GET `{{baseUrl}}/api/v1/meetings/{{meetingId}}/tasks?analysisJobId={{jobId}}`, Bearer JWT hiện hành. Với các biến cURL đã thiết lập:

```sh
curl -sS "$API/meetings/$MEETING_ID/tasks?analysisJobId=$JOB_ID" \
  -H "Authorization: Bearer $TOKEN"
```

`JOB_ID` lấy từ response tạo job. Task bắt đầu `reviewStatus=PENDING_REVIEW`, `version=1`; sửa/loại bỏ/khôi phục/thêm thủ công theo [Review Test Guide](Review_Test_Guide.md). Chưa có approve-and-sync/Trello. Xem LLM Setup để cấu hình provider và đối chiếu nội dung/người/hạn/evidence.

Với JSON, gửi `Content-Type: application/json`. Với multipart, để client tự đặt Content-Type/boundary; không tự gõ header `multipart/form-data`. GET/logout không cần body. Register/login/health dùng **No Auth** trong Postman và không gửi Authorization cũ; token sai vẫn có thể bị security filter từ chối ở route public.

## 3 Thiết lập Postman

Tạo environment `AI MTT Local`, chọn environment này rồi tạo các biến:

| Biến | Giá trị ban đầu | Dùng ở đâu |
| --- | --- | --- |
| `baseUrl` | `http://127.0.0.1:8080`, thay port nếu khác | Mọi URL, không có dấu `/` cuối |
| `emailA` | Email account A chưa đăng ký | Register/login A |
| `emailB` | Email account B chưa đăng ký | Test owner |
| `password` | `Test-api-2026!` | Dữ liệu giả cho cả A/B |
| `token` | Rỗng | Bearer Token của A |
| `tokenB` | Rỗng | Bearer Token của B |
| `meetingId` | Rỗng | Meeting của A |
| `inputVersion` | Rỗng | Version hiện tại để PATCH |
| `oldInputVersion` | Rỗng | Version trước PATCH để test stale |
| `historyCursor` | Rỗng | Cursor string cho history |
| `segmentCursor` | Rỗng | Sequence number cho transcript |

URL ví dụ: `{{baseUrl}}/api/v1/auth/login`. Với protected request, chọn Authorization → Bearer Token → `{{token}}`; riêng request owner B dùng `{{tokenB}}`. Với JSON, chọn Body → raw → JSON. Với file, chọn Body → form-data, field `file` có kiểu **File**, metadata có kiểu **Text**.

Các snippet Postman trong tài liệu đặt ở **Scripts → Post-response** (phiên bản cũ có thể gọi là Tests). Chúng lưu biến khi response thành công, không tự gửi request tiếp theo. Biến và assertion dựa trên [Postman variables](https://learning.postman.com/docs/use/send-requests/variables/variables/) và [Postman test scripts](https://learning.postman.com/docs/tests-and-scripts/write-scripts/test-scripts/).

## 4 Luồng test thành công

### 4.1 Health

```sh
curl -i "$BASE_URL/actuator/health"
```

Mong đợi 200:

```json
{"status":"UP"}
```

Nếu không kết nối được, kiểm tra terminal chạy backend và port. DB không sẵn sàng có thể làm health trả 503; chưa tiếp tục các bước nghiệp vụ.

### 4.2 Đăng ký account A

`POST {{baseUrl}}/api/v1/auth/register`, No Auth. Body Postman dùng `{{emailA}}` và `{{password}}` hoặc dữ liệu cụ thể như dưới:

```json
{
  "email": "api-a-20261005@example.test",
  "password": "Test-api-2026!"
}
```

```sh
curl -i -X POST "$API/auth/register" \
  -H 'Content-Type: application/json' \
  --data '{"email":"api-a-20261005@example.test","password":"Test-api-2026!"}'
```

Mong đợi 201, response chỉ có ID/email, không có password/hash:

```json
{
  "id": "11111111-1111-4111-8111-111111111111",
  "email": "api-a-20261005@example.test"
}
```

Email được lưu lowercase. Password hiện kiểm tra 10–72 UTF-16 code units và không quá 72 byte UTF-8; ký tự có dấu có thể đạt giới hạn byte sớm hơn. Gửi lại cùng email sau lần thành công: 409 `EMAIL_EXISTS`.

### 4.3 Đăng nhập và lưu token A

`POST {{baseUrl}}/api/v1/auth/login`, No Auth, cùng email/password vừa đăng ký:

```sh
curl -i -X POST "$API/auth/login" \
  -H 'Content-Type: application/json' \
  --data '{"email":"api-a-20261005@example.test","password":"Test-api-2026!"}'
```

Mong đợi 200:

```json
{
  "accessToken": "<JWT_FROM_RESPONSE>",
  "expiresAt": "2026-10-05T12:15:00Z",
  "sessionId": "66666666-6666-4666-8666-666666666666"
}
```

Post-response để lưu tự động:

```javascript
pm.test("Login thành công", () => pm.response.to.have.status(200));
if (pm.response.code === 200) {
  const body = pm.response.json();
  pm.environment.set("token", body.accessToken);
}
```

Với cURL, copy **chỉ giá trị accessToken**, không kèm chữ Bearer:

```sh
TOKEN='paste-accessToken-thuc-te-o-day'
```

### 4.4 Lấy user hiện tại

```sh
curl -i "$API/auth/me" -H "Authorization: Bearer $TOKEN"
```

Mong đợi 200, `id/email` của A giống đăng ký. Thiếu/sai/hết hạn token trả 401 `SESSION_EXPIRED`.

### 4.5 Tạo meeting bằng paste

`POST {{baseUrl}}/api/v1/meetings`, Bearer A, raw JSON:

```json
{
  "title": "API test meeting",
  "meetingDate": "2026-10-05",
  "timezone": "Asia/Ho_Chi_Minh",
  "transcriptText": "Nam:  Mai sửa login nhé.\nMai: Chưa chốt hạn.\nLong: Không đổi API."
}
```

```sh
curl -i -X POST "$API/meetings" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  --data '{"title":"API test meeting","meetingDate":"2026-10-05","timezone":"Asia/Ho_Chi_Minh","transcriptText":"Nam:  Mai sửa login nhé.\nMai: Chưa chốt hạn.\nLong: Không đổi API."}'
```

Mong đợi 201. Các ID/version/thời điểm dưới là minh họa; luôn dùng giá trị backend vừa trả:

```json
{
  "meetingId": "22222222-2222-4222-8222-222222222222",
  "inputVersion": 1,
  "transcriptRevision": "33333333-3333-4333-8333-333333333333",
  "title": "API test meeting",
  "meetingDate": "2026-10-05",
  "timezone": "Asia/Ho_Chi_Minh",
  "preview": "Nam: Mai sửa login nhé.\nMai: Chưa chốt hạn.\nLong: Không đổi API.",
  "characterCount": 64,
  "segmentCount": 3,
  "warnings": [],
  "analysisStatus": "NOT_STARTED",
  "createdAt": "2026-10-05T12:00:00Z",
  "sourceExpiresAt": "2026-11-04T12:00:00Z"
}
```

Kiểm tra preview gộp hai dấu cách thành một, vẫn giữ `Không`, đủ ba segment và chưa gọi AI. `characterCount` đo normalized text theo UTF-16, không phải raw text/file bytes. Preview dài tối đa 4000 code units; đọc đầy đủ qua API transcript. `transcriptRevision` là UUID, khác với `inputVersion` số. `sourceExpiresAt` hiện mới là thời hạn lưu đã ghi; chưa có scheduler purge.

Post-response:

```javascript
pm.test("Meeting được tạo", () => pm.response.to.have.status(201));
if (pm.response.code === 201) {
  const body = pm.response.json();
  pm.environment.set("meetingId", body.meetingId);
  pm.environment.set("inputVersion", body.inputVersion);
  pm.test("Đủ ba segment, chưa analysis", () => {
    pm.expect(body.segmentCount).to.eql(3);
    pm.expect(body.analysisStatus).to.eql("NOT_STARTED");
  });
}
```

Với cURL, lưu ID thật:

```sh
MEETING_ID='paste-meetingId-thuc-te-o-day'
```

Chỉ `transcriptText` bắt buộc. `title`, `meetingDate`, `timezone` có thể bỏ/null; không tự dùng ngày upload làm ngày họp. Title tối đa 255; timezone phải là IANA ID hợp lệ nếu có. Không gửi field ngoài schema.

### 4.6 Đọc metadata và preview

```sh
curl -i "$API/meetings/$MEETING_ID" -H "Authorization: Bearer $TOKEN"
```

Mong đợi 200, cùng cấu trúc meeting response ở 4.5. ID, revision/version và preview phải khớp dữ liệu đang lưu. Dùng `inputVersion` mới nhất từ đây trước PATCH.

### 4.7 Đọc history theo cursor

```sh
curl -i "$API/meetings?limit=2" -H "Authorization: Bearer $TOKEN"
```

Mong đợi 200, `items` chỉ chứa meeting của A, sắp xếp mới nhất trước. Cấu trúc:

```json
{
  "items": [
    {
      "meetingId": "22222222-2222-4222-8222-222222222222",
      "title": "API test meeting",
      "meetingDate": "2026-10-05",
      "inputVersion": 1,
      "createdAt": "2026-10-05T12:00:00Z"
    }
  ],
  "nextCursor": null
}
```

Để test phân trang, tạo ít nhất ba meeting của A rồi gọi với `limit=2`. Nếu `nextCursor` là string, đặt vào query `cursor` ở lần kế; `null` nghĩa là hết trang. Mặc định limit 20, cho phép 1–100. Không tự tạo/giải mã cursor hoặc gửi literal `null`.

```sh
HISTORY_CURSOR='paste-nextCursor-string-thuc-te-o-day'
curl -i --get "$API/meetings" \
  -H "Authorization: Bearer $TOKEN" \
  --data-urlencode 'limit=2' \
  --data-urlencode "cursor=$HISTORY_CURSOR"
```

Trong Postman, dùng query `limit=2`, `cursor={{historyCursor}}`; lấy `nextCursor` bằng tay. Đảm bảo giữa hai trang không lặp/mất meeting khi không có request tạo/sửa xen giữa.

### 4.8 Đọc segment và source theo cursor

```sh
curl -i "$API/meetings/$MEETING_ID/transcript?limit=1&cursor=-1" \
  -H "Authorization: Bearer $TOKEN"
```

Mong đợi 200; mẫu segment đầu của paste ở 4.5:

```json
{
  "transcriptRevision": "33333333-3333-4333-8333-333333333333",
  "sourceAvailable": true,
  "segments": [
    {
      "segmentId": "44444444-4444-4444-8444-444444444444",
      "sequence": 0,
      "speaker": null,
      "timestamp": null,
      "text": "Nam: Mai sửa login nhé.",
      "sourceText": "Nam:  Mai sửa login nhé.",
      "normalizedStart": 0,
      "normalizedEnd": 23,
      "sourceLocator": {"sourceType":"PASTE","lineIndex":0,"rawStart":0,"rawEnd":24}
    }
  ],
  "nextCursor": 0
}
```

`text` đã chuẩn hóa; `sourceText` giữ hai dấu cách trong nguồn. Speaker hiện chưa suy identity, nên null dù text có `Nam:`. Range bắt đầu từ 0, end-exclusive và dùng UTF-16; không phải offset UTF-8 bytes. Source map hiện ở cấp dòng/segment, chưa map từng ký tự sau NFC.

Cursor của **transcript là số sequence**, khác cursor string của history. `nextCursor=0` vẫn là giá trị hợp lệ: gửi `cursor=0` để lấy segment tiếp; không dùng kiểm tra boolean `if (nextCursor)` vì 0 bị coi là false. Mặc định cursor -1/limit 50; limit 1–100. `nextCursor=null` nghĩa là hết trang; response có thể có segments rỗng nếu cursor đã qua cuối.

```sh
curl -i "$API/meetings/$MEETING_ID/transcript?limit=1&cursor=0" \
  -H "Authorization: Bearer $TOKEN"
```

Post-response tùy chọn:

```javascript
if (pm.response.code === 200) {
  const body = pm.response.json();
  if (body.nextCursor !== null) {
    pm.environment.set("segmentCursor", body.nextCursor);
  } else {
    pm.environment.unset("segmentCursor");
  }
}
```

### 4.9 Tạo meeting từ TXT hoặc DOCX

Trong Postman: `POST {{baseUrl}}/api/v1/meetings`, Bearer A, form-data:

| Key | Kiểu | Giá trị |
| --- | --- | --- |
| `file` | File | Chọn đúng một `.txt` hoặc `.docx` |
| `title` | Text | `File API test` |
| `meetingDate` | Text | `2026-10-05` |
| `timezone` | Text | `Asia/Ho_Chi_Minh` |

Tạo TXT UTF-8 từ terminal test:

```sh
printf '%s\n' 'Nam:  Mai sửa login nhé.' 'Mai: Chưa chốt hạn.' 'Long: Không đổi API.' > /tmp/ai-mtt-api-test.txt
curl -i -X POST "$API/meetings" \
  -H "Authorization: Bearer $TOKEN" \
  -F 'file=@/tmp/ai-mtt-api-test.txt;type=text/plain' \
  -F 'title=File API test' \
  -F 'meetingDate=2026-10-05' \
  -F 'timezone=Asia/Ho_Chi_Minh'
```

DOCX: tạo file Word với paragraph `Đầu.`, bảng một hàng/hai cell `Giữa 1.` và `Giữa 2.`, paragraph `Cuối.`. Upload file thật bằng lệnh dưới; thay đường dẫn:

```sh
curl -i -X POST "$API/meetings" \
  -H "Authorization: Bearer $TOKEN" \
  -F 'file=@/absolute/path/meeting.docx;type=application/vnd.openxmlformats-officedocument.wordprocessingml.document' \
  -F 'title=DOCX order test'
```

Mong đợi 201, cấu trúc giống 4.5. TXT giữ preview tương ứng; DOCX giữ thứ tự `Đầu. → Giữa 1. → Giữa 2. → Cuối.`. Dùng meetingId trả riêng của file để đọc transcript và xác minh locator paragraph/table/row/cell; không ghi đè biến meeting paste nếu muốn tiếp tục case PATCH.

TXT chỉ UTF-8/UTF-8 BOM. DOCX chỉ đọc body, bỏ headers/footers/comments; nếu có các phần đó, `warnings` chứa `DOCX_NON_BODY_CONTENT_OMITTED`. Không đổi đuôi PDF thành DOCX. Không thêm `transcriptText`, file thứ hai, field lạ hoặc metadata trùng key vào form.

### 4.10 Thay input và test stale version

`PATCH {{baseUrl}}/api/v1/meetings/{{meetingId}}/input`, Bearer A, raw JSON. Lấy version thật bằng GET ở 4.6. Ví dụ dưới chỉ hợp lệ nếu version hiện là 1:

```json
{
  "expectedVersion": 1,
  "title": "API test meeting revised",
  "meetingDate": "2026-10-05",
  "timezone": "Asia/Ho_Chi_Minh",
  "transcriptText": "Nam: Không làm login nữa.\nMai: Chuyển sang sửa payment."
}
```

Trong Postman, thay số `1` bằng `{{inputVersion}}` **không đặt trong dấu nháy**. Trước khi gửi PATCH, lưu version hiện tại sang `oldInputVersion` (bằng tay hoặc pre-request):

```javascript
pm.environment.set("oldInputVersion", pm.environment.get("inputVersion"));
```

Với cURL, sửa số trong payload thành version thật:

```sh
curl -i -X PATCH "$API/meetings/$MEETING_ID/input" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  --data '{"expectedVersion":1,"title":"API test meeting revised","meetingDate":"2026-10-05","timezone":"Asia/Ho_Chi_Minh","transcriptText":"Nam: Không làm login nữa.\nMai: Chuyển sang sửa payment."}'
```

Mong đợi 200: meetingId giữ nguyên, inputVersion tăng, transcriptRevision đổi UUID, preview đổi theo transcript mới. Đây là **thay toàn bộ input/metadata**; title/date/timezone bỏ hoặc null sẽ bị xóa, không giữ giá trị cũ như partial patch. transcriptText và expectedVersion bắt buộc. Hiện PATCH chỉ nhận JSON paste, chưa nhận multipart thay file.

Post-response chỉ dùng trên request PATCH thành công:

```javascript
pm.test("Input được thay", () => pm.response.to.have.status(200));
if (pm.response.code === 200) {
  pm.environment.set("inputVersion", pm.response.json().inputVersion);
}
```

Để test stale, tạo **request PATCH riêng** với cùng body nhưng `expectedVersion={{oldInputVersion}}`. Không gắn pre-request ghi lại oldInputVersion cho request stale. Mong đợi 409 `STALE_VERSION`; GET meeting sau đó vẫn là bản sửa thành công, không bị request stale ghi đè. Hai request hợp lệ cùng version chạy đồng thời: một 200 và một 409; thử thủ công từ hai terminal.

### 4.11 Kiểm tra account B không đọc hoặc sửa meeting A

Đăng ký/login B bằng cách lặp 4.2–4.3 với emailB. Lưu accessToken của B vào `tokenB`; không ghi đè token A. Với cURL:

```sh
TOKEN_B='paste-accessToken-cua-B-o-day'
curl -i "$API/meetings/$MEETING_ID" -H "Authorization: Bearer $TOKEN_B"
curl -i "$API/meetings/$MEETING_ID/transcript" -H "Authorization: Bearer $TOKEN_B"
curl -i -X PATCH "$API/meetings/$MEETING_ID/input" \
  -H "Authorization: Bearer $TOKEN_B" \
  -H 'Content-Type: application/json' \
  --data '{"expectedVersion":1,"transcriptText":"B thay input cua A."}'
```

Cả ba request mong đợi 404 `RESOURCE_NOT_FOUND`, kể cả request PATCH có version cũ nhưng body đúng schema. History B không chứa meeting A; nếu B chưa tự tạo meeting thì `items=[]`. GET bằng A sau test phải vẫn đọc được dữ liệu của A.

### 4.12 Logout và xác minh token bị revoke

Làm sau các tests cần token A:

```sh
curl -i -X POST "$API/auth/logout" -H "Authorization: Bearer $TOKEN"
curl -i "$API/auth/me" -H "Authorization: Bearer $TOKEN"
curl -i "$API/meetings/$MEETING_ID" -H "Authorization: Bearer $TOKEN"
```

Logout trả 204, không parse body JSON rỗng. Hai request sau dùng token cũ phải trả 401 `SESSION_EXPIRED`. Login lại cùng account, thay token, GET meeting phải đọc được draft đã lưu. Logout revoke session hiện tại; không tự revoke các session login khác của cùng user.

## 5 Kiểm tra lỗi và giới hạn

Chạy từng case với token còn hạn, body đúng schema ngoại trừ trường đang test và không tự thêm Origin. Nếu nhiều điều kiện sai cùng lúc, lỗi có thể bị chặn ở bước sớm hơn; ví dụ thiếu auth sẽ không tới validation owner.

| Case | Cách test | HTTP/code mong đợi |
| --- | --- | --- |
| Email đã đăng ký | Register A lần hai | 409 `EMAIL_EXISTS` |
| Email/password sai | Email sai format hoặc password dưới 10 code units | 400 `INVALID_INPUT` |
| Password quá 72 byte UTF-8 | Password 37 chữ `á`, body còn hợp lệ | 400 `INVALID_INPUT` |
| Sai credentials | Login với password sai nhưng đủ 10 ký tự | 401 `INVALID_CREDENTIALS` |
| Thiếu/sai/revoked/expired JWT | GET me hoặc meeting | 401 `SESSION_EXPIRED` |
| Transcript trống | `transcriptText:" \n\t"` | 400 `INVALID_INPUT` |
| Thiếu transcriptText | POST body chỉ có title | 400 `INVALID_INPUT` |
| Field JSON không hỗ trợ | Thêm `file`, `syncStatus` hoặc `unexpected` vào paste | 400 `INVALID_INPUT` |
| Timezone không hợp lệ | `timezone:"not-a-zone"` | 400 `INVALID_INPUT` |
| Ngày sai format | `meetingDate:"05/10/2026"` | 400 `INVALID_INPUT` |
| Text vượt giới hạn | 200001 chữ `a`, JSON còn dưới 1 MiB | 413 `TEXT_TOO_LARGE` |
| JSON vượt giới hạn | Body hơn 1048576 byte | 413 `REQUEST_TOO_LARGE` |
| File vượt giới hạn | Upload file hơn 10485760 byte | 413 `FILE_TOO_LARGE` |
| File/type sai | PDF/DOCM, hoặc TXT với MIME image/png | 415 `INVALID_FILE_TYPE` |
| DOCX hỏng | File `.docx` chứa text thường | 400 `INVALID_FILE` |
| TXT encoding sai | Bytes UTF-8 không hợp lệ | 400 `INVALID_ENCODING` |
| DOCX chỉ có text ngoài body | Text chỉ nằm trong header, body rỗng | 400 `NO_BODY_TEXT` |
| DOCX giải nén vượt giới hạn | Entry >20 MiB, tổng >50 MiB, >1000 entry hoặc ratio quá thấp | 413 `DOCX_ZIP_LIMIT` ở preflight |
| File cùng text/hai file | Multipart thêm transcriptText hoặc hai file cùng key | 400 `INVALID_INPUT` |
| ID không đúng UUID | `GET /meetings/not-a-uuid` | 400 `INVALID_INPUT` |
| ID không tồn tại/khác owner | UUID đúng nhưng không thuộc user | 404 `RESOURCE_NOT_FOUND` |
| History cursor sai | `cursor=invalid` | 400 `INVALID_INPUT` |
| Transcript cursor sai | `cursor=-2` hoặc `cursor=abc` | 400 `INVALID_INPUT` |
| Limit sai | `limit=0` hoặc `limit=101` | 400 `INVALID_INPUT` |
| Version cũ | PATCH sau lần sửa thành công, dùng version trước | 409 `STALE_VERSION` |
| PATCH thiếu expectedVersion | Body có transcriptText nhưng thiếu version | 400 `INVALID_INPUT` |
| Method chưa hỗ trợ | DELETE meeting hiện có bằng owner | 405 `METHOD_NOT_ALLOWED` |
| Vượt rate limit auth | Hơn 20 lần register/login hợp lệ qua cùng IP trong cửa sổ 60 giây | 429 `RATE_LIMIT`, Retry-After 60 |

Register và login dùng chung budget rate limit; các lần thử trước đó cũng tính vào cửa sổ. Sau case 429, chờ ít nhất 60 giây trước khi test auth tiếp. TTL access token hiện tối đa 15 phút, không có refresh API cho JWT hệ thống.

Tạo fixtures lỗi bằng Python stdlib, không cần thư viện thêm; các file chỉ là dữ liệu giả ở `/tmp`:

```sh
python3 - <<'PY'
import json
from pathlib import Path
Path('/tmp/ai-mtt-text-too-large.json').write_text(json.dumps({'transcriptText':'a' * 200001}))
Path('/tmp/ai-mtt-request-too-large.json').write_text(json.dumps({'transcriptText':'a' * 1048577}))
Path('/tmp/ai-mtt-invalid-utf8.txt').write_bytes(bytes([0xC3, 0x28]))
Path('/tmp/ai-mtt-invalid.docx').write_text('This is not a DOCX archive.')
PY
curl -i -X POST "$API/meetings" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  --data-binary @/tmp/ai-mtt-text-too-large.json
```

Dùng tương tự với request-too-large JSON. Với invalid TXT/DOCX, dùng multipart `file` như mục 4.9. Không chạy script fixtures rồi ghi case pass trước khi gửi request và kiểm tra response.

### Cấu trúc lỗi chung

Ví dụ stale version:

```json
{
  "timestamp": "2026-10-05T12:10:00Z",
  "traceId": "55555555-5555-4555-8555-555555555555",
  "status": 409,
  "code": "STALE_VERSION",
  "message": "Input đã thay đổi. Tải lại trước khi sửa.",
  "retryable": false,
  "details": []
}
```

Kiểm tra HTTP status khớp field status, `traceId` khớp header `X-Trace-Id`, không có password/hash/stacktrace hoặc nội dung transcript bị phản chiếu trong message. Validation field có thể có details gồm `field/message`; không yêu cầu message cố định cho mọi lỗi cùng code.

Post-response cho riêng case 409 stale:

```javascript
pm.test("Stale version bị từ chối", () => pm.response.to.have.status(409));
const error = pm.response.json();
pm.test("Code và trace đúng", () => {
  pm.expect(error.code).to.eql("STALE_VERSION");
  pm.expect(error.traceId).to.eql(pm.response.headers.get("X-Trace-Id"));
});
```

### CORS kiểm tra riêng khi có browser/Extension

cURL và Postman desktop thường không gửi Origin tự động. CORS không thay thế JWT/owner checks. Mặc định allowlist rỗng; `run_local.py` cũng đặt allowlist rỗng, nên request có Origin không được chấp nhận ở `/api/**`.

Để test case allow, chạy backend theo `.env`/Compose trong README với `EXTENSION_ORIGIN_ALLOWLIST=chrome-extension://<extension-id-thật>` rồi restart backend. Thay Origin ở lệnh bằng chính giá trị cấu hình:

```sh
curl -i -X OPTIONS "$API/meetings" \
  -H 'Origin: chrome-extension://your-real-extension-id' \
  -H 'Access-Control-Request-Method: POST' \
  -H 'Access-Control-Request-Headers: authorization,content-type'
```

Origin đúng allowlist: 200 và `Access-Control-Allow-Origin` đúng Origin. Origin ngoài allowlist: 403. **Lỗi CORS hiện có thể trả text `Invalid CORS request`, không phải JSON ApiError**; không dùng snippet parse JSON cho case này. Không đặt wildcard trong allowlist.

## 6 Ghi kết quả test

Giữ bản ghi cho mỗi lần chạy; mẫu bên dưới chưa có kết quả. Không lưu JWT/password thật trong report. Case chưa chạy ghi `Chưa chạy`, môi trường cản trở ghi `Blocked`.

| Nhóm | Request/case | Expected | Actual | Kết quả | Ghi chú/traceId |
| --- | --- | --- | --- | --- | --- |
| Health | GET health | 200 UP | — | Chưa chạy | |
| Auth | Register A/login/me | 201/200/200 | — | Chưa chạy | |
| Input | Paste preview 3 segment | 201 | — | Chưa chạy | |
| History | Hai trang không trùng | 200 | — | Chưa chạy | |
| Source | Raw spaces khác normalized | 200 | — | Chưa chạy | |
| File | TXT/DOCX đúng body order | 201 | — | Chưa chạy | |
| Version | PATCH hợp lệ rồi stale | 200/409 | — | Chưa chạy | |
| Owner | B GET/source/PATCH meeting A | 404 | — | Chưa chạy | |
| Logout | Logout rồi dùng token cũ | 204/401 | — | Chưa chạy | |
| Validation | Các case ở mục 5 | Theo từng case | — | Chưa chạy | |

## 7 Kiểm tra tự động và cơ sở đối chiếu

Sau khi kiểm tra API, dùng [Extension Setup](Extension_Setup.md) để build/Load unpacked Side Panel, cấu hình CORS theo ID thật và kiểm tra luồng auth → input → preview → history → mở lại meeting. Backend test API và client fixture tests không thay thế checklist Chrome end-to-end.

Tests source ở [FoundationIntegrationTest.java](../../backend/src/test/java/vn/aimtt/FoundationIntegrationTest.java), [AnalysisJobIntegrationTest.java](../../backend/src/test/java/vn/aimtt/AnalysisJobIntegrationTest.java) và [LlmIntegrationTest.java](../../backend/src/test/java/vn/aimtt/LlmIntegrationTest.java), cùng unit tests auth/parser/filter/rate limiter/jobs/llm/config. Suite hiện có 66 unit và 26 integration tests. Unit/package đạt; integration suite bị chặn socket khi khởi tạo PostgreSQL và HTTP smoke mới chưa chạy runtime. Kết quả V1 lịch sử không chứng minh V2/V3 đã pass. Extension 0.3.0 có 40 tests/build đạt với fixtures; hai adapter cần cấu hình/test thực theo [LLM Setup](LLM_Setup.md).

Chạy toàn bộ bằng `python3 backend/scripts/run_local.py` từ terminal local có quyền Docker. Nếu đã có DB test rỗng riêng, xem README để đặt `TEST_DATABASE_*` và chạy `./mvnw verify`. Không trỏ tests vào DB chứa dữ liệu cần giữ.

Nguồn hợp đồng API: [AuthController](../../backend/src/main/java/vn/aimtt/auth/AuthController.java), [MeetingController](../../backend/src/main/java/vn/aimtt/meeting/MeetingController.java), [MeetingService](../../backend/src/main/java/vn/aimtt/meeting/MeetingService.java), [TranscriptParser](../../backend/src/main/java/vn/aimtt/transcript/TranscriptParser.java), [ApiExceptionHandler](../../backend/src/main/java/vn/aimtt/common/ApiExceptionHandler.java), [application.yml](../../backend/src/main/resources/application.yml). Khi code thay đổi, cập nhật guide và expected results tương ứng.

## 8 Trello, tạo card và xóa dữ liệu (14.5–14.7)

Dùng Trello giả (`python3 tools/fake_trello.py --port 9999`, token demo được in ra khi khởi động) và các biến backend trong [Demo Script](Demo_Script.md), hoặc dùng Board thử nghiệm thật theo [Trello Setup](Trello_Setup.md). Ví dụ dưới đây dùng lại `API`, `TOKEN` và `MEETING_ID` (meeting đã phân tích xong).

```sh
# Kết nối bằng token (với OAuth: POST $API/trello/connections/authorize, mở authorizeUrl, rồi poll GET $API/trello/authorizations/<transactionId>)
curl -s -X POST "$API/trello/connections/token" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"token":"TOKEN_TRELLO"}'
CONN_ID='...'   # id trong response
curl -s "$API/trello/connections/$CONN_ID/boards" -H "Authorization: Bearer $TOKEN"
curl -s "$API/trello/boards/BOARD_ID/lists?connectionId=$CONN_ID" -H "Authorization: Bearer $TOKEN"

# Nơi tạo card (expectedVersion 0 khi chưa có), sau đó đối chiếu người phụ trách và gợi ý hạn
curl -s -X PUT "$API/meetings/$MEETING_ID/destination" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"connectionId\":\"$CONN_ID\",\"boardId\":\"BOARD_ID\",\"listId\":\"LIST_ID\",\"expectedVersion\":0}"
curl -s -X POST "$API/meetings/$MEETING_ID/resolve-members" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"expectedDestinationVersion":1}'
curl -s -X POST "$API/meetings/$MEETING_ID/resolve-deadlines" -H "Authorization: Bearer $TOKEN"

# Xác nhận người phụ trách/hạn bằng PATCH task (trelloMemberId hoặc memberDecision, dueLocal hoặc deadlineDecision) rồi xem trước và tạo card
curl -s "$API/tasks/TASK_ID/card-preview" -H "Authorization: Bearer $TOKEN"
curl -s -X POST "$API/meetings/$MEETING_ID/approve-and-sync" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" -d '{"expectedDestinationVersion":1,"tasks":[{"taskId":"TASK_ID","expectedVersion":3}]}'
curl -s "$API/sync-jobs/SYNC_JOB_ID" -H "Authorization: Bearer $TOKEN"
```

Kết quả mong đợi khi gọi `approve-and-sync`:

- Thành công: trả về 202 với `syncJobId`.
- Gửi lại cùng key và cùng body: nhận lại đúng job cũ.
- Cùng key nhưng body khác: lỗi 409.
- Task đã đổi version: 409 `STALE_VERSION`, kèm `details`.
- Task chưa đủ điều kiện (người phụ trách chưa rõ, hạn chưa quyết định, đã tạo card…): 422 `APPROVAL_REJECTED`, kèm danh sách lý do theo từng task.

Mỗi item của sync job có `status`, `cardUrl`, `errorCode`, `retryable` và `actions`. Các hành động:

- **RETRY**: chỉ cho lỗi tạm thời.
- **RECONCILE**: dành cho item UNKNOWN.
- **LINK_CARD**: gửi body `{"card":"<link hoặc id>","acknowledgeNoMarker":false}`.
- **RECREATE**: chỉ xuất hiện sau khi đối soát không thấy card. Body: `{"acknowledgementDuplicateRisk":true,"expectedVersion":n}`.

Xóa dữ liệu:

- `DELETE $API/meetings/$MEETING_ID/transcript` xóa nguồn transcript. Task và liên kết card vẫn được giữ.
- `DELETE $API/meetings/$MEETING_ID` xóa meeting và dữ liệu liên quan.
- Cả hai trả 204 khi thành công.
- Trả 409 `RESOURCE_BUSY` khi job phân tích đang chạy. Riêng xóa meeting cũng trả 409 khi còn card đang tạo hoặc chờ đối soát.
- Card đã tạo trên Trello không bị xóa.
