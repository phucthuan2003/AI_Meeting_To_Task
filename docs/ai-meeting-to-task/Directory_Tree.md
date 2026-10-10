# Cây thư mục và tác dụng từng file — AI Meeting to Task

Cập nhật ngày **10/10/2026**, theo mã nguồn trong workspace, extension **0.5.0**.

Tài liệu liệt kê từng file nguồn/cấu hình/test/tài liệu được Git quản lý và tài liệu mới chưa commit. Mỗi dòng file có chú thích sau dấu `#`. Thư mục được đánh dấu `/`. Dependency, build output, Git internals và cache được mô tả riêng, không bung hàng nghìn file sinh tự động. Không đọc/đưa giá trị secret từ `.env` vào tài liệu.

## 1. Nhìn nhanh cấu trúc

```text
Code/
├── backend/      # API, nghiệp vụ, DB access, worker AI/Trello
├── extension/    # Giao diện Chrome Side Panel
├── evaluation/   # Dataset và đo chất lượng AI/usability
├── tools/        # Trello giả và browser E2E
├── docs/         # SDS, hướng dẫn, nhật ký và báo cáo
├── compose.yaml  # PostgreSQL local
└── README.md     # Điểm bắt đầu đọc/chạy dự án
```

Backend là **Modular Monolith**: các package nghiệp vụ cùng nằm trong một ứng dụng Spring Boot. `extension/` là frontend độc lập gọi REST API. Các thư mục `auth`, `llm`, `sync` không phải microservice được triển khai riêng.

## 2. Cây đầy đủ có chú thích

Các tầng package Java `src/main/java/vn/aimtt` được giữ nguyên để dễ tìm trong IDE. Trong khu vực test, các package cùng tên chứa tests của module tương ứng.

```text
Code/
├── backend/  # Backend Spring Boot — Modular Monolith; toàn bộ nghiệp vụ và worker.
│   ├── .mvn/  # Cấu hình Maven Wrapper.
│   │   └── wrapper/
│   │       └── maven-wrapper.properties  # Khóa URL/phiên bản Maven mà Maven Wrapper sử dụng.
│   ├── scripts/  # Chạy local, smoke test và chẩn đoán Gemini.
│   │   ├── tests/
│   │   │   └── test_gemini_http.py  # Test TLS, redaction, lỗi HTTP và hành vi script chẩn đoán Gemini.
│   │   ├── check_gemini_model.py  # Gọi metadata model Gemini để kiểm tra khả năng hỗ trợ; không kiểm chứng toàn bộ phân tích.
│   │   ├── gemini_http.py  # HTTP/TLS dùng chung cho script Gemini; xử lý CA, chặn redirect và phân loại lỗi.
│   │   ├── http_smoke.py  # Khởi chạy JAR để kiểm thử luồng HTTP với DB test được cấu hình.
│   │   ├── probe_gemini_request.py  # Gửi dữ liệu giả kiểm tra Gemini; có diagnostics và so sánh structured format cũ.
│   │   ├── run_local.py  # Tạo DB Docker tạm, chạy kiểm thử/smoke và giữ backend chạy cho phiên local.
│   │   └── test_gemini_simple.py  # Script thử gọi Gemini với câu hỏi đơn giản; không phải pipeline trích xuất task.
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/
│   │   │   │   └── vn/
│   │   │   │       └── aimtt/
│   │   │   │           ├── auth/  # Xác thực, session và quyền truy cập.
│   │   │   │           │   ├── AuthController.java  # API đăng ký, đăng nhập, thông tin tài khoản hiện tại và đăng xuất.
│   │   │   │           │   ├── AuthProperties.java  # Cấu hình khóa JWT, issuer, audience và thời hạn access token.
│   │   │   │           │   ├── AuthService.java  # Nghiệp vụ tài khoản, mật khẩu, phát JWT và thu hồi phiên đăng nhập.
│   │   │   │           │   ├── AuthSession.java  # Entity phiên đăng nhập; theo dõi thời hạn và trạng thái thu hồi.
│   │   │   │           │   ├── LoginRateLimiter.java  # Hạn chế tần suất đăng nhập theo địa chỉ truy cập trong một tiến trình.
│   │   │   │           │   ├── SecurityConfiguration.java  # Cấu hình Spring Security, xác thực JWT/session, CORS và password encoder.
│   │   │   │           │   ├── SessionRepository.java  # Truy cập bảng phiên đăng nhập qua Spring Data JPA.
│   │   │   │           │   ├── UserAccount.java  # Entity tài khoản, email và mật khẩu đã băm.
│   │   │   │           │   └── UserRepository.java  # Truy vấn/lưu tài khoản qua Spring Data JPA.
│   │   │   │           ├── common/  # Lỗi API, trace và bộ lọc dùng chung.
│   │   │   │           │   ├── ApiError.java  # Cấu trúc response lỗi gồm mã, thông báo, traceId và thông tin liên quan.
│   │   │   │           │   ├── ApiException.java  # Exception nghiệp vụ mang HTTP status, mã lỗi và chi tiết.
│   │   │   │           │   ├── ApiExceptionHandler.java  # Chuyển exception thành response lỗi thống nhất, tránh lộ lỗi nội bộ.
│   │   │   │           │   ├── RequestBodyLimitFilter.java  # Giới hạn kích thước JSON request trước khi deserialize, kể cả đọc stream.
│   │   │   │           │   └── TraceFilter.java  # Gắn trace ID để đối chiếu request, response và log.
│   │   │   │           ├── job/  # Job phân tích, hàng đợi DB và điều phối pipeline.
│   │   │   │           │   ├── AnalysisJob.java  # Mô hình job, trạng thái, snapshot input/provider, lease và checkpoint.
│   │   │   │           │   ├── AnalysisJobController.java  # API policy AI, tạo job, đọc tiến độ, hủy và thử lại phân tích.
│   │   │   │           │   ├── AnalysisJobRunner.java  # Điều phối một lượt xử lý job: nhận quyền, chuẩn bị, gọi pipeline và kết thúc.
│   │   │   │           │   ├── AnalysisJobService.java  # Kiểm tra chủ sở hữu, version, idempotency và điều kiện tạo/hủy/retry job.
│   │   │   │           │   ├── AnalysisJobStore.java  # JDBC lưu/claim job, lease, checkpoint, chunk, log và kết quả phân tích.
│   │   │   │           │   ├── AnalysisPipeline.java  # Chạy trích xuất một lần hoặc nhiều chunk, resume, kiểm tra và hợp nhất kết quả.
│   │   │   │           │   ├── AnalysisPolicy.java  # Công bố provider khả dụng và chốt model/prompt/schema/budget khi tạo job.
│   │   │   │           │   ├── AnalysisProperties.java  # Cấu hình worker phân tích, lease, số lần thử, concurrency và chuẩn bị nguồn.
│   │   │   │           │   ├── AnalysisWorker.java  # Lịch polling nền để gọi AnalysisJobRunner; chạy ở backend.
│   │   │   │           │   ├── JobFailure.java  # Mã lỗi job, thông báo, khả năng retry và cờ lỗi một phần.
│   │   │   │           │   └── PreparationBudget.java  # Ước lượng ngân sách chuẩn bị nguồn/metadata; không phải tokenizer thật.
│   │   │   │           ├── llm/  # Adapter AI, prompt/schema và kiểm tra kết quả.
│   │   │   │           │   ├── ChunkingProperties.java  # Cấu hình bật/tắt chunking, byte mỗi phần, overlap, số phần và known tasks.
│   │   │   │           │   ├── Extraction.java  # Các record dữ liệu nguồn, sự kiện AI, evidence, candidate và kết quả tổng hợp.
│   │   │   │           │   ├── ExtractionPrompt.java  # Prompt trích xuất, quy tắc chunking và version prompt/schema theo provider.
│   │   │   │           │   ├── ExtractionValidator.java  # Kiểm tra JSON/evidence và hợp nhất CREATE/UPDATE/CANCEL thành task đề xuất.
│   │   │   │           │   ├── GeminiProvider.java  # Adapter gọi Gemini generateContent; schema trong prompt, đọc text JSON và usage.
│   │   │   │           │   ├── GeminiSchema.java  # Helper chuyển schema cho cách gọi structured cũ; hiện được test, không được GeminiProvider gọi.
│   │   │   │           │   ├── JdkLlmHttpTransport.java  # HTTP tới AI: giới hạn response, timeout, không redirect và kiểm tra lease.
│   │   │   │           │   ├── LlmHttpTransport.java  # Interface HTTP để thay transport thật bằng giả khi test.
│   │   │   │           │   ├── LlmProperties.java  # Cấu hình key/model OpenAI/Gemini, timeout và giới hạn input/output; che secret khi toString.
│   │   │   │           │   ├── LlmProvider.java  # Hợp đồng adapter trích xuất dùng chung cho các nhà cung cấp AI.
│   │   │   │           │   ├── OpenAiProvider.java  # Adapter OpenAI Responses API với đầu ra JSON theo schema và đọc usage.
│   │   │   │           │   ├── ProviderDiagnostics.java  # Rút ra nhãn chẩn đoán an toàn từ lỗi provider, không trả nguyên văn thông tin nhạy cảm.
│   │   │   │           │   └── ProviderSupport.java  # Mã hóa request, đọc response và ánh xạ lỗi HTTP provider sang lỗi job.
│   │   │   │           ├── meeting/  # Cuộc họp, phiên bản transcript và nguồn.
│   │   │   │           │   ├── Meeting.java  # Entity cuộc họp: chủ sở hữu, metadata, version và revision hiện hành.
│   │   │   │           │   ├── MeetingController.java  # API tạo meeting từ paste/file, lịch sử, đọc metadata/nguồn và thay input.
│   │   │   │           │   ├── MeetingRepository.java  # Truy cập/lọc meeting theo owner qua Spring Data JPA.
│   │   │   │           │   ├── MeetingService.java  # Lưu meeting/revision/segment, phân trang, preview và kiểm tra version khi thay nội dung.
│   │   │   │           │   ├── RevisionRepository.java  # Truy cập các phiên bản transcript qua JPA.
│   │   │   │           │   ├── SegmentRepository.java  # Truy cập các segment nguồn theo revision/thứ tự.
│   │   │   │           │   ├── TranscriptRevision.java  # Entity phiên bản transcript gốc/chuẩn hóa, cảnh báo và thời hạn lưu nguồn.
│   │   │   │           │   └── TranscriptSegment.java  # Entity đoạn nguồn, thứ tự, vị trí chuẩn hóa và source locator.
│   │   │   │           ├── privacy/  # Xóa dữ liệu và retention.
│   │   │   │           │   ├── PrivacyController.java  # API xóa transcript hoặc meeting của chủ sở hữu.
│   │   │   │           │   ├── PrivacyService.java  # Xóa/purge nguồn và bản sao dẫn chứng, kiểm tra trạng thái bận; không xóa card Trello.
│   │   │   │           │   ├── RetentionProperties.java  # Cấu hình lịch retention, thời gian gia hạn và thời hạn log/session.
│   │   │   │           │   └── RetentionWorker.java  # Tác vụ định kỳ gọi dọn dữ liệu quá hạn ở backend.
│   │   │   │           ├── sync/  # Duyệt, snapshot, tạo card và đối soát.
│   │   │   │           │   ├── ApproveService.java  # Kiểm tra điều kiện/version khi duyệt; tạo snapshot và sync job trong transaction.
│   │   │   │           │   ├── CardPayloads.java  # Dựng tên/mô tả/member/due của card và marker AI_MTT_REF từ task đã duyệt.
│   │   │   │           │   ├── Reconciler.java  # Tìm card theo marker để đối soát UNKNOWN, phát hiện trùng và kiểm tra URL card.
│   │   │   │           │   ├── SyncActions.java  # Nghiệp vụ retry, đối soát, gắn card có sẵn và tạo lại có điều kiện.
│   │   │   │           │   ├── SyncController.java  # API preview card, approve-and-sync, trạng thái job và hành động trên sync item.
│   │   │   │           │   ├── SyncErrors.java  # Ánh xạ mã lỗi đồng bộ thành thông báo cho người dùng.
│   │   │   │           │   ├── SyncItemStore.java  # JDBC quản lý từng item: claim/lease, dispatch, kết quả, retry và đối soát.
│   │   │   │           │   ├── SyncProperties.java  # Cấu hình worker tạo card, lease, backoff và giới hạn đối soát.
│   │   │   │           │   ├── SyncRunner.java  # Xử lý item từ snapshot, kiểm tra đích, gửi tạo card và phân biệt FAILED/UNKNOWN.
│   │   │   │           │   ├── SyncStore.java  # Truy vấn sync job/item cho giao diện và ghi audit đồng bộ.
│   │   │   │           │   └── SyncWorker.java  # Lịch polling nền gọi SyncRunner để xử lý/đối soát card.
│   │   │   │           ├── task/  # Task nháp, version, review và evidence.
│   │   │   │           │   ├── TaskController.java  # API danh sách task, thêm thủ công, sửa, loại bỏ, khôi phục và evidence.
│   │   │   │           │   ├── TaskRow.java  # Record dữ liệu task: version, nguồn AI/USER, người/hạn và trạng thái review/sync.
│   │   │   │           │   ├── TaskService.java  # Nghiệp vụ sửa/loại bỏ/khôi phục task, xác nhận trường dữ liệu và kiểm tra version.
│   │   │   │           │   ├── TaskStore.java  # JDBC truy cập task/evidence, khóa bản ghi và lưu task từ kết quả AI.
│   │   │   │           │   └── TaskWarnings.java  # Tính cảnh báo theo trường và các điều kiện chặn duyệt task.
│   │   │   │           ├── transcript/  # Đọc và chuẩn hóa văn bản/file đầu vào.
│   │   │   │           │   ├── InputProperties.java  # Giới hạn file, văn bản, giải nén DOCX và thời hạn nguồn.
│   │   │   │           │   ├── ParsedTranscript.java  # Kết quả parser gồm văn bản gốc/chuẩn hóa, segment và cảnh báo.
│   │   │   │           │   └── TranscriptParser.java  # Đọc paste/TXT/DOCX, kiểm tra đầu vào, chuẩn hóa và tạo ánh xạ nguồn.
│   │   │   │           ├── trello/  # Kết nối/API Trello, Board/List, thành viên và hạn.
│   │   │   │           │   ├── DeadlineSuggester.java  # Gợi ý ngày hạn từ cụm từ và ngày cuộc họp; người dùng vẫn phải xác nhận.
│   │   │   │           │   ├── DestinationService.java  # Quản lý Board/List có version, thành viên/alias và gợi ý người/hạn cho task.
│   │   │   │           │   ├── MemberMatcher.java  # Chuẩn hóa tên, so khớp thành viên và phân biệt gợi ý/trùng tên/không thấy.
│   │   │   │           │   ├── TokenCipher.java  # Mã hóa và giải mã token Trello bằng AES-GCM với khóa cấu hình ở backend.
│   │   │   │           │   ├── TrelloClient.java  # HTTP client đọc tài khoản/Board/List/member/card và gọi tạo card Trello.
│   │   │   │           │   ├── TrelloConnectionService.java  # OAuth state/PKCE, kết nối bằng token, refresh, lưu mã hóa và ngắt kết nối.
│   │   │   │           │   ├── TrelloController.java  # API kết nối/callback Trello, Board/List/member, destination và resolve người/hạn.
│   │   │   │           │   └── TrelloProperties.java  # Cấu hình endpoint, OAuth/API key, mã hóa, timeout và chế độ HTTP local cho demo.
│   │   │   │           └── MeetingToTaskApplication.java  # Điểm vào main khởi động Spring Boot và các thành phần ứng dụng.
│   │   │   └── resources/  # Cấu hình, migration và schema đóng gói cùng backend.
│   │   │       ├── db/
│   │   │       │   └── migration/
│   │   │       │       ├── V1__backend_foundation.sql  # Tạo schema nền tảng: tài khoản/session, meeting, transcript revision/segment.
│   │   │       │       ├── V2__analysis_jobs.sql  # Thêm job phân tích, checkpoint và ràng buộc hàng đợi.
│   │   │       │       ├── V3__analysis_results.sql  # Thêm nơi lưu kết quả phân tích AI.
│   │   │       │       ├── V4__review_tasks.sql  # Thêm task/evidence/version phục vụ review và backfill từ kết quả cũ.
│   │   │       │       ├── V5__trello_sync.sql  # Thêm kết nối, destination, alias, snapshot, sync job/item/attempt và audit.
│   │   │       │       └── V6__chunks_logs_retention.sql  # Thêm checkpoint chunk, processing log và trường phục vụ purge/retention.
│   │   │       ├── llm/
│   │   │       │   └── meeting-events-v1.schema.json  # JSON Schema chuẩn cho sự kiện trích xuất CREATE/UPDATE/CANCEL.
│   │   │       └── application.yml  # Cấu hình Spring, DB, AI, worker, Trello, retention và ánh xạ biến môi trường.
│   │   └── test/  # Mã kiểm thử; không phải thành phần runtime sản phẩm.
│   │       └── java/
│   │           └── vn/
│   │               └── aimtt/
│   │                   ├── auth/
│   │                   │   ├── AuthServiceTest.java  # Unit test đăng nhập/session, JWT và nghiệp vụ xác thực.
│   │                   │   └── LoginRateLimiterTest.java  # Test ngưỡng và cửa sổ thời gian giới hạn đăng nhập.
│   │                   ├── common/
│   │                   │   └── RequestBodyLimitFilterTest.java  # Test chặn body quá lớn khi có/không có Content-Length.
│   │                   ├── e2e/
│   │                   │   └── E2eServer.java  # Backend dành riêng cho E2E, thay AI bằng bộ trả lời theo quy tắc; nằm trong test classpath.
│   │                   ├── job/
│   │                   │   ├── AnalysisConfigurationTest.java  # Test binding/validation cấu hình phân tích và khởi tạo thành phần.
│   │                   │   ├── AnalysisJobRunnerTest.java  # Test chuyển trạng thái, lease, hủy và lỗi trong worker phân tích.
│   │                   │   ├── AnalysisJobServiceTest.java  # Test quy tắc tạo/hủy/retry, owner, version và idempotency của job.
│   │                   │   ├── ChunkPlanTest.java  # Test chia segment theo ngân sách, overlap và khử trùng sự kiện giữa các phần.
│   │                   │   └── JobFixture.java  # Dữ liệu job giả dùng chung trong unit tests.
│   │                   ├── llm/
│   │                   │   ├── AnalysisPipelineTest.java  # Test điều phối gọi AI, ngân sách/lease và kiểm tra kết quả qua pipeline.
│   │                   │   ├── ExtractionValidatorTest.java  # Test JSON/evidence, sửa/hủy task, trường thiếu và chuẩn hóa hạn.
│   │                   │   ├── GeminiCompatibilityTest.java  # Test helper schema cũ và nhãn chẩn đoán lỗi Gemini an toàn.
│   │                   │   ├── JdkTransportTest.java  # Test timeout, giới hạn response và kiểm tra lease của HTTP transport AI.
│   │                   │   ├── LlmConfigurationTest.java  # Test khởi tạo adapter/policy, cấu hình model/budget và che secret.
│   │                   │   ├── LlmFixtures.java  # Nguồn, job và response AI giả dùng chung cho tests.
│   │                   │   └── ProviderTest.java  # Test payload/header/model của hai adapter và xử lý lỗi; dùng HTTP giả.
│   │                   ├── sync/
│   │                   │   └── SyncRulesTest.java  # Test payload card, marker và các quy tắc đồng bộ/đối soát.
│   │                   ├── task/
│   │                   │   └── TaskRulesTest.java  # Test cảnh báo và quy tắc trường dữ liệu/task review.
│   │                   ├── transcript/
│   │                   │   └── TranscriptParserTest.java  # Test parser paste/TXT/DOCX, chuẩn hóa, giới hạn và ánh xạ nguồn.
│   │                   ├── trello/
│   │                   │   └── TrelloRulesTest.java  # Test so khớp tên, gợi ý hạn và mã hóa token Trello.
│   │                   ├── AnalysisJobIntegrationTest.java  # Test DB/API job: version, enqueue, quyền, cancel, lease và recovery.
│   │                   ├── ChunkingIntegrationTest.java  # Test nhiều chunk, sửa/hủy xa, lỗi một phần và resume trên DB.
│   │                   ├── FakeTrello.java  # HTTP Trello giả trong JVM dùng cho integration tests, không kết nối Trello thật.
│   │                   ├── FoundationIntegrationTest.java  # Test auth, nhập transcript, quyền owner, history, CORS và xung đột input với DB.
│   │                   ├── IntegrationSupport.java  # Tiện ích nền dùng chung cho integration tests: tài khoản, request và dữ liệu thử.
│   │                   ├── LlmIntegrationTest.java  # Test pipeline, lưu task/kết quả và API qua DB với transport AI giả.
│   │                   ├── ReliabilityPrivacyIntegrationTest.java  # Test purge, xóa dữ liệu, log, prompt injection và không fallback provider.
│   │                   ├── TaskReviewIntegrationTest.java  # Test task review qua API/DB: sửa, version, quyền, manual, reject và restore.
│   │                   ├── TestDatabase.java  # Khởi tạo PostgreSQL test hoặc nhận cấu hình DB test bên ngoài.
│   │                   └── TrelloSyncIntegrationTest.java  # Test duyệt/tạo card, idempotency, UNKNOWN và đối soát với Trello giả.
│   ├── .dockerignore  # Loại file không cần thiết khỏi build context Docker của backend.
│   ├── Dockerfile  # Build JAR và tạo container Java chạy backend; không chạy tests trong bước package này.
│   ├── mvnw  # Maven Wrapper dành cho macOS/Linux, không cần cài Maven riêng.
│   ├── mvnw.cmd  # Maven Wrapper dành cho Windows.
│   └── pom.xml  # Khai báo Spring Boot, thư viện, Java, plugin build và dependency kiểm thử.
├── docs/  # Tài liệu thiết kế, triển khai, kiểm thử và báo cáo.
│   └── ai-meeting-to-task/
│       ├── AI_Meeting_to_Task_SDS_v3.docx  # Bản Word đặc tả thiết kế hệ thống SDS v3.
│       ├── Analysis_Job_Test_Guide.md  # Checklist job phân tích, polling, cancel, retry và restart.
│       ├── API_Test_Guide.md  # Hướng dẫn kiểm thử API, request/response và các tình huống lỗi/quyền.
│       ├── Bao_Cao_Tien_Do_2026-10-10.md  # Báo cáo cho giảng viên: quá trình làm, kiến trúc, luồng, kết quả và hạn chế.
│       ├── Demo_Script.md  # Kịch bản trình diễn với Trello giả: thành công, trùng tên và UNKNOWN.
│       ├── Directory_Tree.md  # Tài liệu này: cây thư mục có chú thích từng file và bản đồ đọc code.
│       ├── Evaluation_Guide.md  # Hướng dẫn chạy dataset AI, tính chỉ số và thử nghiệm usability.
│       ├── Extension_Setup.md  # Hướng dẫn build/load extension, cấu hình origin và kết nối backend.
│       ├── Full_Test_Guide.md  # Hướng dẫn kiểm thử tổng thể các chặng Trello, chunking, privacy và demo.
│       ├── Implementation_Progress.md  # Nhật ký triển khai, kết quả kiểm thử và phần chưa xác minh theo từng bước.
│       ├── LLM_Setup.md  # Cấu hình OpenAI/Gemini và lịch sử chẩn đoán lỗi gọi AI.
│       ├── Real_Trello_Test.md  # Các bước kiểm thử tạo card trên Board Trello thật.
│       ├── Review_Test_Guide.md  # Checklist sửa task, autosave, evidence, xung đột và thao tác review.
│       ├── SDS_v3_Content.md  # Nội dung đặc tả SDS v3 dạng Markdown để tra cứu trong repository.
│       ├── SDS_v3_Context.md  # Bối cảnh bàn giao: quyết định thiết kế, tiến độ và việc cần làm tiếp.
│       └── Trello_Setup.md  # Hướng dẫn cấu hình kết nối Trello, token/OAuth và khóa mã hóa.
├── evaluation/  # Dataset và công cụ đo chất lượng AI/usability; không phải model được huấn luyện.
│   ├── dataset/
│   │   └── seed_v1.json  # Dataset tổng hợp 14 transcript chia dev/test, đáp án và chính sách diễn giải.
│   ├── tests/
│   │   ├── test_evaluate.py  # Test phép ghép/chấm task và tính chỉ số đánh giá.
│   │   └── test_usability.py  # Test phép tổng hợp số liệu usability.
│   ├── usability/
│   │   ├── sessions_template.csv  # Mẫu ghi thời gian, độ đúng và thao tác khi so sánh thủ công với extension.
│   │   └── usability_report.py  # Tổng hợp CSV thử nghiệm người dùng; so sánh hai điều kiện và kết quả theo cặp.
│   ├── eval_lib.py  # Hàm hỗ trợ đọc dataset, sinh transcript, ghép task và so sánh trường dữ liệu.
│   ├── evaluate.py  # Chấm dự đoán theo rubric; xuất báo cáo precision/recall/F1 và các chỉ số liên quan.
│   └── run_eval.py  # Tạo meeting/job qua backend để thu dự đoán AI trên dataset; có thể phát sinh phí API.
├── extension/  # Chrome Extension MV3 — giao diện React Side Panel và HTTP client.
│   ├── scripts/
│   │   └── check-build.mjs  # Kiểm tra artifact MV3 sau build, manifest/quyền/CSP và cấu hình backend.
│   ├── src/  # Mã nguồn giao diện và logic extension.
│   │   ├── AnalysisPanel.jsx  # Chọn provider, tạo/theo dõi/hủy/retry job và chuyển sang phần review.
│   │   ├── api.mjs  # HTTP client gọi backend: Bearer token, timeout, lỗi và các endpoint nghiệp vụ.
│   │   ├── App.jsx  # Điều phối màn hình: phiên đăng nhập, meeting, nhập liệu, lịch sử và trạng thái ứng dụng.
│   │   ├── components.jsx  # Thành phần dùng chung: đăng nhập, nhập nội dung, preview/nguồn, history và thông báo lỗi.
│   │   ├── config.mjs  # Kiểm tra backend origin và tạo nội dung manifest MV3/quyền/CSP theo origin.
│   │   ├── input.mjs  # Kiểm tra input phía client và dựng payload paste/file/thông tin đăng nhập.
│   │   ├── jobs.mjs  # Polling job với backoff, dừng đúng lúc và kiểm tra job thuộc đúng meeting/revision.
│   │   ├── main.jsx  # Điểm vào React, mount App và error boundary của panel.
│   │   ├── meeting.mjs  # Mở lại meeting đã lưu, kiểm tra revision và xử lý con trỏ khi nguồn không còn.
│   │   ├── review.mjs  # Logic review tách khỏi UI: draft, PATCH/version, hạn, điều kiện tạo card và hàng đợi autosave.
│   │   ├── ReviewPanel.jsx  # Danh sách/editor task, evidence, manual task, xác nhận người/hạn và duyệt tạo card.
│   │   ├── service-worker.mjs  # Worker Chrome mở Side Panel và giới hạn truy cập session storage; không xử lý AI/job backend.
│   │   ├── storage.mjs  # Quản lý JWT trong session storage và meeting ID theo account/backend trong local storage.
│   │   ├── styles.css  # Bố cục và kiểu giao diện Side Panel, form, task, cảnh báo và các khối chức năng.
│   │   ├── SyncPanel.jsx  # Theo dõi tạo card; hiển thị thành công/lỗi/UNKNOWN và các hành động xử lý.
│   │   ├── theme.css  # Biến màu/chủ đề dùng chung của giao diện.
│   │   └── TrelloPanel.jsx  # Giao diện kết nối Trello và chọn/lưu Board/List đích.
│   ├── tests/  # Kiểm thử logic và render UI bằng Node.
│   │   ├── api.test.mjs  # Test HTTP client, auth, timeout, payload và không tự lặp lệnh ghi.
│   │   ├── input.test.mjs  # Test giới hạn input, metadata, credential và cấu hình manifest/origin.
│   │   ├── jobs.test.mjs  # Test polling, backoff, terminal state và bỏ response cũ/sai snapshot.
│   │   ├── meeting.test.mjs  # Test khôi phục meeting từ backend và xử lý revision/owner/pointer.
│   │   ├── review.test.mjs  # Test payload sửa task, hạn, gate tạo card và autosave/version conflict.
│   │   ├── storage.test.mjs  # Test session JWT, account switching, expiry và con trỏ meeting được phân phạm vi.
│   │   ├── ui-entry.mjs  # Export các component làm đầu vào bundle kiểm thử render UI.
│   │   └── ui.test.mjs  # Test render an toàn, cảnh báo, trạng thái job/review/Trello và nút thao tác.
│   ├── package-lock.json  # Khóa chính xác dependency npm để cài đặt có thể tái lập bằng npm ci.
│   ├── package.json  # Phiên bản extension, React/Vite, yêu cầu Node và lệnh test/build/check.
│   ├── sidepanel.html  # HTML đầu vào của Side Panel, nạp ứng dụng React.
│   └── vite.config.mjs  # Cấu hình build panel/worker, nhúng API origin và sinh manifest.json.
├── tools/  # Công cụ demo, giả lập dịch vụ và kiểm thử xuyên hệ thống.
│   ├── e2e/  # Kiểm thử luồng xuyên extension/backend/DB với dịch vụ giả.
│   │   ├── build_extension.mjs  # Bundle extension phục vụ E2E bằng esbuild và sinh manifest.
│   │   ├── e2e_flow.py  # Playwright chạy luồng browser: nhập, review, kết nối, tạo card, UNKNOWN và đối soát.
│   │   └── README.md  # Hướng dẫn dựng môi trường và chạy E2E bằng Chromium, backend, DB và Trello/AI giả.
│   ├── tests/
│   │   └── test_fake_trello.py  # Test hành vi server Trello giả và các tình huống hỗ trợ kiểm thử.
│   └── fake_trello.py  # Server Trello giả độc lập: Board/card, OAuth giả và bơm lỗi cho demo/test.
├── .gitattributes  # Quy định thuộc tính file và chuẩn hóa khi Git xử lý file.
├── .gitignore  # Loại trừ secret, dependency, build output và dữ liệu tạm khỏi Git.
├── compose.yaml  # Chạy PostgreSQL local bằng Docker Compose, cấu hình volume và healthcheck.
└── README.md  # Giới thiệu, khởi động, API, kiến trúc và trạng thái kiểm thử toàn dự án.
```

## 3. File cục bộ và thư mục sinh tự động

Những mục dưới đây không thuộc danh sách file nguồn ở trên. Một số chỉ xuất hiện sau khi cài dependency/chạy build/test; không cần tự viết hoặc sửa trực tiếp nội dung build.

| Đường dẫn | Công dụng |
| --- | --- |
| `.env` | Cấu hình local như DB/JWT/AI/Trello; có secret, không commit hoặc đưa vào báo cáo. Maven không tự nạp file này. |
| `.git/` | Lịch sử commit, branch và metadata Git; không phải code ứng dụng. |
| `.local/` | Cache, log và công cụ tạm phục vụ môi trường phát triển. |
| `backend/target/` | JAR, bytecode Java, tài nguyên đã copy và kết quả Maven. |
| `backend/target/classes/` | Class/tài nguyên runtime đã biên dịch. |
| `backend/target/test-classes/` | Class test đã biên dịch, gồm backend giả lập AI dành cho E2E. |
| `backend/target/surefire-reports/` | Báo cáo test JUnit/XML/text; kiểm tra ngày/commit vì có thể còn report cũ. |
| `extension/node_modules/` | Thư viện npm cài từ package-lock; không phải mã do dự án tự viết. |
| `extension/dist/` | Extension đã build để Load unpacked trong Chrome. |
| `extension/dist/manifest.json` | Manifest MV3 sinh bởi Vite từ `src/config.mjs`; khai báo quyền, worker và panel. |
| `extension/dist/sidepanel.html` | HTML panel sau build. |
| `extension/dist/service-worker.js` | Worker Chrome sau bundle. |
| `extension/dist/assets/` | JS/CSS đã bundle, tên có hash và đổi giữa các lần build. |
| `evaluation/out/` | Đầu ra dự đoán/báo cáo khi chạy evaluation; không nhầm với dataset đáp án. |
| `__pycache__/` | Bytecode/cache Python sinh khi chạy script, có thể xuất hiện ở nhiều thư mục. |

Không có file `manifest.json` viết tay ở gốc `extension/`: source của manifest là `extensionManifest()` trong `config.mjs`, được plugin trong `vite.config.mjs` xuất khi build.

## 4. Đọc tên file backend như thế nào?

| Quy ước | Vai trò | Ví dụ |
| --- | --- | --- |
| `*Controller` | Nhận HTTP request, đọc thông tin người dùng và gọi nghiệp vụ | `TaskController.java` |
| `*Service` | Điều kiện nghiệp vụ, phân quyền, transaction và quy tắc thao tác | `TaskService.java` |
| `*Repository` | Truy cập entity bằng Spring Data JPA | `MeetingRepository.java` |
| `*Store` | Truy cập DB bằng JDBC/SQL, gồm khóa/claim/version | `AnalysisJobStore.java` |
| `*Properties` | Nhóm cấu hình từ application.yml/biến môi trường | `LlmProperties.java` |
| `*Worker` | Bộ lịch kích hoạt công việc nền | `SyncWorker.java` |
| `*Runner` | Thực hiện một lượt công việc nền | `SyncRunner.java` |
| `*Provider` | Adapter giao tiếp nhà cung cấp AI | `GeminiProvider.java` |
| `*Test` | Kiểm thử hành vi; không phải nghiệp vụ runtime | `ProviderTest.java` |
| `*Fixture` / `*Fixtures` | Dữ liệu giả dùng chung cho tests | `LlmFixtures.java` |
| `Vn__*.sql` | Migration Flyway có thứ tự phiên bản | `V5__trello_sync.sql` |

Luồng Controller → Service → Repository/Store là cách phân lớp chính; không phải mọi module đều có đủ ba loại file hoặc đi qua đúng một chuỗi giống nhau.

## 5. Bản đồ file theo luồng chạy

### Mở extension và đăng nhập

`sidepanel.html → main.jsx → App.jsx → components.jsx → api.mjs → AuthController → AuthService → UserRepository/SessionRepository`.

`service-worker.mjs` thiết lập mở panel khi bấm biểu tượng. `storage.mjs` quản lý phiên và con trỏ meeting phía trình duyệt. Worker Chrome này khác hoàn toàn worker phân tích trong backend.

### Nhập transcript

`components.jsx + input.mjs → api.mjs → MeetingController → TranscriptParser → MeetingService → Meeting/Revision/SegmentRepository`.

Kết quả gồm preview và các segment có vị trí nguồn; chưa có việc gọi AI ở thao tác lưu input.

### Phân tích AI

`AnalysisPanel.jsx → api.mjs → AnalysisJobController → AnalysisJobService → AnalysisJobStore` tạo job.

Sau đó `AnalysisWorker → AnalysisJobRunner → AnalysisPipeline → OpenAiProvider/GeminiProvider → JdkLlmHttpTransport` gọi AI. `ExtractionValidator` kiểm tra/tổng hợp, kết quả/task được lưu qua lớp store. `jobs.mjs` đọc tiến độ để UI cập nhật.

### Review task

`ReviewPanel.jsx + review.mjs → api.mjs → TaskController → TaskService → TaskStore`.

`TaskWarnings` tính cảnh báo; version đi cùng request sửa. Task thủ công cũng đi qua nghiệp vụ task, không cần AI sinh ra.

### Kết nối và chọn Trello

`TrelloPanel.jsx → api.mjs → TrelloController → TrelloConnectionService/DestinationService → TrelloClient`.

`TokenCipher` xử lý mã hóa token. `MemberMatcher` gợi ý thành viên; `DeadlineSuggester` gợi ý hạn. Người dùng xác nhận trước khi tạo card.

### Tạo card và xử lý lỗi

`ReviewPanel.jsx → SyncController → ApproveService → snapshot + sync job/item trong PostgreSQL`.

`SyncWorker → SyncRunner → TrelloClient` thực hiện gửi. `Reconciler` tìm lại card khi UNKNOWN. `SyncPanel.jsx` hiển thị kết quả và các thao tác qua `SyncActions`.

### Xóa nguồn/dọn dữ liệu

Thao tác người dùng đi qua `PrivacyController → PrivacyService`. Dọn theo lịch đi qua `RetentionWorker → PrivacyService`.

## 6. Các điểm dễ nhầm khi thuyết trình

- `extension/` chứa UI và logic tương tác; không chứa API key AI hay pipeline phân tích nặng.
- `backend/src/main/` là code sản phẩm; `backend/src/test/`, `extension/tests/` và `tools/e2e/` phục vụ kiểm thử.
- `FakeTrello.java` là Trello giả trong integration tests; `tools/fake_trello.py` là server giả độc lập cho demo/E2E. Cả hai đều không phải Trello thật.
- `E2eServer.java` thay AI bằng phản hồi theo quy tắc; test E2E đạt không chứng minh độ chính xác model thật.
- `evaluation/` đánh giá mô hình/API có sẵn; không phải nơi huấn luyện AI riêng.
- `GeminiSchema.java` còn tồn tại từ cách gọi structured cũ, hiện chỉ có tham chiếu trong test; adapter Gemini hiện đưa schema vào prompt.
- Chưa có module ghi âm/STT trong cây hiện tại; input là transcript văn bản/TXT/DOCX.
- `dist/` và `target/` là kết quả build. Sửa source rồi build lại, không coi sửa artifact là sửa mã nguồn bền vững.

## 7. Thứ tự đọc code gợi ý

1. `README.md` và `docs/ai-meeting-to-task/Bao_Cao_Tien_Do_2026-10-10.md`: hiểu bài toán và phạm vi.
2. `extension/src/App.jsx`, `components.jsx`, `AnalysisPanel.jsx`, `ReviewPanel.jsx`: hiểu trải nghiệm người dùng.
3. `meeting/MeetingController.java`, `MeetingService.java`, `transcript/TranscriptParser.java`: hiểu cách đưa dữ liệu vào.
4. `job/AnalysisPipeline.java`, `llm/ExtractionPrompt.java`, `ExtractionValidator.java`: hiểu cách biến transcript thành task.
5. `task/TaskService.java`, `TaskWarnings.java`: hiểu review và version.
6. `sync/ApproveService.java`, `SyncRunner.java`, `Reconciler.java`: hiểu tạo card và xử lý kết quả chưa rõ.
7. `db/migration/V1…V6` và các integration tests: đối chiếu dữ liệu và tình huống kiểm thử.

Trong các bước 3–6, đường dẫn package tính từ `backend/src/main/java/vn/aimtt/`; migration tính từ `backend/src/main/resources/`.
