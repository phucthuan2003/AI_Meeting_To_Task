# Meeting Intelligence Service

Microservice FastAPI nhận diện tiếng Việt bằng faster-whisper/CTranslate2 và dùng Gemini để trích xuất tóm tắt, quyết định và công việc. Spring Boot giữ xác thực người dùng, phiên thu, transcript đã lưu và quy trình duyệt task. Khóa dịch vụ chỉ dùng giữa các tiến trình phía máy chủ.

## Khởi động

Trên máy Linux có GPU NVIDIA, cài driver tương thích CUDA và NVIDIA Container Toolkit. Từ thư mục này, tạo `.env` theo `.env.example`, điền `INTELLIGENCE_API_KEY` ngẫu nhiên và `GEMINI_API_KEY` nếu cần phân tích. Mặc định là Turbo FP16 với CUDA 12 và cuDNN 9 theo yêu cầu của CTranslate2. Có thể đặt `COMPUTE_TYPE=int8_float16` khi cần giảm bộ nhớ GPU. [Tài liệu faster-whisper](https://github.com/SYSTRAN/faster-whisper) · [Model Turbo CTranslate2](https://huggingface.co/deepdml/faster-whisper-large-v3-turbo-ct2)

```sh
docker compose --env-file .env -f docker/docker-compose.yml up --build
curl -fsS http://127.0.0.1:8000/healthz
```

Lần đầu tải model từ Hugging Face vào volume `model_cache`. Service chỉ lắng nghe sau khi nạp model và warm-up thành công. Compose công bố cổng trên loopback. Khi chạy qua mạng giữa các máy, dùng mạng nội bộ hoặc HTTPS/WSS tại reverse proxy.

Chạy CPU trên macOS/Linux với Python 3.10 trở lên. `MODEL_NAME=base` giúp kiểm tra nhanh với model nhỏ hơn.

```sh
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
export INTELLIGENCE_API_KEY="$(openssl rand -hex 32)"
DEVICE=cpu MODEL_NAME=base python -m uvicorn src.main:app --host 127.0.0.1 --port 8000 \
  --ws websockets --ws-max-size 64000 --ws-max-queue 8 --limit-concurrency 32
```

## Giao diện

| Endpoint | Dữ liệu gửi | Kết quả |
|---|---|---|
| `POST /api/v1/transcribe/file` | Multipart, trường `file`, WAV/MP3/M4A/WebM, tối đa 25 MiB và 60 phút sau giải mã | `duration_seconds`, `processing_time_seconds`, `rtf`, `segments[{start,end,text}]`, `full_text` |
| `WS /ws/transcribe/stream` | Binary PCM signed 16-bit little-endian mono 16 kHz, tối đa 64.000 byte/frame, sau đó gửi JSON `{"type":"STOP"}` | `TRANSCRIPTION_DELTA` với `is_final: true`, `start`, `end`, `text`, tiếp theo là `STREAM_END` khi đã xử lý đoạn cuối |
| `POST /api/v1/intelligence/extract-actions` | JSON gồm `meeting_id` và `transcript` tối đa 200.000 ký tự | `summary`, `key_decisions`, `action_items[{task,assignee,deadline,priority}]` |

Cả ba dùng `Authorization: Bearer <INTELLIGENCE_API_KEY>`. Trong terminal chạy lệnh thử bên dưới, đặt cùng khóa đã cấu hình cho service. Timestamp tính bằng giây từ đầu file hoặc stream. WebSocket dùng VAD và cửa sổ 2 giây, bước trượt 1,5 giây. Delta là phần văn bản đã chốt, có thể chỉ là một phần của câu nói dài. Stream đóng sau 30 giây không nhận frame, với tối đa 2 giờ audio mỗi phiên. Client gửi `STOP` và chờ `STREAM_END` để lấy phần cuối. Khi kết nối bị đứt, client mở phiên mới và timestamp tính lại từ đầu.

API phân tích chấp nhận hạn ISO `YYYY-MM-DD` và ưu tiên `HIGH`, `MEDIUM`, `LOW`. Các trường người phụ trách, hạn và ưu tiên có thể là `null` nếu transcript thiếu căn cứ. Thiếu khóa Gemini trả `503 LLM_NOT_CONFIGURED`. Audio hỏng trả `422 INVALID_AUDIO`. Lỗi CUDA hết bộ nhớ trả `503 STT_OUT_OF_MEMORY` trên REST và mã đóng `1013` với lý do tương ứng trên WebSocket.

```sh
curl -fsS -H "Authorization: Bearer $INTELLIGENCE_API_KEY" \
  -F file=@benchmarks/test_datasets/10078277432407694754.wav \
  http://127.0.0.1:8000/api/v1/transcribe/file

curl -fsS -H "Authorization: Bearer $INTELLIGENCE_API_KEY" -H 'Content-Type: application/json' \
  --data '{"meeting_id":"SMOKE-001","transcript":"Mai kiểm tra API trước ngày 14/11/2026."}' \
  http://127.0.0.1:8000/api/v1/intelligence/extract-actions
```

Swagger UI tại `http://127.0.0.1:8000/docs`. WebSocket dùng client có thể đặt header khi bắt tay, ví dụ `websockets.connect(..., additional_headers={"Authorization": "Bearer " + key})`. Với Chrome Extension hiện tại, audio đi qua Spring Boot.

## Kết nối Spring Boot và Chrome Extension

Đặt `INTELLIGENCE_STT_URL=http://127.0.0.1:8000` và cùng `INTELLIGENCE_API_KEY` trong môi trường chạy Spring Boot. Dùng địa chỉ dịch vụ nội bộ nếu hai tiến trình chạy ở hai container. Worker gửi file hoặc đoạn thu WAV qua API file. Extension vẫn thu theo đoạn khoảng 15 giây và xem tiến độ qua SSE của Spring Boot. WebSocket 2 giây là hợp đồng riêng cho client phía máy chủ.

Khi bật URL, phiên mới chọn microservice và phiên đã tạo với Groq tiếp tục dùng Groq nếu khóa đó còn cấu hình. Khi để trống URL, phiên mới dùng Groq theo thiết lập hiện tại. API `extract-actions` phục vụ tóm tắt và trích xuất độc lập. Luồng task nháp và Trello vẫn cần schema riêng có evidence, version và bước duyệt.

## Benchmark

Chạy từ terminal hoặc từ image CUDA. `--manifest` nhận danh sách JSON gồm `file` (đường dẫn tương đối với manifest) và `transcript` (nhãn chuẩn). Mẫu mặc định là một bản ghi 10,02 giây từ FLEURS tiếng Việt, dùng để kiểm tra pipeline. Với báo cáo thực nghiệm, cần manifest riêng chứa audio họp có nhãn, nhiều người nói và file dài. [Nguồn và giấy phép mẫu](benchmarks/test_datasets/README.md)

```sh
python benchmarks/run_benchmark.py --device cpu --repeats 1
python benchmarks/run_benchmark.py --device cuda --manifest /path/to/ground_truth.json --repeats 3
```

Benchmark chạy Turbo và hai baseline Whisper Base/Small trên cùng dữ liệu, mỗi model trong tiến trình riêng. WER dùng `jiwer`, giữ dấu tiếng Việt, bỏ dấu câu, không phân biệt hoa thường và tách theo khoảng trắng. Latency đo sau warm-up và gồm toàn bộ quá trình tiêu thụ kết quả suy luận. Với cửa sổ 1/2/5 giây, benchmark bật word timestamps và chỉ đo cửa sổ đủ độ dài. WER gộp đo trên file đầy đủ. Kết quả gồm `benchmark_metrics.csv`, `benchmark_metrics.md`, `latency_vs_chunk_size.png` và `rtf_comparison.png` trong `benchmarks/results/`.

Chạy benchmark trong image trên máy GPU Linux. Dùng PID namespace của host **chỉ cho container benchmark** để NVML đối chiếu đúng PID. Chạy khi GPU không có tải cạnh tranh nếu cần số liệu đối chuẩn.

```sh
docker compose --env-file .env -f docker/docker-compose.yml build
docker run --name aimtt-stt-benchmark --gpus device=0 --pid=host \
  -e NVIDIA_DRIVER_CAPABILITIES=compute,utility \
  aimtt/meeting-intelligence:local python3 benchmarks/run_benchmark.py --device cuda
mkdir -p benchmarks/results
docker cp aimtt-stt-benchmark:/app/benchmarks/results/. benchmarks/results/
docker rm aimtt-stt-benchmark
```

VRAM tiến trình lấy mẫu qua NVML, gồm cấp phát từ CTranslate2. Nếu môi trường đã cài PyTorch CUDA tương thích, dùng thêm `--torch-memory` để ghi `torch.cuda.max_memory_allocated()` ở cột riêng. Khi driver hoặc PID namespace không cho đọc bộ nhớ tiến trình, báo cáo để `N/A`. [Tài liệu NVML](https://docs.nvidia.com/deploy/nvml-api/latest/api/group__nvmlDeviceQueries.html) · [Phạm vi phép đo PyTorch](https://docs.pytorch.org/docs/stable/torch_cuda_memory)

## Kiểm thử và mức thực thi

```sh
python -m unittest discover -s tests -v
```

| Công đoạn | Đã thực thi | Phạm vi xác minh |
|---|---|---|
| STT file và streaming | Turbo tiếng Việt, VAD, 2 giây/overlap 0,5 giây, flush, lỗi đầu vào và CUDA OOM | 11 test Python đạt. Smoke CPU đọc WAV/MP3/M4A. Qua WebSocket Uvicorn thật, mẫu FLEURS trả 6 delta và `STREAM_END`, WER 24,00%. GPU OOM được kiểm tra ở lớp trả mã lỗi bằng test double. |
| Phân tích | Gemini structured output và kiểm tra Pydantic | Gọi Gemini thật với transcript tổng hợp, nhận một quyết định và một công việc đúng schema. |
| Tích hợp | Spring Boot chọn endpoint STT bằng cấu hình, Extension vẫn đi qua Spring Boot | Adapter Java gọi REST Uvicorn thật qua HTTP/1.1. 4 test tích hợp capture/PostgreSQL, 69 test Extension và build MV3. Suite backend lượt cuối đạt 151/154. Hai lỗi duyệt hạn và đối soát Trello tái hiện trên bản gốc. Một test Trello khác đạt ở lượt trước và lỗi ở lượt cuối. |
| Benchmark | Đo 3 model và xuất đủ 4 tệp | CPU INT8, một bản ghi FLEURS 10,02 giây. Turbo có WER file 8,00%, RTF file 0,4324 và p50 3,85 giây cho cửa sổ 2 giây. Đây là smoke test trên một bản ghi. |
| Đóng gói GPU | Dockerfile CUDA/cuDNN, GPU reservation, model cache, healthcheck | Compose qua kiểm tra cấu hình. Chưa chạy image trên NVIDIA để đo VRAM và kiểm chứng mục tiêu RTF < 0,1. |

## Thực nghiệm đối chứng chính sách cắt

`benchmarks/stream_ablation.py` chạy lại ba cấu hình trên cùng bản ghi và model. A dùng cửa sổ cố định của WebSocket. B chọn khoảng lặng bằng Silero VAD trong giới hạn 2–4 giây audio mới. C giữ chính sách B và thử giải mã lại tại biên cưỡng bức với 0,5 giây lookahead. B và C là cấu hình nghiên cứu trong replay. Luồng sản phẩm dùng chính sách A.

Lấy `data/vi_vn/test.tsv` và `data/vi_vn/audio/test.tar.gz` từ Google FLEURS. Script lấy 24 ID câu khác nhau theo thứ tự archive, giới hạn 6–16 giây và bỏ ID 1738 đã dùng cho smoke test trước. Kết quả nhiễu trắng 10 dB của bốn mẫu đầu được tách thành nhóm riêng. [Dữ liệu FLEURS, CC BY 4.0](https://huggingface.co/datasets/google/fleurs/tree/main/data/vi_vn)

```sh
python benchmarks/prepare_fleurs.py /path/test.tar.gz /path/test.tsv /tmp/fleurs-vi-test
OMP_NUM_THREADS=4 python benchmarks/stream_ablation.py \
  /tmp/fleurs-vi-test/ground_truth.json benchmarks/results/course-ablation \
  --model base --device cpu --compute-type int8 --stress 4
```

| Cấu hình | WER sạch | RTF suy luận | P95 phát delta mô phỏng | WER nhiễu 10 dB |
|---|---:|---:|---:|---:|
| A · Cố định | 64,20% | 0,322 | 2,93 s | 82,18% |
| B · VAD | 51,48% | 0,130 | 1,83 s | 77,23% |
| C · VAD + giải mã lại | 52,66% | 0,185 | 4,60 s | 79,21% |

A → B giảm 92 lượt giải mã trên nhóm sạch. C thực hiện thêm 37 lượt giải mã lại và có WER cao hơn B 1,18 điểm phần trăm. WER tuyệt đối còn cao. Chỉ số P95 dùng đồng hồ replay cộng thời gian ASR đo được và lookahead, chưa gồm tính VAD ngoài ASR, mạng hoặc giao diện. Để đo tác động lên action item cần một bộ audio họp có nhãn người nói, task và hạn trên cùng bản ghi.
