"""Tự chạy các test case STT/SEC của Meeting Intelligence Service trên service đang chạy.

Cách dùng (trong venv của service, từ thư mục meeting-intelligence-service):
    export INTELLIGENCE_API_KEY=...        # cùng khóa với service
    python tools/run_stt_testcases.py --url http://127.0.0.1:8000 [--slow]
In ra PASS/FAIL theo ID trong bảng test case; --slow chạy thêm STT-15 (chờ 31 giây).
"""
import argparse
import asyncio
from http.client import HTTPConnection, HTTPSConnection
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
SAMPLE = ROOT / "benchmarks" / "test_datasets" / "10078277432407694754.wav"
results = []


def record(tc, ok, detail=""):
    results.append((tc, ok, detail))
    print(f"{'PASS' if ok else 'FAIL'}  {tc:7} {detail}")


def http(method, url, body=None, headers=None, timeout=300):
    req = urllib.request.Request(url, data=body, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def reject_large_body(base, path, size, headers):
    """Read an early 413 without trying to write a body the server already rejected."""
    url = urlsplit(base)
    connection_type = HTTPSConnection if url.scheme == "https" else HTTPConnection
    with_connection = connection_type(url.hostname, url.port, timeout=10)
    try:
        with_connection.request("POST", path, headers={**headers, "Content-Length": str(size)})
        response = with_connection.getresponse()
        return response.status, response.read()
    finally:
        with_connection.close()


def multipart(name, data, field="file"):
    boundary = uuid.uuid4().hex
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"{field}\"; filename=\"{name}\"\r\n"
            f"Content-Type: application/octet-stream\r\n\r\n").encode() + data + f"\r\n--{boundary}--\r\n".encode()
    return body, {"Content-Type": f"multipart/form-data; boundary={boundary}"}


def detail_of(raw):
    try:
        return json.loads(raw).get("detail")
    except Exception:
        return raw[:80]


def rest_cases(base, key, formats):
    auth = {"Authorization": f"Bearer {key}"}
    s, b = http("GET", base + "/healthz")
    record("STT-01", s == 200 and b"ready" in b, f"healthz {s} {b[:40]!r}")

    wav = SAMPLE.read_bytes()
    body, h = multipart("sample.wav", wav)
    t = time.perf_counter(); s, b = http("POST", base + "/api/v1/transcribe/file", body, {**h, **auth})
    ok = s == 200
    if ok:
        j = json.loads(b)
        ok = all(k in j for k in ("duration_seconds", "processing_time_seconds", "rtf", "segments", "full_text")) and j["full_text"].strip() != ""
        record("STT-03", ok, f"{s} dur={j.get('duration_seconds'):.2f}s rtf={j.get('rtf'):.3f} ({time.perf_counter()-t:.1f}s) text='{j.get('full_text','')[:60]}…'")
    else:
        record("STT-03", False, f"{s} {detail_of(b)}")

    if formats:
        with tempfile.TemporaryDirectory() as temp:
            for suffix, codec in (("mp3", "libmp3lame"), ("m4a", "aac"), ("webm", "libopus")):
                target = Path(temp) / f"sample.{suffix}"
                subprocess.run(["ffmpeg", "-v", "error", "-i", str(SAMPLE), "-c:a", codec, str(target)], check=True)
                data, headers = multipart(target.name, target.read_bytes())
                status, result = http("POST", base + "/api/v1/transcribe/file", data, {**headers, **auth})
                ok = status == 200 and bool(json.loads(result).get("full_text", "").strip())
                record(f"FMT-{suffix.upper()}", ok, f"{status} · transcript {'có' if ok else 'không có'} nội dung")

    s, b = http("POST", base + "/api/v1/transcribe/file", body, h)
    record("STT-05", s == 401, f"không token → {s} {detail_of(b)}")
    s, b = http("POST", base + "/api/v1/transcribe/file", body, {**h, "Authorization": "Bearer sai-khoa"})
    record("STT-05b", s == 401, f"sai token → {s}")

    body2, h2 = multipart("sample.flac", wav)
    s, b = http("POST", base + "/api/v1/transcribe/file", body2, {**h2, **auth})
    record("STT-06", s == 415 and detail_of(b) == "UNSUPPORTED_AUDIO_FORMAT", f"{s} {detail_of(b)}")

    body3, h3 = multipart("broken.wav", os.urandom(50_000))
    s, b = http("POST", base + "/api/v1/transcribe/file", body3, {**h3, **auth})
    record("STT-07", s == 422 and detail_of(b) == "INVALID_AUDIO", f"{s} {detail_of(b)}")

    s, b = reject_large_body(base, "/api/v1/transcribe/file", 30 * 1024 * 1024,
                             {"Content-Type": "multipart/form-data; boundary=fixture", **auth})
    record("STT-08", s == 413, f"{s} {detail_of(b)}")

    s, b = http("POST", base + "/api/v1/transcribe/file", b"{}", {"Content-Type": "application/json", **auth})
    record("STT-10", s == 415 and detail_of(b) == "EXPECTED_MULTIPART", f"json body → {s} {detail_of(b)}")

    req = json.dumps({"meeting_id": "SMOKE-001", "transcript": "Mai kiểm tra API trước ngày 14/11/2026."}).encode()
    s, b = http("POST", base + "/api/v1/intelligence/extract-actions", req, {"Content-Type": "application/json", **auth})
    if s == 503 and detail_of(b) == "LLM_NOT_CONFIGURED":
        record("STT-20", True, "chưa có GEMINI_API_KEY → 503 LLM_NOT_CONFIGURED (STT-19 bỏ qua)")
    else:
        ok = s == 200 and all(k in json.loads(b) for k in ("summary", "key_decisions", "action_items"))
        record("STT-19", ok, f"{s} {b[:160].decode(errors='ignore')}")

    bad = json.dumps({"meeting_id": "X", "transcript": "", "extra": 1}).encode()
    s, b = http("POST", base + "/api/v1/intelligence/extract-actions", bad, {"Content-Type": "application/json", **auth})
    record("STT-21", s == 422 and b"INVALID_REQUEST" in b, f"{s} {detail_of(b)}")

    s, b = reject_large_body(base, "/api/v1/intelligence/extract-actions", 3 * 1024 * 1024,
                             {"Content-Type": "application/json", **auth})
    record("SEC-08", s == 413, f"body 3 MiB → {s} {detail_of(b)}")


def pcm_sample():
    from src.services.stt_engine import decode_audio
    import numpy as np
    with SAMPLE.open("rb") as f:
        samples = decode_audio(f)
    return (np.clip(samples, -1, 1) * 32767).astype("<i2").tobytes()


async def ws_cases(base, key, slow):
    import websockets
    from websockets.exceptions import ConnectionClosed
    url = base.replace("http", "ws", 1) + "/ws/transcribe/stream"
    hdr = {"Authorization": f"Bearer {key}"}

    async def close_code(headers, send=None, wait=10):
        try:
            async with websockets.connect(url, additional_headers=headers, max_size=None, ping_interval=None) as ws:
                if send is not None:
                    await ws.send(send)
                await asyncio.wait_for(ws.recv(), wait)
                return None
        except ConnectionClosed as e:
            return e.rcvd.code if e.rcvd else None
        except websockets.exceptions.InvalidStatus as e:
            return f"HTTP {e.response.status_code}"

    pcm = pcm_sample()
    deltas, end = [], False
    t = time.perf_counter()
    async with websockets.connect(url, additional_headers=hdr) as ws:
        for i in range(0, len(pcm), 3200):
            await ws.send(pcm[i:i + 3200])
        await ws.send(json.dumps({"type": "STOP"}))
        async for msg in ws:
            m = json.loads(msg)
            if m.get("type") == "TRANSCRIPTION_DELTA":
                deltas.append(m)
            elif m.get("type") == "STREAM_END":
                end = True
    mono = all(a["end"] <= b["start"] + 1e-3 for a, b in zip(deltas, deltas[1:]))
    record("STT-11", end and deltas and mono,
           f"{len(deltas)} delta, STREAM_END={end}, thời gian tăng dần={mono}, {time.perf_counter()-t:.1f}s: '{' '.join(d['text'] for d in deltas)[:70]}…'")

    record("STT-12", (c := await close_code({})) in {1008, "HTTP 403"}, f"không token → đóng {c}")
    record("STT-13", (c := await close_code(hdr, b"\0" * 70_000)) == 1009, f"frame 70.000 byte → đóng {c}")
    record("STT-14", (c := await close_code(hdr, json.dumps({"type": "PAUSE"}))) == 1003, f"lệnh PAUSE → đóng {c}")

    try:
        async with websockets.connect(url, additional_headers=hdr) as ws:
            await ws.send(b"\0" * 3201)
            await ws.send(json.dumps({"type": "STOP"}))
            await asyncio.wait_for(ws.recv(), 10)
            c = None
    except ConnectionClosed as e:
        c = e.rcvd.code if e.rcvd else None
    record("STT-16", c == 1003, f"byte lẻ + STOP → đóng {c}")

    if slow:
        record("STT-15", (c := await close_code(hdr, None, wait=40)) == 1008, f"im lặng 31 s → đóng {c}")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--url", default="http://127.0.0.1:8000")
    p.add_argument("--slow", action="store_true")
    p.add_argument("--formats", action="store_true", help="Chuyển mẫu qua MP3/M4A/WebM bằng ffmpeg rồi thử API")
    a = p.parse_args()
    key = os.environ.get("INTELLIGENCE_API_KEY")
    if not key:
        sys.exit("Đặt INTELLIGENCE_API_KEY giống service trước khi chạy.")
    if a.formats and not shutil.which("ffmpeg"):
        p.error("--formats cần ffmpeg trong PATH")
    rest_cases(a.url, key, a.formats)
    asyncio.run(ws_cases(a.url, key, a.slow))
    passed = sum(ok for _, ok, _ in results)
    print(f"\nTổng: {passed}/{len(results)} PASS")
    sys.exit(0 if passed == len(results) else 1)


if __name__ == "__main__":
    main()
