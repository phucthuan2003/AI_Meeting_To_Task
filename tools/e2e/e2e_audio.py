#!/usr/bin/env python3
"""Exercise real MV3 file upload and tab recording against the isolated test stack."""
import argparse
import hashlib
from pathlib import Path
import re
from tempfile import TemporaryDirectory
import uuid

from playwright.sync_api import expect, sync_playwright

EXTENSION_ALPHABET = str.maketrans("0123456789abcdef", "abcdefghijklmnop")
SAMPLE = (Path(__file__).resolve().parents[2] / "meeting-intelligence-service" / "benchmarks"
          / "test_datasets" / "10078277432407694754.wav")


def register(page):
    page.get_by_role("button", name="Chưa có tài khoản? Đăng ký").click()
    page.get_by_label("Email").fill(f"audio-{uuid.uuid4().hex}@example.test")
    page.get_by_label("Mật khẩu").fill("fixture-audio-password-123")
    page.get_by_role("button", name="Đăng ký và đăng nhập").click()
    expect(page.get_by_role("button", name="Đăng xuất")).to_be_visible(timeout=20_000)


def run(context, ext_id, media_url, shots):
    panel = context.new_page()
    panel.goto(f"chrome-extension://{ext_id}/sidepanel.html")
    register(panel)
    panel.get_by_role("button", name="Ghi âm / Live").click()
    panel.locator('input[type="file"]').set_input_files(
        {"name": "invalid.txt", "mimeType": "text/plain", "buffer": b"not audio"})
    upload = panel.get_by_role("button", name="Gửi file để chuyển lời nói")
    upload.click()
    expect(panel.get_by_text("Chọn WAV, MP3, M4A hoặc WebM", exact=False)).to_be_visible()
    assert context.service_workers[0].evaluate(
        """async () => {
            const {aiMttSession:s} = await chrome.storage.session.get('aiMttSession');
            const r = await fetch(s.origin + '/api/v1/captures', {
                headers: {Authorization: 'Bearer ' + s.accessToken},
            });
            if (!r.ok) throw new Error('Could not list captures: ' + r.status);
            return (await r.json()).length;
        }"""
    ) == 0
    print("PASS AUD-01 invalid file rejected before creating capture", flush=True)

    panel.get_by_label("Tiêu đề").fill("Audio E2E file")
    panel.locator('input[type="file"]').set_input_files(SAMPLE)
    upload.click()
    publish = panel.get_by_role("button", name="Chốt & lưu thành meeting để review")
    expect(publish).to_be_enabled(timeout=60_000)
    expect(panel.get_by_text("Đã xử lý 1/1 đoạn", exact=False)).to_be_visible()
    expect(panel.get_by_text(re.compile(r"Transcript STT \([1-9]\d* đoạn\)"))).to_be_visible()
    panel.screenshot(path=str(shots / "audio-file-ready.png"), full_page=True)
    publish.click()
    expect(panel.get_by_text("Audio E2E file", exact=True)).to_be_visible(timeout=20_000)
    print("PASS AUD-02 real WAV upload, STT and publish", flush=True)

    panel.get_by_role("button", name="Ghi âm / Live").click()
    panel.get_by_role("button", name="Phiên mới").click()
    panel.get_by_label("Nguồn").select_option("LIVE")
    panel.get_by_label("Tiêu đề").fill("Audio E2E live")
    panel.get_by_label("Dịch vụ phân tích").select_option("openai", timeout=15_000)
    panel.get_by_label("Kèm microphone").uncheck()
    panel.get_by_role("button", name="Chuẩn bị phiên live").click()
    start = panel.get_by_role("button", name="Bắt đầu thu tab đang mở")
    expect(start).to_be_enabled()

    tab = context.new_page()
    tab.goto(media_url)
    tab.bring_to_front()
    tab.locator("audio").evaluate("audio => { audio.loop = true; audio.muted = false; return audio.play(); }")
    # The test panel is a normal extension page. Keep the media tab active when
    # dispatching the panel button, as Chrome's real side panel would.
    start.evaluate("button => button.click()")
    expect(panel.get_by_text(re.compile(r"Thu: RECORDING"))).to_be_visible(timeout=20_000)
    panel.screenshot(path=str(shots / "audio-tab-recording.png"), full_page=True)
    expect(panel.get_by_text("Đã xử lý 1/1 đoạn", exact=False)).to_be_visible(timeout=55_000)
    print("PASS AUD-03 real tab capture and automatic 15 s chunk upload", flush=True)

    panel.get_by_role("button", name="Dừng thu & gửi đoạn cuối").evaluate("button => button.click()")
    expect(publish).to_be_enabled(timeout=60_000)
    expect(panel.get_by_text("Đã xử lý 2/2 đoạn", exact=False)).to_be_visible()
    expect(panel.get_by_text(re.compile(r"Thu: STOPPED"))).to_be_visible()
    expect(panel.get_by_text(re.compile(r"Transcript STT \([1-9]\d* đoạn\)"))).to_be_visible()
    panel.screenshot(path=str(shots / "audio-tab-ready.png"), full_page=True)
    publish.click()
    expect(panel.get_by_text("Audio E2E live", exact=True)).to_be_visible(timeout=20_000)
    print("PASS AUD-04 stop, flush partial chunk, STT and publish live capture", flush=True)
    tab.close()

    panel.get_by_role("button", name="Ghi âm / Live").click()
    panel.get_by_role("button", name="Phiên mới").click()
    panel.get_by_label("Nguồn").select_option("LIVE")
    panel.get_by_label("Dịch vụ phân tích").select_option("openai", timeout=15_000)
    panel.get_by_role("button", name="Chuẩn bị phiên live").click()
    panel.once("dialog", lambda dialog: dialog.accept())
    panel.get_by_role("button", name="Hủy phiên").click()
    expect(panel.get_by_role("button", name="Chuẩn bị phiên live")).to_be_visible()
    expect(publish).to_have_count(0)
    print("PASS AUD-05 cancel a prepared live session", flush=True)


def test_microphone(chromium, ext, ext_id, executable, shots):
    # Use a different profile so the fake-device flag cannot affect tab capture.
    with TemporaryDirectory(prefix="aimtt-mic-") as profile:
        context = chromium.launch_persistent_context(
            profile, channel="chromium", executable_path=executable,
            args=[f"--disable-extensions-except={ext}", f"--load-extension={ext}",
                  "--use-fake-device-for-media-stream", "--use-fake-ui-for-media-stream"],
        )
        try:
            page = context.new_page()
            page.goto(f"chrome-extension://{ext_id}/microphone.html")
            page.get_by_role("button", name="Cho phép microphone").click()
            expect(page.locator("#status")).to_contain_text("Đã cấp quyền và dừng microphone thử")
            page.screenshot(path=str(shots / "audio-microphone-permission.png"))
            print("PASS AUD-06 open and stop a simulated microphone from the permission page", flush=True)
        finally:
            context.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ext", type=Path, required=True)
    parser.add_argument("--media-url", help="Local HTML page playing the sample audio")
    parser.add_argument("--shots", type=Path, required=True)
    parser.add_argument("--chromium")
    parser.add_argument("--headless", action="store_true", help="Headless audio routing varies by platform")
    parser.add_argument("--microphone-only", action="store_true", help="Test permission with a simulated microphone")
    args = parser.parse_args()
    if not args.media_url and not args.microphone_only:
        parser.error("--media-url is required for tab recording")
    ext = str(args.ext.resolve())
    ext_id = hashlib.sha256(ext.encode()).hexdigest()[:32].translate(EXTENSION_ALPHABET)
    args.shots.mkdir(parents=True, exist_ok=True)
    with TemporaryDirectory(prefix="aimtt-chromium-") as profile, sync_playwright() as playwright:
        if args.microphone_only:
            test_microphone(playwright.chromium, ext, ext_id, args.chromium, args.shots)
            return
        context = playwright.chromium.launch_persistent_context(
            profile, channel="chromium", executable_path=args.chromium,
            headless=args.headless, ignore_default_args=["--mute-audio"],
            args=[f"--disable-extensions-except={ext}", f"--load-extension={ext}",
                  f"--allowlisted-extension-id={ext_id}", "--autoplay-policy=no-user-gesture-required"],
            viewport={"width": 430, "height": 950}, locale="vi-VN", timezone_id="Asia/Ho_Chi_Minh",
        )
        try:
            worker = context.service_workers[0] if context.service_workers else context.wait_for_event("serviceworker")
            assert worker.url.split("/")[2] == ext_id
            run(context, ext_id, args.media_url, args.shots)
        finally:
            context.close()


if __name__ == "__main__":
    main()
