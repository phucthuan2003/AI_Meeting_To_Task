# Benchmark STT tiếng Việt

- Manifest `ground_truth.json`. Số lượt mỗi mẫu `1`.
- Thiết bị `cpu`, kiểu tính toán `int8`, hệ thống `macOS-14.6.1-arm64-arm-64bit`.
- faster-whisper `1.2.1`, CTranslate2 `4.6.0`.
- Latency là thời gian từ mảng PCM đến khi tiêu thụ hết kết quả suy luận, sau warm-up.
- RTF gộp là tổng thời gian suy luận chia tổng độ dài audio. Mục tiêu GPU cho file đầy đủ < 0,1.
- WER gộp tính trên file đầy đủ ở lượt đầu, giữ dấu tiếng Việt, phân tách theo khoảng trắng.
- VRAM NVML là đỉnh lấy mẫu 20 ms của tiến trình, gồm cả model. Cột PyTorch chỉ đo allocator của PyTorch.

| Model | Đầu vào | Lượt đo | Latency p50 (s) | Latency p95 (s) | RTF gộp | WER |
|---|---|---:|---:|---:|---:|---:|
| deepdml/faster-whisper-large-v3-turbo-ct2 | 1s | 10 | 3.9030 | 4.0382 | 3.1512 | N/A |
| deepdml/faster-whisper-large-v3-turbo-ct2 | 2s | 5 | 3.8506 | 9.2158 | 2.5876 | N/A |
| deepdml/faster-whisper-large-v3-turbo-ct2 | 5s | 2 | 4.0032 | 4.0198 | 0.8006 | N/A |
| deepdml/faster-whisper-large-v3-turbo-ct2 | full | 1 | 4.3324 | 4.3324 | 0.4324 | 8.00% |
| base | 1s | 10 | 0.3414 | 2.7888 | 0.7166 | N/A |
| base | 2s | 5 | 0.3511 | 0.4246 | 0.1822 | N/A |
| base | 5s | 2 | 0.4782 | 0.4871 | 0.0956 | N/A |
| base | full | 1 | 0.6201 | 0.6201 | 0.0619 | 16.00% |
| small | 1s | 10 | 0.9359 | 1.1298 | 0.7852 | N/A |
| small | 2s | 5 | 1.0661 | 1.1781 | 0.5282 | N/A |
| small | 5s | 2 | 1.3271 | 1.4142 | 0.2654 | N/A |
| small | full | 1 | 1.4641 | 1.4641 | 0.1461 | 16.00% |

| Model | GPU | Đỉnh VRAM NVML (MiB) | Đỉnh PyTorch (MiB) |
|---|---|---:|---:|
| deepdml/faster-whisper-large-v3-turbo-ct2 | N/A | N/A | N/A |
| base | N/A | N/A | N/A |
| small | N/A | N/A | N/A |
