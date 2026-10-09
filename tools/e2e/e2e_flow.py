#!/usr/bin/env python3
"""Full browser E2E (SDS §9.6 / §14.8 demo path) with the real unpacked extension in Chromium.

Requires: backend (E2eServer or real backend) on :8080 with EXTENSION_ORIGIN_ALLOWLIST for the unpacked ID,
tools/fake_trello.py on :9999, and `pip install playwright` with a Chromium build. Drives the extension page exactly
as a user would: register → paste → analyse → review → OAuth connect (fake Trello) → Board/List → member mapping →
deadline suggestions → confirm "Tạo N card" → results; then a timeout-after-create fault → UNKNOWN → reconcile;
then delete the meeting. Screenshots go to --shots.
"""
import argparse
import json
import os
import re
import sys
import time
import urllib.request

from playwright.sync_api import expect, sync_playwright

TRANSCRIPT = "\n".join([
    "Nam: Chào mọi người, bắt đầu họp sprint.",
    "Nam: Mai hoàn thành màn hình đăng nhập trước thứ Sáu nhé.",
    "Nam: Long sửa Payment API nhé.",
    "Mai: Em nhận.",
    "Nam: Payment API đang lỗi timeout.",
])


def admin(base, path, body=None):
    req = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(), method="POST" if body is not None else "GET")
    req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ext", required=True)
    ap.add_argument("--trello", default="http://127.0.0.1:9999")
    ap.add_argument("--shots", default="e2e-shots")
    ap.add_argument("--headed", action="store_true")
    args = ap.parse_args()
    os.makedirs(args.shots, exist_ok=True)
    admin(args.trello, "/__admin/reset", {})
    step = [0]
    with sync_playwright() as p:
        context = p.chromium.launch_persistent_context(os.path.join(args.shots, "profile"), headless=not args.headed, channel="chromium",
                                                       args=["--disable-extensions-except=" + args.ext, "--load-extension=" + args.ext],
                                                       viewport={"width": 400, "height": 900}, locale="vi-VN", timezone_id="Asia/Ho_Chi_Minh")
        worker = context.service_workers[0] if context.service_workers else context.wait_for_event("serviceworker", timeout=15000)
        ext_id = worker.url.split("/")[2]
        page = context.new_page()
        errors = []
        page.on("pageerror", lambda e: errors.append(str(e)))
        page.on("console", lambda m: errors.append(m.text) if m.type == "error" else None)

        def shot(name):
            step[0] += 1
            page.screenshot(path=os.path.join(args.shots, "%02d-%s.png" % (step[0], name)), full_page=True)

        page.goto("chrome-extension://%s/sidepanel.html" % ext_id)
        print("extension id", ext_id)
        # 1. Register + login
        page.get_by_role("button", name="Chưa có tài khoản? Đăng ký").click()
        page.get_by_label("Email").fill("e2e-%d@example.test" % int(time.time()))
        page.get_by_label("Mật khẩu").fill("e2e-password-123")
        page.get_by_role("button", name="Đăng ký và đăng nhập").click()
        expect(page.get_by_role("button", name="Đăng xuất")).to_be_visible(timeout=20000)
        # 2. Paste transcript with meeting date
        page.get_by_label("Tiêu đề").fill("Sprint planning E2E")
        page.get_by_label("Ngày họp").fill("2026-10-05")
        page.get_by_label("Múi giờ").fill("Asia/Ho_Chi_Minh")
        page.get_by_label("Nội dung").fill(TRANSCRIPT)
        page.get_by_role("button", name="Lưu & xem preview").click()
        expect(page.get_by_text("Input đã lưu trên backend")).to_be_visible(timeout=15000)
        shot("preview")
        # 3. Analyse
        page.get_by_label("Chọn AI").select_option("openai")
        page.get_by_role("button", name="Phân tích", exact=True).click()
        expect(page.get_by_role("heading", name="Hoàn thành màn hình đăng nhập")).to_be_visible(timeout=30000)
        expect(page.get_by_role("heading", name="Sửa Payment API")).to_be_visible()
        shot("review")
        # 4. Connect Trello with OAuth (fake Trello consent page opens in a new tab)
        with context.expect_page(timeout=15000) as popup_info:
            page.get_by_role("button", name="Kết nối Trello (OAuth)").click()
        popup = popup_info.value
        popup.wait_for_load_state()
        assert "code_challenge_method=S256" in popup.url, popup.url
        popup.get_by_role("button", name="Allow").click()
        expect(popup.get_by_text("Đã kết nối Trello")).to_be_visible(timeout=15000)
        popup.close()
        expect(page.get_by_text("Đã kết nối:")).to_be_visible(timeout=15000)
        # 5. Destination
        page.get_by_label("Board").select_option(label="Demo Board")
        page.get_by_label("List").select_option(label="To Do")
        page.get_by_role("button", name="Lưu nơi tạo card").click()
        expect(page.get_by_text("Nơi tạo card:")).to_be_visible(timeout=15000)
        # 6. Member mapping + deadline suggestions
        page.get_by_role("button", name="Đối chiếu người phụ trách").click()
        expect(page.get_by_text(re.compile("gợi ý Nguyễn Thị Mai"))).to_be_visible(timeout=15000)
        expect(page.get_by_text(re.compile("nhiều thành viên trùng tên"))).to_be_visible()
        page.get_by_role("button", name="Gợi ý hạn từ ngày họp").click()
        page.wait_for_timeout(800)
        shot("trello-mapping")
        cards = page.locator("article.task-card")
        mai = cards.filter(has_text="Hoàn thành màn hình đăng nhập")
        mai.get_by_role("button", name="Sửa").click()
        mai.get_by_role("button", name=re.compile("Xác nhận gợi ý")).click()
        mai.get_by_role("button", name=re.compile("Dùng gợi ý")).click()
        expect(mai.get_by_text("Hạn sẽ dùng: Thứ Sáu, 09/10/2026 lúc 17:00 (Asia/Ho_Chi_Minh)")).to_be_visible()
        mai.get_by_role("button", name="Thu gọn").click()
        long = cards.filter(has_text="Sửa Payment API")
        long.get_by_role("button", name="Sửa").click()
        long.get_by_label("Thành viên Trello").select_option(label="Trần Long (@longtran) — gợi ý theo tên")
        long.get_by_label("Không đặt hạn").check()
        long.get_by_role("button", name="Thu gọn").click()
        expect(page.get_by_role("button", name="Tạo 2 card trên Trello")).to_be_enabled(timeout=15000)
        shot("ready")
        # 7. Confirm and create
        page.get_by_role("button", name="Tạo 2 card trên Trello").click()
        expect(page.get_by_text("Tạo 2 card trong Demo Board › To Do")).to_be_visible()
        shot("confirm")
        page.get_by_role("button", name="Xác nhận tạo 2 card").click()
        expect(page.get_by_text("Đã tạo xong")).to_be_visible(timeout=30000)
        state = admin(args.trello, "/__admin/state")
        assert len(state["cards"]) == 2, state
        by_name = {c["name"]: c for c in state["cards"]}
        assert by_name["Hoàn thành màn hình đăng nhập"]["idMembers"] == "m000000000000000000000a1"
        assert by_name["Hoàn thành màn hình đăng nhập"]["due"] == "2026-10-09T10:00:00Z", by_name
        assert by_name["Sửa Payment API"]["idMembers"] == "m000000000000000000000a2" and by_name["Sửa Payment API"]["due"] is None
        assert all("AI_MTT_REF=" in c["desc"] for c in state["cards"])
        shot("synced")
        # 8. UNKNOWN: manual task, create times out after Trello made the card → reconcile, never re-create
        page.get_by_role("button", name="+ Thêm task thủ công").click()
        page.get_by_label("Tên công việc").last.fill("Gửi biên bản họp")
        page.locator("form.manual").get_by_label("Không giao người").check()
        page.locator("form.manual").get_by_label("Không đặt hạn").check()
        page.get_by_role("button", name="Thêm task").click()
        expect(page.get_by_role("button", name="Tạo 1 card trên Trello")).to_be_enabled(timeout=15000)
        admin(args.trello, "/__admin/fault", {"mode": "timeout_after_create", "count": 1})
        page.get_by_role("button", name="Tạo 1 card trên Trello").click()
        page.get_by_role("button", name="Xác nhận tạo 1 card").click()
        expect(page.get_by_text("Chưa rõ kết quả")).to_be_visible(timeout=30000)
        expect(page.get_by_role("button", name="Thử lại")).to_have_count(0)
        shot("unknown")
        time.sleep(4)  # the fake finishes its slow response; the card exists
        page.get_by_role("button", name="Đối soát ngay").click()
        expect(page.locator(".sync-item.synced").filter(has_text="Gửi biên bản họp")).to_be_visible(timeout=20000)
        state = admin(args.trello, "/__admin/state")
        assert len(state["cards"]) == 3 and state["createCalls"] == 3, state
        shot("reconciled")
        # 9. Reload panel → state restored from backend
        page.reload()
        expect(page.get_by_text("Đã tạo xong")).to_be_visible(timeout=20000)
        expect(page.get_by_text("Card đã tạo; chỉ xem và mở trên Trello.").first).to_be_visible()
        # 10. Delete meeting (cards stay on Trello)
        page.once("dialog", lambda d: d.accept())
        page.get_by_role("button", name="Xóa meeting").click()
        expect(page.get_by_text("Đã xóa meeting")).to_be_visible(timeout=15000)
        assert len(admin(args.trello, "/__admin/state")["cards"]) == 3
        shot("deleted")
        context.close()
    relevant = [e for e in errors if "favicon" not in e]
    print("console errors:", relevant)
    print("E2E PASS")


if __name__ == "__main__":
    sys.exit(main())
