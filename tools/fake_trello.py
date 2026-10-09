#!/usr/bin/env python3
"""Local Trello double for demos and fault drills (SDS §12.1, §14.8). Python stdlib only; never use with real data.

Implements the subset of the Trello REST API used by the backend, an OAuth 2.0 authorize/token flow with PKCE,
a small HTML viewer for cards, and admin endpoints that inject faults (timeout/502 after the card is created,
400, 429, 401) so the UNKNOWN → reconcile path can be demonstrated without touching a real Board.

    python3 tools/fake_trello.py --port 9999

Backend environment for the demo (see docs/ai-meeting-to-task/Demo_Script.md):
    TRELLO_API_BASE_URL=http://127.0.0.1:9999/1  TRELLO_ALLOW_LOCAL_HTTP=true  TRELLO_API_KEY=demo-key
    TRELLO_AUTHORIZE_URL=http://127.0.0.1:9999/authorize  TRELLO_TOKEN_URL=http://127.0.0.1:9999/oauth/token
    TRELLO_CLIENT_ID=demo-client  TRELLO_CLIENT_SECRET=demo-secret
    TRELLO_CALLBACK_URL=http://127.0.0.1:8080/api/v1/trello/oauth/callback
"""
import argparse
import base64
import hashlib
import html
import json
import secrets
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlencode, urlparse

DEMO_TOKEN = "demotoken0000000000000000000000000000000000000000000000000000001"
ME = {"id": "u0000000000000000000000me", "username": "demo", "fullName": "Demo User"}
BOARD, OTHER_BOARD = "b0000000000000000000000a", "b0000000000000000000000b"
LIST, DOING, OTHER_LIST = "l0000000000000000000000a", "l0000000000000000000000d", "l0000000000000000000000b"
MEMBERS = [
    {"id": "m000000000000000000000a1", "username": "mainguyen", "fullName": "Nguyễn Thị Mai"},
    {"id": "m000000000000000000000a2", "username": "longtran", "fullName": "Trần Long"},
    {"id": "m000000000000000000000a3", "username": "longpham", "fullName": "Phạm Long"},
    {"id": "m000000000000000000000a4", "username": "namle", "fullName": "Lê Nam"},
]


class State:
    def __init__(self):
        self.lock = threading.Lock()
        self.reset()

    def reset(self):
        self.tokens = {DEMO_TOKEN}
        self.cards = []
        self.fault = {"mode": "none", "count": 0}
        self.codes = {}     # code -> (challenge, redirect_uri)
        self.refresh = {}   # refresh token -> access token
        self.create_calls = 0

    def take_fault(self):
        with self.lock:
            if self.fault["count"] <= 0:
                return "none"
            self.fault["count"] -= 1
            mode = self.fault["mode"]
            if self.fault["count"] == 0:
                self.fault["mode"] = "none"
            return mode


STATE = State()
BOARDS = {BOARD: "Demo Board", OTHER_BOARD: "Sprint khác"}
LISTS = {LIST: ("To Do", BOARD), DOING: ("Doing", BOARD), OTHER_LIST: ("Backlog", OTHER_BOARD)}


def s256(verifier):
    return base64.urlsafe_b64encode(hashlib.sha256(verifier.encode("ascii")).digest()).rstrip(b"=").decode()


class Handler(BaseHTTPRequestHandler):
    server_version = "FakeTrello/1.0"
    base = "http://127.0.0.1:9999"

    def log_message(self, fmt, *args):  # keep demo output short; never print headers (tokens)
        print("fake-trello %s %s" % (self.command, urlparse(self.path).path))

    # ---------- helpers ----------
    def send_json(self, status, body, headers=None):
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def send_html(self, status, body):
        data = ("<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>"
                "<style>body{font-family:system-ui;max-width:760px;margin:24px auto;padding:0 16px;color:#203731}"
                ".card{border:1px solid #dce5e1;border-radius:10px;padding:12px;margin:10px 0}pre{white-space:pre-wrap}"
                "button{padding:8px 14px;margin-right:8px}</style>" + body).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def body(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        if not raw:
            return {}
        if "json" in (self.headers.get("Content-Type") or ""):
            return json.loads(raw.decode("utf-8"))
        return {k: v[0] for k, v in parse_qs(raw.decode("utf-8")).items()}

    def authorized(self):
        header = self.headers.get("Authorization") or ""
        return any(header == "Bearer " + t or 'oauth_token="%s"' % t in header for t in STATE.tokens)

    def board(self, board_id):
        return {"id": board_id, "name": BOARDS[board_id], "closed": False, "url": "%s/b/%s" % (self.base, board_id)}

    def trello_list(self, list_id):
        name, board = LISTS[list_id]
        return {"id": list_id, "name": name, "closed": False, "idBoard": board}

    # ---------- routing ----------
    def do_GET(self):
        url = urlparse(self.path)
        parts = [p for p in url.path.split("/") if p]
        query = {k: v[0] for k, v in parse_qs(url.query).items()}
        if url.path == "/":
            return self.viewer()
        if url.path == "/authorize":
            return self.authorize_page(query)
        if url.path == "/__admin/state":
            return self.send_json(200, {"cards": STATE.cards, "fault": STATE.fault, "createCalls": STATE.create_calls})
        if len(parts) == 2 and parts[0] == "c":
            card = next((c for c in STATE.cards if c["shortLink"] == parts[1]), None)
            if not card:
                return self.send_html(404, "<h1>Không có card</h1>")
            return self.send_html(200, "<p><a href='/'>← Board</a></p><div class=card><h2>%s</h2><pre>%s</pre><p>Due: %s · Members: %s</p></div>" % (
                html.escape(card["name"]), html.escape(card["desc"]), html.escape(str(card.get("due"))), html.escape(card.get("idMembers") or "-")))
        if len(parts) == 2 and parts[0] == "b":
            return self.viewer()
        if not parts or parts[0] != "1":
            return self.send_json(404, {"message": "not found"})
        if not self.authorized():
            return self.send_json(401, {"message": "invalid token"})
        p = parts[1:]
        if p == ["members", "me"]:
            return self.send_json(200, ME)
        if p == ["members", "me", "boards"]:
            return self.send_json(200, [self.board(b) for b in BOARDS])
        if len(p) == 2 and p[0] == "boards":
            return self.send_json(200, self.board(p[1])) if p[1] in BOARDS else self.send_json(404, {})
        if len(p) == 3 and p[0] == "boards" and p[2] == "lists":
            return self.send_json(200, [self.trello_list(l) for l, (_, b) in LISTS.items() if b == p[1]])
        if len(p) == 3 and p[0] == "boards" and p[2] == "members":
            return self.send_json(200, MEMBERS if p[1] == BOARD else MEMBERS[:1])
        if len(p) == 4 and p[0] == "boards" and p[2] == "cards":
            return self.send_json(200, [c for c in reversed(STATE.cards) if c["idBoard"] == p[1]])
        if len(p) == 2 and p[0] == "lists":
            return self.send_json(200, self.trello_list(p[1])) if p[1] in LISTS else self.send_json(404, {})
        if len(p) == 2 and p[0] == "cards":
            card = next((c for c in STATE.cards if p[1] in (c["id"], c["shortLink"])), None)
            return self.send_json(200, card) if card else self.send_json(404, {})
        return self.send_json(404, {"message": "not found"})

    def do_POST(self):
        url = urlparse(self.path)
        body = self.body()
        if url.path == "/__admin/fault":
            STATE.fault = {"mode": body.get("mode", "none"), "count": int(body.get("count", 1))}
            return self.send_json(200, STATE.fault)
        if url.path == "/__admin/reset":
            STATE.reset()
            return self.send_json(200, {"ok": True})
        if url.path == "/__admin/revoke":
            STATE.tokens.clear()
            return self.send_json(200, {"revoked": True})
        if url.path == "/__admin/restore-token":
            STATE.tokens.add(DEMO_TOKEN)
            return self.send_json(200, {"token": "restored"})
        if url.path == "/authorize/decide":
            return self.authorize_decide(body)
        if url.path == "/oauth/token":
            return self.token(body)
        if url.path == "/1/cards":
            if not self.authorized():
                return self.send_json(401, {"message": "invalid token"})
            return self.create_card(body)
        return self.send_json(404, {"message": "not found"})

    # ---------- cards ----------
    def create_card(self, body):
        STATE.create_calls += 1
        fault = STATE.take_fault()
        if fault == "reject":
            return self.send_json(400, {"message": "invalid value"})
        if fault == "rate_limit":
            return self.send_json(429, {"message": "rate limited"}, {"Retry-After": "1"})
        if fault == "auth":
            return self.send_json(401, {"message": "unauthorized"})
        list_id = body.get("idList")
        if list_id not in LISTS:
            return self.send_json(400, {"message": "invalid list"})
        n = len(STATE.cards) + 1
        short = "d%07d" % n
        card = {"id": "c%023d" % n, "shortLink": short, "url": "%s/c/%s" % (self.base, short), "shortUrl": "%s/c/%s" % (self.base, short),
                "name": body.get("name", ""), "desc": body.get("desc", ""), "idList": list_id, "idBoard": LISTS[list_id][1],
                "closed": False, "idMembers": body.get("idMembers", ""), "due": body.get("due")}
        with STATE.lock:
            STATE.cards.append(card)
        if fault == "timeout_after_create":
            time.sleep(float(self.server.timeout_sleep))  # backend gives up → UNKNOWN; the card does exist
        if fault == "server_error_after_create":
            return self.send_json(502, {"message": "bad gateway"})
        return self.send_json(200, card)

    # ---------- OAuth 2.0 + PKCE ----------
    def authorize_page(self, q):
        fields = "".join("<input type=hidden name='%s' value='%s'>" % (html.escape(k), html.escape(v)) for k, v in q.items())
        ok = q.get("response_type") == "code" and q.get("code_challenge_method") == "S256" and q.get("code_challenge") and q.get("state")
        if not ok:
            return self.send_html(400, "<h1>Yêu cầu không hợp lệ</h1><p>Thiếu PKCE S256 hoặc state.</p>")
        return self.send_html(200, "<h1>Fake Trello</h1><p>Ứng dụng <b>%s</b> xin quyền: <code>%s</code></p>"
                                   "<form method=post action='/authorize/decide'>%s<button name=decision value=allow>Allow</button>"
                                   "<button name=decision value=deny>Deny</button></form>" % (html.escape(q.get("client_id", "")), html.escape(q.get("scope", "")), fields))

    def authorize_decide(self, body):
        redirect = body.get("redirect_uri", "")
        if body.get("decision") != "allow":
            target = redirect + "?" + urlencode({"error": "access_denied", "state": body.get("state", "")})
        else:
            code = secrets.token_urlsafe(16)
            STATE.codes[code] = (body.get("code_challenge"), redirect)
            target = redirect + "?" + urlencode({"code": code, "state": body.get("state", "")})
        self.send_response(302)
        self.send_header("Location", target)
        self.end_headers()

    def token(self, body):
        grant = body.get("grant_type")
        if body.get("client_secret") != self.server.client_secret:
            return self.send_json(401, {"error": "invalid_client"})
        if grant == "authorization_code":
            entry = STATE.codes.pop(body.get("code"), None)
            if not entry or entry[0] != s256(body.get("code_verifier", "")) or entry[1] != body.get("redirect_uri"):
                return self.send_json(400, {"error": "invalid_grant"})
        elif grant == "refresh_token":
            if STATE.refresh.pop(body.get("refresh_token"), None) is None:
                return self.send_json(400, {"error": "invalid_grant"})
        else:
            return self.send_json(400, {"error": "unsupported_grant_type"})
        access, refresh = "oauthaccess" + secrets.token_hex(16), "oauthrefresh" + secrets.token_hex(16)
        STATE.tokens.add(access)
        STATE.refresh[refresh] = access
        return self.send_json(200, {"access_token": access, "refresh_token": refresh, "expires_in": 3600, "scope": "read:board:trello write:board:trello offline_access"})

    # ---------- viewer ----------
    def viewer(self):
        rows = []
        for list_id, (name, board) in LISTS.items():
            cards = "".join("<div class=card><a href='/c/%s'><b>%s</b></a><br><small>%s</small></div>" % (
                c["shortLink"], html.escape(c["name"]), html.escape((c.get("due") or "không hạn") + " · " + (c.get("idMembers") or "không giao")))
                for c in STATE.cards if c["idList"] == list_id)
            rows.append("<h2>%s › %s</h2>%s" % (html.escape(BOARDS[board]), html.escape(name), cards or "<p><i>Chưa có card</i></p>"))
        fault = html.escape(json.dumps(STATE.fault))
        self.send_html(200, "<h1>Fake Trello (demo)</h1><p>Lỗi đang bơm: <code>%s</code> · số lần gọi tạo card: %d</p>%s" % (fault, STATE.create_calls, "".join(rows)))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=9999)
    parser.add_argument("--client-secret", default="demo-secret")
    parser.add_argument("--timeout-sleep", default="25", help="seconds to hold a create response for timeout_after_create (> backend TRELLO_TIMEOUT)")
    args = parser.parse_args()
    Handler.base = "http://127.0.0.1:%d" % args.port
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.client_secret = args.client_secret
    server.timeout_sleep = args.timeout_sleep
    print("Fake Trello on http://127.0.0.1:%d  (viewer: /)  demo token: %s" % (args.port, DEMO_TOKEN))
    server.serve_forever()


if __name__ == "__main__":
    main()
