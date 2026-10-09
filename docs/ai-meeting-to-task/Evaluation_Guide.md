# Đánh giá AI và usability (SDS 9.2–9.5, 14.8)

Cập nhật 10/10/2026. Mọi script chỉ dùng Python 3 stdlib, chạy từ thư mục gốc `Code`.

## 1 Dataset

`evaluation/dataset/seed_v1.json` (phiên bản `seed-v1`) gồm 14 transcript **tổng hợp** (không có dữ liệu thật), chia `dev` (7) và `test` (7). Mỗi item có `tasks` ground truth (từ khóa rubric, assignee, `deadline_raw`, `deadline_local`, priority, dòng bằng chứng) và `cancelled` nếu có task bị hủy.

| Nhóm tình huống | Item |
| --- | --- |
| Một task / nhiều task | vi-01-single, en-01-basic, vi-11-self-commitment |
| Không có task, ý tưởng, câu hỏi | vi-02-no-task, vi-09-idea-not-task |
| Sửa lời, hủy task | vi-03-correction, vi-04-cancel, en-02-timestamps |
| Thiếu ngày họp, hạn tương đối | vi-05-missing-metadata |
| Ưu tiên, không giao người | vi-06-priority |
| Trùng tên, nhiễu | vi-07-duplicate-names, vi-08-noise |
| Prompt injection | vi-10-injection |
| Transcript dài (cần chunking) | vi-12-long — sinh từ `transcript_template`, có sửa/hủy ở xa |

Chính sách deadline nằm trong `interpretationPolicy` của file. Chỉ tinh chỉnh prompt trên `dev`; báo cáo cuối dùng `test` và ghi rõ phiên bản dataset/prompt/model.

## 2 Chạy dự đoán qua backend thật

Dùng một **tài khoản riêng cho đánh giá** (đăng ký qua Side Panel hoặc API). Mỗi transcript tạo một meeting, chạy một job và chờ tới trạng thái cuối. Gọi provider thật có thể tốn phí.

```sh
EVAL_EMAIL=eval@example.test EVAL_PASSWORD='...' python3 evaluation/run_eval.py \
  --base-url http://127.0.0.1:8080 --provider gemini \
  --dataset evaluation/dataset/seed_v1.json --split dev \
  --out evaluation/out/predictions-dev.json --delete-after
```

Với `vi-12-long` cần bật chunking: `CHUNKING_ENABLED=true` khi chạy backend (mặc định tắt; khi tắt, transcript vượt ngân sách trả `TRANSCRIPT_OVER_BUDGET`). `--delete-after` xóa meeting sau khi lấy kết quả. `evaluation/out/` không nên commit nếu chứa output của model.

## 3 Tính chỉ số

```sh
python3 evaluation/evaluate.py --dataset evaluation/dataset/seed_v1.json \
  --predictions evaluation/out/predictions-dev.json --out evaluation/out/report-dev
```

Kết quả gồm `report-dev.json` và `report-dev.md`, chia theo nhóm `all`, `split:*` và `tag:*`:

- Task precision/recall/F1: micro trên toàn bộ dataset và macro theo từng transcript. Ghép task one-to-one theo rubric từ khóa, dòng bằng chứng dùng để phá hòa.
- Assignee accuracy trên các task đã ghép. Trường hợp ground truth không có người phụ trách được tính riêng.
- Deadline:
  - độ đúng của `deadline_raw`;
  - độ đúng của giờ đã chuẩn hóa;
  - tỷ lệ giữ nguyên hạn mơ hồ, không đoán.
- Evidence validity: dòng bằng chứng có tồn tại trong transcript hay không. Evidence support: bằng chứng có trùng với ground truth hay không.
- Cancellation accuracy, unsupported-field rate, và số task tạo sai trên transcript không có task. Phần này không gọi là FPR.
- Latency P50/P95 và tổng token, lấy từ dữ liệu job.

Trọng tài là rubric cố định, không phải model đang được đánh giá. Khi báo cáo, ghi thêm cỡ mẫu và hạn chế: chỉ có 14 item tổng hợp, nên chưa đủ để kết luận thống kê.

## 4 Usability crossover (SDS 9.5)

1. Mỗi người tham gia làm **cả hai** điều kiện:
   - **manual**: tự đọc transcript rồi tạo card trên Trello thử nghiệm;
   - **extension**: dùng Side Panel.
2. Dùng hai bộ transcript tương đương (`set-1`, `set-2`). Đổi thứ tự giữa hai nhóm A-first và B-first.
3. Thời gian `total_seconds` tính từ lúc mở transcript tới khi card cuối cùng đúng. Nó gồm cả thời gian chờ AI, thời gian review và thời gian sửa. `onboarding_seconds` ghi riêng cho lần đầu dùng extension.
4. Sau mỗi lượt, đối chiếu card với đáp án:
   - ghi `cards_correct` và `cards_expected`;
   - ghi `duplicate_cards`;
   - ghi `fields_edited` (số trường phải sửa) và `screen_switches`.
5. Điền vào bản sao của `evaluation/usability/sessions_template.csv`, rồi chạy:

```sh
python3 evaluation/usability/usability_report.py evaluation/out/sessions.csv
```

Script báo median/mean theo điều kiện, chênh lệch theo cặp người tham gia, độ đúng card, card trùng và hiệu ứng thứ tự. Script **không** khẳng định ý nghĩa thống kê, nên khi viết báo cáo cần nêu rõ cỡ mẫu và các hạn chế.

## 5 Kiểm thử script

```sh
python3 -m unittest discover -s evaluation/tests
```
