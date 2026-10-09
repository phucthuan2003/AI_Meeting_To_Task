# Test toàn bộ 14.5–14.8

Cập nhật 10/10/2026 · backend Flyway **V1–V6** · extension **0.5.0**. Chạy từ thư mục gốc `Code`. Chỉ dùng DB test rỗng và Trello giả hoặc Board thử nghiệm.

## 1 Tự động

| Bộ | Lệnh | Kết quả mong đợi |
| --- | --- | --- |
| Backend (unit + integration, PostgreSQL 16 nhúng) | `cd backend && ./mvnw verify` | `Tests run: 134, Failures: 0, Errors: 0` · `BUILD SUCCESS` |
| Extension (logic + React render + build MV3) | `cd extension && npm ci && npm run check` | `pass 61` rồi Vite build + kiểm tra manifest đạt |
| Trello giả | `python3 -m unittest discover -s tools/tests` | `Ran 2 tests … OK` |
| Script đánh giá | `python3 -m unittest discover -s evaluation/tests` | `Ran 6 tests … OK` |
| Browser E2E (tùy chọn) | xem [tools/e2e/README.md](../../tools/e2e/README.md) | `E2E PASS`, `console errors: []` |

Chạy `mvnw` bằng user thường (không root). Integration test dùng một HTTP Trello giả ngay trong JVM, nên không cần mạng.

## 2 Thủ công trên Chrome thật

Trước khi test thủ công:

1. Khởi động lại backend để Flyway áp dụng V5/V6.
2. Đặt `TOKEN_ENCRYPTION_KEY` (xem [Trello Setup](Trello_Setup.md)).
3. Chạy `npm run check`, rồi Reload `extension/dist`. Footer phải hiện **Bản 0.5**.
4. Dùng Trello giả theo [Demo Script](Demo_Script.md), hoặc Board thử nghiệm thật.

| # | Bước | Mong đợi |
| --- | --- | --- |
| 1 | Kết nối Trello (OAuth hoặc token) | Hiện "Đã kết nối: …". Extension không hiển thị hay lưu token. |
| 2 | Chọn Board/List → Lưu | Hiện "Nơi tạo card: Board › List". |
| 3 | Đối chiếu người phụ trách | Mai được *gợi ý*. Long hiện *nhiều thành viên trùng tên* và nút Tạo bị khóa kèm lý do. |
| 4 | Gợi ý hạn → Dùng gợi ý | "Hạn sẽ dùng: Thứ Sáu, 09/10/2026 lúc 17:00 (Asia/Ho_Chi_Minh)". |
| 5 | Tạo N card → Xem trước → Xác nhận | Hiện "Đã tạo xong". Mở card: mô tả có `AI_MTT_REF=`, đúng member, đúng hạn. |
| 6 | Bấm Tạo 2 lần nhanh hoặc mở 2 tab | Chỉ có một job và không sinh card trùng. |
| 7 | Bật fault `timeout_after_create` → tạo 1 card | Hiện **Chưa rõ kết quả**, không có Thử lại. Bấm Đối soát → Đã tạo, không có card trùng. |
| 8 | Bật fault `reject` → tạo | Hiện Lỗi, sửa task được. Lưu task xong thì task về chờ duyệt và tạo lại được. |
| 9 | `POST /__admin/revoke` → tạo | Yêu cầu kết nối lại, bản nháp còn nguyên. Sau `restore-token` + Thử lại → Đã tạo. |
| 10 | Đổi Board khi đã mapping | Member không thuộc Board mới bị bỏ và phải chọn lại. Nếu còn card đang tạo, đổi Board bị chặn. |
| 11 | Đóng/mở lại panel, đăng xuất/đăng nhập | Kết quả tạo card và nút Mở card vẫn còn. |
| 12 | Xóa transcript gốc / Xóa meeting | Đang phân tích thì bị chặn (409). Sau khi xóa, card trên Trello vẫn còn. |
| 13 | Transcript dài với `CHUNKING_ENABLED=true` | Tiến độ hiện số phần. Sửa/hủy ở phần sau phải được áp dụng. |
| 14 | Xem log backend | Không có transcript, token hay API key. Chỉ có metadata (job, phần, token, latency, mã lỗi). |

## 3 Đối chiếu acceptance test (SDS 9.6)

| AT | Nơi kiểm tra |
| --- | --- |
| 01–04, 14–16 | Các chặng 14.1–14.4: FoundationIntegrationTest, AnalysisJobIntegrationTest, TranscriptParserTest, Analysis_Job/Review guide |
| 05, 06 | TrelloSyncIntegrationTest (member resolution), TrelloRulesTest, E2E |
| 07, 08 | TrelloRulesTest (DeadlineSuggester), review.test.mjs, E2E (hạn 17:00 kèm múi giờ) |
| 09, 10 | ExtractionValidatorTest (14.3) |
| 11–13 | ChunkingIntegrationTest |
| 17 | TaskReviewIntegrationTest, ui.test.mjs (autosave khi xung đột) |
| 18–27 | TrelloSyncIntegrationTest, SyncRulesTest, E2E (UNKNOWN → đối soát) |
| 28–30 | ReliabilityPrivacyIntegrationTest |

## 4 Chưa thể kiểm chứng tự động

Các mục dưới đây chỉ kiểm chứng được trên máy của bạn:

- Trello thật: OAuth Atlassian và chế độ token.
- AI thật trên dataset (xem [Evaluation Guide](Evaluation_Guide.md)).
- Usability với người dùng.
- `npm run check` (Vite build) trên macOS.
- Migration V5/V6 trên DB đang dùng.
