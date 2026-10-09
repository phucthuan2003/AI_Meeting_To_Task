# Kết nối Chrome Extension với backend localhost

Ngày triển khai: 09/10/2026. Bản 0.3.0 có nhập/preview/history, chọn OpenAI/Gemini, theo dõi DB analysis jobs và đọc đề xuất AI/evidence. Backend đã có adapters; cần key để phân tích thật. Chưa có sửa/duyệt task hoặc Trello. Xem [LLM Setup](LLM_Setup.md) và [Analysis Job Test Guide](Analysis_Job_Test_Guide.md).

## 1. Build và load Side Panel

Cần Chrome 116+ và Node 22.12+. Từ thư mục project:

```sh
cd extension
npm ci
npm run check
```

Build mặc định gọi **`http://127.0.0.1:8080`**. `localhost` và `127.0.0.1` là hai hostname khác nhau. Nếu backend dùng cổng/hostname khác, build lại đúng origin:

```sh
API_ORIGIN=http://localhost:18080 npm run build
```

1. Mở `chrome://extensions`, bật **Developer mode**.
2. Bấm **Load unpacked**, chọn thư mục `extension/dist` đã build (không chọn source `extension`).
3. Ghi lại **ID** extension. Có thể lấy origin chính xác ở mục **Backend** trong Side Panel.
4. Bấm icon extension để mở Side Panel; có thể pin icon lên toolbar.
5. Sau mỗi lần build lại, bấm **Reload** ở `chrome://extensions`, đóng/mở lại panel. Reload extension xóa session token; đăng nhập lại để khôi phục meeting.

React và JavaScript được bundle trong `dist`, không dùng CDN hoặc remote code. Manifest chỉ cấp `storage`, `sidePanel` và host backend được build; không có content script hoặc quyền đọc trang họp. Host permission của Chrome không giới hạn theo port; CSP `connect-src` và API client giới hạn origin gồm port. API origin thay đổi cần build lại, không có form nhận URL tùy ý rồi gửi token tới đó.

### Cảnh báo npm audit khi cài dependencies

Log local người dùng ngày 09/10 xác nhận bản dependency cũ chạy 35 tests và build đạt, nhưng npm audit báo 6 high severity vulnerabilities. Build đạt không chứng minh dependency đã được vá. Báo cáo gửi lại có Vite 7.2.7, PostCSS, Rollup và source-map-js; npm đề xuất Vite 7.3.7 ngoài exact pin hiện tại, các dependency còn lại có fix qua npm audit fix.

Chạy lần lượt ngay trong thư mục extension, dừng nếu một lệnh lỗi:

```sh
npm install --save-dev --save-exact vite@7.3.7
npm audit fix
npm run check
npm audit
```

Lệnh đầu cập nhật Vite trong cùng major 7 và ghi package.json/package-lock.json; npm audit fix cập nhật dependency có bản sửa trong phạm vi cho phép. Không cần dùng npm audit fix --force cho hướng xử lý này. Nếu audit còn cảnh báo, giữ báo cáo chi tiết để đối chiếu tiếp; chưa ghi hết lỗ hổng cho đến khi audit mới xác nhận. Giữ API_ORIGIN tùy chỉnh khi build nếu đang dùng backend khác origin mặc định. Sau khi check đạt, Reload extension ở chrome://extensions rồi đóng/mở panel.

Ở lượt audit, agent chưa tải được bản vá do registry bị sandbox chặn DNS (ENOTFOUND). Kiểm tra filesystem sau đó thấy package/lock đã cập nhật: Vite 7.3.7, PostCSS 8.5.29, Rollup 4.64.3, source-map-js 1.2.2 và picomatch 4.0.7. Chưa có báo cáo audit mới để ghi found 0 vulnerabilities. Không sửa thủ công integrity trong lockfile.

Nguồn đối chiếu: [Vite 7.3.7](https://github.com/vitejs/vite/releases/tag/v7.3.7), [PostCSS bản vá đầy đủ 8.5.23](https://github.com/postcss/postcss/security/advisories/GHSA-fxqj-rqcc-2cmp), [Rollup bản vá 4.59.0](https://github.com/rollup/rollup/security/advisories/GHSA-mw96-cpmx-2vgc), [source-map-js bản vá 1.2.2](https://github.com/advisories/GHSA-68fv-2mgg-jv7q). Các mốc này hỗ trợ đối chiếu advisory đã biết, không thay thế audit hiện hành.

## 2. Khởi động backend đúng CORS

### Chạy thử bằng DB tạm

Docker Desktop cần đang chạy. Từ thư mục project, thay ID dưới đây bằng ID thật:

```sh
EXTENSION_ORIGIN_ALLOWLIST='chrome-extension://YOUR_EXTENSION_ID' \
python3 backend/scripts/run_local.py
```

Script chạy backend `verify`, HTTP smoke rồi giữ server. Ưu tiên cổng 8080; nếu cổng đã bận, URL thực được in ở cuối. Khi đó build lại `API_ORIGIN` đúng URL, Reload extension. Script đã được sửa để giữ allowlist truyền từ môi trường, thay vì luôn ghi đè thành chuỗi rỗng.

DB demo của script là dữ liệu tạm: **Ctrl+C sẽ dừng và xóa container demo**, nên meeting của phiên demo không có ở lần chạy tiếp theo. Để test mở lại sau khi restart backend mà vẫn giữ dữ liệu, dùng Compose bên dưới.

### Chạy với DB Compose có volume

Theo setup `.env` trong [README](../../README.md), đặt:

```sh
EXTENSION_ORIGIN_ALLOWLIST='chrome-extension://YOUR_EXTENSION_ID'
```

Sau đó từ project root:

```sh
docker compose up -d postgres
set -a
. ./.env
set +a
cd backend
./mvnw spring-boot:run
```

Giữ `DATABASE_PASSWORD` và `JWT_SIGNING_KEY` trong `.env` local, không commit. Khi ID extension đổi (chẳng hạn chuyển thư mục load), cập nhật allowlist và restart backend. Không cấu hình wildcard.

### Nếu `source .env` lỗi hoặc backend không nhận password

File `.env` nằm ở thư mục Code, cạnh compose.yaml, không nằm trong backend. Nếu terminal đang ở backend, dùng `cd ..` trước khi nạp cấu hình:

```sh
cd ..
set -a
source ./.env
set +a
if [ -n "$DATABASE_PASSWORD" ]; then
  echo 'Database password đã được nạp'
else
  echo 'Database password còn trống; kiểm tra .env'
fi
cd backend
./mvnw spring-boot:run
```

Đoạn `cd ..` trên chỉ dùng khi đang ở backend. Nếu đã ở Code thì bỏ dòng đó. Assignment phải là `DATABASE_PASSWORD='giá-trị'`: **không có khoảng trắng trước hoặc sau dấu `=`**. Dạng `DATABASE_PASSWORD= giá-trị` khiến shell cố chạy giá trị như tên lệnh và không export được password cho Java. Lỗi PostgreSQL `SCRAM-based authentication, but no password was provided` tương ứng với backend chưa nhận password, chưa phải bằng chứng password sai. Sửa syntax rồi nạp lại; giữ nguyên password đã dùng để tạo DB. Không xóa volume để xử lý lỗi nạp biến môi trường.

## 3. Test luồng kết nối

Chỉ dùng transcript giả. Kết quả trong bảng là **expected**, chưa phải các case đã chạy trên Chrome/backend thật.

| Bước | Thao tác | Kết quả cần kiểm tra |
| --- | --- | --- |
| 1 | Mở mục Backend → Kiểm tra kết nối | Health `Sẵn sàng`; URL đúng server đang chạy. Health không chứng minh login/CORS đã đạt. |
| 2 | Đăng ký email mới; password 10–72 ký tự, tối đa 72 byte UTF-8 | Đăng ký, đăng nhập rồi hiển thị email. Nếu đăng ký thành công nhưng login lỗi, chuyển sang Đăng nhập, không đăng ký trùng. |
| 3 | Dán transcript mẫu dưới đây; ngày để trống nếu chưa biết, kiểm tra múi giờ → Lưu & xem preview | Meeting ID/version, preview, segment count, nguồn và warnings. Chưa gọi LLM. |
| 4 | Mở từng segment | Text gốc, locator và offset UTF-16 đúng revision; chuỗi HTML được hiển thị như văn bản. |
| 5 | Đóng rồi mở lại Side Panel | Session trong Chrome còn hiệu lực; tải meeting từ backend, không lấy transcript từ browser storage. |
| 6 | Lịch sử → chọn meeting | Chỉ hiển thị meeting của tài khoản đang đăng nhập. Có nút Xem thêm khi backend trả cursor. |
| 7 | Nhập mới → TXT/DOCX → chọn đúng một file | Upload với metadata, không gửi kèm paste text. File lỗi hiển thị code/message/trace nếu backend trả. |
| 8 | Thay input → nhập lại toàn bộ transcript → Lưu revision mới | PATCH có expectedVersion; version/revision thay đổi. Form bắt đầu với nội dung trống để tránh lấy preview bị cắt làm toàn bộ transcript. |
| 9 | Mở cùng meeting ở hai cửa sổ, sửa và lưu ở A, rồi lưu từ version cũ ở B | B trả 409 STALE_VERSION; không tự ghi đè. Bấm Tải phiên bản mới nhất sẽ hỏi bỏ nội dung chưa lưu. |
| 10 | Đăng xuất, đăng nhập cùng account | Logout revoke session; mở lại meeting đã lưu theo ID riêng của account. |
| 11 | Đăng xuất A rồi đăng nhập B | Không khôi phục pointer của A; history dùng account B, backend tiếp tục kiểm tra owner. |
| 12 | Hết hạn token (~15 phút) hoặc revoke qua API | Yêu cầu đăng nhập lại, pointer meeting đã lưu còn; input chưa lưu không được lưu bền. |
| 13 | Backend ngừng chạy khi gửi request | Báo mất kết nối/timeout, không tự retry lệnh ghi. Kiểm tra lịch sử trước khi gửi lại để tránh tạo thêm meeting. |

Transcript mẫu:

```text
09:00:00 Nam: Mai sửa lỗi đăng nhập nhé.
09:01:00 Mai: Được, nhưng chưa chốt hạn.
09:02:00 Nam: Không tạo card trước khi mọi người duyệt.
```

Meeting mới không tự lấy ngày upload làm ngày họp. Nút **Lưu** gửi input tới backend. Bản 0.3.0 có **Chọn AI** với OpenAI/Gemini; provider lưu vào job, ô chọn khóa khi active/gửi request. API key đặt ở backend. Provider có key dùng nút **Phân tích** và policy llm-v1; khi hoàn tất, panel đọc đề xuất/evidence đã lưu. Provider chưa có key hoặc job foundation cũ vẫn FAILED PROVIDER_NOT_CONFIGURED. Readiness chỉ kiểm tra key có giá trị, chưa xác minh quyền/quota/model. Polling/cancel/retry tiếp tục dùng provider đã pin trong job; không tự fallback.

## 4. Privacy, lỗi và xử lý

- JWT, expiresAt và user dùng `chrome.storage.session`, chỉ trusted extension contexts. Logout thành công/xác nhận 401/hết hạn xóa token; không có refresh token. Nếu logout mất mạng, UI báo lỗi và vẫn giữ phiên để thử revoke lại.
- `chrome.storage.local` chỉ giữ meeting ID, key gồm backend origin và user ID. Không lưu transcript/file/password hoặc token vào local/sync/localStorage. Raw input chưa lưu chỉ có trong RAM của panel; đóng panel, hết phiên hoặc đổi account có thể làm mất phần chưa lưu.
- Preview, title, source và errors render bằng React text. Không dùng `dangerouslySetInnerHTML`; không đưa transcript vào log.
- Cursor transcript `0` là hợp lệ. UI không nhầm `0` với hết trang. Khi revision của page khác meeting đang xem, dừng ghép nguồn và yêu cầu tải lại.
- `403 Invalid CORS request`: xác nhận `chrome-extension://ID` ở allowlist và restart backend; trường hợp lỗi trình duyệt có thể chỉ hiện `NETWORK_ERROR`.
- `401`: đăng nhập lại. `409`: giữ form để bạn xử lý, không tự retry hoặc overwrite. `429`: chờ `Retry-After`. Lỗi/timeout khi lưu không chứng minh request chưa ghi; hãy xem history trước khi gửi lại.
- Retention `sourceExpiresAt` hiện chỉ là deadline lưu ở backend; purge scheduler chưa có. Không coi việc hiển thị deadline là chứng minh nguồn đã bị xóa.

## 5. Kiểm chứng và giới hạn hiện tại

`npm run check` chạy Node tests dùng fetch/storage giả lập, React server-render tests và build/kiểm tra artifact MV3. Tests này kiểm tra hợp đồng client, cách lưu phiên, giới hạn, pagination, version conflict và text escaping; **không thay thế** việc load Chrome Side Panel hoặc integration với PostgreSQL thật.

Ngày 09/10/2026: backend 66 unit tests/package và extension 40 tests/build MV3 đạt; 26 DB integration cases bị chặn socket khi khởi tạo PostgreSQL. Người dùng đã báo preparation job 2/2 và PROVIDER_NOT_CONFIGURED ở bản trước; chưa test API AI/Chrome 0.3.0 thật hoặc có báo cáo Pass toàn checklist. Dùng [LLM Setup](LLM_Setup.md), [API Test Guide](API_Test_Guide.md) và guide job để ghi actual result.

| Nhóm | Actual / status |
| --- | --- |
| Health, đăng ký/login/logout qua panel | Chưa chạy trên Chrome thật |
| Paste/TXT/DOCX, preview/source/history | Chưa chạy end-to-end |
| Đóng/mở panel, restart Chrome, hết token | Chưa chạy trên Chrome thật |
| Hai cửa sổ, 409, owner A/B | Chưa chạy end-to-end |
| Màn hình sidebar 320–400 px | Chờ kiểm tra trên Chrome Side Panel thật |

Nguồn API đã đối chiếu: [Chrome Side Panel](https://developer.chrome.com/docs/extensions/reference/api/sidePanel), [Chrome storage/session](https://developer.chrome.com/docs/extensions/reference/api/storage?hl=en), [Extension cross-origin requests](https://developer.chrome.com/docs/extensions/develop/concepts/network-requests), [Vite production build](https://vite.dev/guide/build.html).
