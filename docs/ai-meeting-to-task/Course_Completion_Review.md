# Đánh giá mức độ hoàn thiện AI Meeting to Task

Ngày đối chiếu 06/11/2026. Mục tiêu chính là chuyển nội dung cuộc họp thành đề xuất công việc có bằng chứng để người dùng xác nhận trước khi tạo card. Thực nghiệm ASR đánh giá ảnh hưởng của cách cắt audio. Thực nghiệm LLM đánh giá phần trích xuất từ transcript có sẵn. Hai phép đo có dữ liệu và điều kiện khác nhau.

## Đối chiếu theo trọng số

| Tiêu chí | Mức độ hiện tại | Bằng chứng và khoảng trống |
|---|---|---|
| Thực nghiệm · 20% | Có đối chứng và phân tích lỗi | 24 clip FLEURS sạch, 4 biến thể nhiễu ghép cặp, 3 chính sách cùng Whisper Base. Thêm 14 transcript tổng hợp qua Spring và Gemini thật. Chưa có hội thoại tiếng Việt nhiều người kèm nhãn task và timestamp theo từ, đo GPU, throughput nhiều phiên hay đối chứng audio đến task. |
| Hoàn thiện · 15% | Luồng chính đã triển khai, nghiệm thu còn mở | Có nhập liệu, STT, job, PostgreSQL, evidence, duyệt và Trello. Có xác thực, kiểm tra owner, giới hạn payload, schema, version và idempotency. REST Java–Uvicorn và WebSocket riêng đã chạy. Bộ backend trước đó đạt 151/154. Cần tập demo trên đúng máy và xác minh Board thử. |
| Công nghệ mới · 10% | Đã có công nghệ và lý do lựa chọn | CTranslate2/FastAPI cho STT local, Spring/PostgreSQL cho luồng có transaction và checkpoint, SSE/WebSocket cho hai giao diện riêng, LLM cho structured output, Compose cho triển khai. Còn thiếu dữ liệu về tải nhiều phiên và chi phí vận hành đầy đủ. |
| Ý tưởng · 5% | Bài toán thực tế, có cải tiến đã đo | Duyệt task có evidence giảm rủi ro ghi nhầm. Đối chứng cho thấy lợi ích của VAD trong tập thử và kết quả âm của giải mã lại tại biên. Kết luận giới hạn trong thiết kế và tập đã dùng. |
| Báo cáo · 30% | Đã chuẩn bị đủ ba tài liệu | Project Charter 3 trang, SDS 6 trang, Final Report 8 trang hai cột trên mẫu IEEE Conference. Số nhóm, tên, đơn vị và người phụ trách để trống theo yêu cầu. |
| Trình bày · 20% | Đã chuẩn bị slide và kịch bản | 12 slide có ghi chú, hai biểu đồ chỉnh sửa được, 20 phút trình bày, 5 phút demo, 5 phút hỏi đáp theo mục 4. Sơ đồ đầu tài liệu ghi khoảng 10 phút cho báo cáo, nên cần xác nhận thời lượng nếu giảng viên áp dụng mốc khác. |

## Kết quả thực nghiệm

**ASR.** WER sạch của cố định, VAD thích nghi và VAD kèm sửa biên lần lượt là **64,20%, 51,48%, 52,66%**. P95 phát delta mô phỏng là **2,93 s, 1,83 s, 4,60 s**. Cấu hình VAD giảm 92 lượt suy luận và tốt hơn ở 18/24 clip, nhưng WER tuyệt đối vẫn cao. Giải mã lại tăng chi phí và chưa cho hiệu quả so với VAD đơn thuần. Hai chính sách thử nghiệm chạy trong replay, luồng production vẫn dùng baseline. [Cách tái lập](../../meeting-intelligence-service/README.md#thực-nghiệm-đối-chứng-chính-sách-cắt) · [CSV đối chứng](../../meeting-intelligence-service/benchmarks/results/course-ablation/comparison.csv)

**Action item.** Gemini 3.5 Flash-Lite chạy qua backend với 14 transcript tổng hợp seed-v1. 13 job hoàn tất, một ca hủy việc bị từ chối. F1 task là 100% trên các job hoàn tất và 96,77% nếu tính ca lỗi là đầu ra rỗng. Đúng người 12/14, hạn chuẩn hóa 1/7. Provider latency p50 1,177 giây và p95 2,177 giây trên 13 job. Backend ghi nhận 19.425 input và 3.322 output token. Quy đổi theo Standard khoảng 0,01413 USD cho phần đã ghi nhận, chưa tính thinking tokens hoặc job lỗi, không phải số tiền hóa đơn. [Kết quả chấm](../../meeting-intelligence-service/benchmarks/results/course-ablation/action_metrics.md) · [Bảng giá Gemini](https://ai.google.dev/gemini-api/docs/pricing)

## Phần cần điều chỉnh theo thứ tự ưu tiên

1. **Trước buổi bảo vệ.** Điền thông tin nhóm, thay X trong tên thư mục/tệp, tập demo 5 phút với tài khoản, Board thử và một ca dữ liệu mơ hồ. Ưu tiên sửa ca deadline và kiểm tra các ca đồng bộ Trello không ổn định. Số liệu 96,77% là F1 tìm task trên tập tổng hợp, còn độ đúng hạn và người có mẫu số khác.
2. **Trước khi kết luận về hiệu quả doanh nghiệp.** Thu audio có đồng ý và nhãn người nói, task, người nhận, thời hạn và vị trí từ. Chạy transcript chuẩn và ba luồng STT qua cùng bộ trích xuất. Báo cáo F1 đúng cả task lẫn người, lỗi quanh biên, độ trễ qua UI và tải nhiều phiên.
3. **Trước khi mở rộng triển khai.** Đo RTF, VRAM và throughput trên máy NVIDIA, kiểm tra độ ổn định giữa phiên. Thử VAD thích nghi ở luồng WebSocket có kiểm soát. Cải tiến quy tắc chấp nhận thay từ trước khi mở lại nhánh giải mã lại.
4. **Trước khi dùng với họp mật hoặc tự gán người.** Bổ sung nguồn danh tính theo lượt nói và kiểm tra các câu tự nhận việc. Chế độ khép kín cần Local LLM, kiểm tra egress và đích quản lý việc nội bộ. PII masking là lớp giảm lộ danh tính khi có sử dụng cloud.

## Hồ sơ và quy định nộp

Bốn tệp nằm trong [Nhom_X](../../output/pdf/Nhom_X). Hạn bản mềm là **20:00 thứ Sáu 13/11/2026** qua thư mục Teams của nhóm. Bản cứng Final Report đóng bìa xanh nộp **14/11/2026 trước giờ bảo vệ** theo tài liệu. Lịch ghi nhóm 1–4 ngày 31/10, nhóm 5–8 ngày 07/11 và nhóm 9–12 ngày 14/11/2026. Cần đối chiếu lịch theo số nhóm thực tế.
