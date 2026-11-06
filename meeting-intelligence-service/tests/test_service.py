from contextlib import ExitStack
from io import BytesIO
import unittest
from unittest.mock import MagicMock, patch
import wave

from fastapi.testclient import TestClient
import numpy as np

from src.core.config import SAMPLE_RATE, Settings
from src.main import create_app
from src.schemas.audio_schema import Segment
from src.schemas.meeting_schema import MeetingAnalysis, MeetingRequest
from src.services.audio_buffer import AudioBuffer, FinalizedTranscript, Window
from src.services.llm_analyzer import AnalysisUnavailable, GeminiAnalyzer
from src.services.stt_engine import AudioTooLong, RecognizedSegment, SttEngine, SttUnavailable, decode_audio


def wav(seconds=1, rate=8_000):
    stream = BytesIO()
    with wave.open(stream, "wb") as writer:
        writer.setparams((1, 2, rate, 0, "NONE", "not compressed"))
        writer.writeframes(b"\0\0" * rate * seconds)
    return stream.getvalue()


def recognized(start, end, text):
    return RecognizedSegment(start, end, text, (Segment(start=start, end=end, text=text),))


class BufferTests(unittest.TestCase):
    def test_odd_network_boundaries_resample_pcm_and_keep_exact_overlap(self):
        audio = np.arange(56_000, dtype="<i2").tobytes()
        buffer = AudioBuffer()
        self.assertEqual(list(buffer.push(audio[:13])), [])
        first, second = buffer.push(audio[13:])
        tail = buffer.finish()
        self.assertEqual((first.offset, second.offset, tail.offset), (0, 1.5, 3))
        self.assertEqual((first.samples.size, second.samples.size, tail.samples.size), (32_000, 32_000, 8_000))
        np.testing.assert_array_equal(first.samples[-8_000:], second.samples[:8_000])
        self.assertEqual(first.samples[1], np.float32(1 / 32768))
        self.assertTrue(tail.final)

    def test_dedupes_drift_and_keeps_later_real_repetition(self):
        transcript = FinalizedTranscript()
        window = Window(0, np.zeros(32_000))
        first = list(transcript.accept(window, [recognized(1.56, 1.8, "Mai")]))
        self.assertEqual([s.text for s in first], ["Mai"])
        window = Window(1.5, np.zeros(32_000))
        second = list(transcript.accept(window, [
            recognized(.16, .42, " Mai,"), recognized(.65, .88, "Mai"),
        ]))
        self.assertEqual([s.text for s in second], ["Mai"])
        self.assertGreaterEqual(second[0].start, first[0].end)

    def test_stop_rejects_incomplete_samples(self):
        buffer = AudioBuffer()
        list(buffer.push(b"x"))
        with self.assertRaisesRegex(ValueError, "INCOMPLETE_PCM_SAMPLE"):
            buffer.finish()

    def test_decode_uses_16khz_and_bounds_decoded_length(self):
        self.assertEqual(decode_audio(BytesIO(wav())).size, SAMPLE_RATE)
        with self.assertRaises(AudioTooLong):
            decode_audio(BytesIO(wav(2)), max_seconds=1)


class ApiTests(unittest.TestCase):
    def setUp(self):
        engine = object.__new__(SttEngine)
        engine.warmup = MagicMock()
        engine.transcribe = MagicMock(return_value=[recognized(.1, .4, "Xin chào")])
        analyzer = MagicMock()
        analyzer.extract.return_value = MeetingAnalysis.model_validate({
            "summary": "Đã giao việc.", "key_decisions": [], "action_items": [{
                "task": "Kiểm tra API", "assignee": None, "deadline": "2026-11-14", "priority": "HIGH",
            }],
        })
        self.engine = engine
        self.analyzer = analyzer
        stack = ExitStack()
        self.addCleanup(stack.close)
        self.client = stack.enter_context(TestClient(create_app(
            Settings(api_key="test-key", gemini_api_key="test-gemini"),
            engine_factory=lambda *_: engine, analyzer_factory=lambda *_: analyzer,
        )))
        self.auth = {"Authorization": "Bearer test-key"}

    def test_health_and_file_contract(self):
        self.assertEqual(self.client.get("/healthz").json(), {"status": "ready"})
        files = {"file": ("sample.wav", wav(), "audio/wav")}
        self.assertEqual(self.client.post("/api/v1/transcribe/file", files=files).status_code, 401)
        response = self.client.post("/api/v1/transcribe/file", headers=self.auth, files=files)
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["duration_seconds"], 1)
        self.assertEqual(body["full_text"], "Xin chào")
        self.assertEqual(body["rtf"], body["processing_time_seconds"])
        self.assertEqual(body["segments"], [{"start": .1, "end": .4, "text": "Xin chào"}])

    def test_corrupt_file_and_gpu_oom_are_sanitized(self):
        endpoint = "/api/v1/transcribe/file"
        response = self.client.post(endpoint, headers=self.auth, files={"file": ("fake.wav", b"garbage")})
        self.assertEqual((response.status_code, response.json()), (422, {"detail": "INVALID_AUDIO"}))
        self.engine.transcribe.side_effect = SttUnavailable("STT_OUT_OF_MEMORY")
        response = self.client.post(endpoint, headers=self.auth, files={"file": ("audio.wav", wav())})
        self.assertEqual((response.status_code, response.json()), (503, {"detail": "STT_OUT_OF_MEMORY"}))

    def test_chunked_http_body_is_bounded(self):
        def chunks():
            yield b'--test\r\nContent-Disposition: form-data; name="file"; filename="clip.wav"\r\n\r\n'
            yield b"\0" * (128 * 1024)
            yield b"\r\n--test--\r\n"

        headers = {**self.auth, "Content-Type": "multipart/form-data; boundary=test"}
        with patch("src.main.MAX_UPLOAD_BYTES", 10):
            response = self.client.post("/api/v1/transcribe/file", headers=headers, content=chunks())
        self.assertEqual((response.status_code, response.json()), (413, {"detail": "REQUEST_TOO_LARGE"}))

    def test_websocket_auth_and_flush_tail_on_stop(self):
        from starlette.websockets import WebSocketDisconnect

        url = "/ws/transcribe/stream"
        with self.assertRaises(WebSocketDisconnect):
            with self.client.websocket_connect(url):
                pass
        with self.client.websocket_connect(url, headers=self.auth) as ws:
            ws.send_bytes(b"\0\0" * 16_000)
            ws.send_json({"type": "STOP"})
            self.assertEqual(ws.receive_json(), {
                "type": "TRANSCRIPTION_DELTA", "is_final": True, "text": "Xin chào", "start": .1, "end": .4,
            })
            self.assertEqual(ws.receive_json(), {"type": "STREAM_END"})

    def test_websocket_bad_frame_and_disconnect_release_slot(self):
        from starlette.websockets import WebSocketDisconnect

        url = "/ws/transcribe/stream"
        with self.client.websocket_connect(url, headers=self.auth):
            pass
        with self.client.websocket_connect(url, headers=self.auth) as ws:
            ws.send_bytes(b"x" * 64_001)
            with self.assertRaises(WebSocketDisconnect) as raised:
                ws.receive_json()
            self.assertEqual(raised.exception.code, 1009)

    def test_llm_result_schema_and_transcript_privacy(self):
        endpoint = "/api/v1/intelligence/extract-actions"
        response = self.client.post(endpoint, headers=self.auth, json={"meeting_id": "MEET-01", "transcript": "Mai kiểm tra API."})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["action_items"][0]["deadline"], "2026-11-14")
        response = self.client.post(endpoint, headers=self.auth, json={"meeting_id": "", "transcript": "private"})
        self.assertEqual((response.status_code, response.json()), (422, {"detail": "INVALID_REQUEST"}))


class GeminiContractTests(unittest.TestCase):
    def test_gemini_uses_schema_and_rejects_invalid_provider_content(self):
        analyzer = object.__new__(GeminiAnalyzer)
        analyzer.model = "gemini-test"
        analyzer.client = MagicMock()
        generate = analyzer.client.models.generate_content
        generate.return_value.text = '{"summary":"OK","key_decisions":[],"action_items":[]}'
        meeting = MeetingRequest(meeting_id="MEET", transcript="Tóm tắt thử")
        self.assertEqual(analyzer.extract(meeting).summary, "OK")
        config = generate.call_args.kwargs["config"]
        self.assertEqual(config.response_json_schema, MeetingAnalysis.model_json_schema())
        self.assertNotIn(meeting.transcript, config.system_instruction)
        generate.return_value.text = '{"summary":"private","key_decisions":[],"action_items":[{"task":"A","priority":"URGENT"}]}'
        with self.assertRaisesRegex(AnalysisUnavailable, "^LLM_RESPONSE_INVALID$"):
            analyzer.extract(meeting)
