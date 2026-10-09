import base64
import hashlib
import json
import os
import sys
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from urllib.parse import parse_qs, urlencode, urlparse

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import fake_trello  # noqa: E402


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


class FakeTrelloTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), fake_trello.Handler)
        cls.server.client_secret, cls.server.timeout_sleep = "demo-secret", "0.1"
        cls.base = "http://127.0.0.1:%d" % cls.server.server_address[1]
        fake_trello.Handler.base = cls.base
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()

    def setUp(self):
        fake_trello.STATE.reset()

    def call(self, method, path, body=None, token=fake_trello.DEMO_TOKEN, form=False):
        data = None if body is None else (urlencode(body) if form else json.dumps(body)).encode()
        req = urllib.request.Request(self.base + path, data=data, method=method)
        if token:
            req.add_header("Authorization", "Bearer " + token)
        if data is not None:
            req.add_header("Content-Type", "application/x-www-form-urlencoded" if form else "application/json")
        try:
            with urllib.request.build_opener(NoRedirect).open(req, timeout=5) as r:
                return r.status, r.read(), dict(r.headers)
        except urllib.error.HTTPError as e:
            return e.code, e.read(), dict(e.headers)

    def test_api_subset_and_faults(self):
        self.assertEqual(self.call("GET", "/1/members/me", token="wrong")[0], 401)
        status, body, _ = self.call("GET", "/1/members/me/boards")
        self.assertEqual(status, 200)
        self.assertEqual(len(json.loads(body)), 2)
        members = json.loads(self.call("GET", "/1/boards/%s/members" % fake_trello.BOARD)[1])
        self.assertEqual(sum(1 for m in members if "Long" in m["fullName"]), 2)
        status, body, _ = self.call("POST", "/1/cards", {"name": "A", "desc": "AI_MTT_REF=x", "idList": fake_trello.LIST})
        self.assertEqual(status, 200)
        card = json.loads(body)
        self.assertTrue(card["url"].startswith(self.base + "/c/"))
        self.call("POST", "/__admin/fault", {"mode": "server_error_after_create", "count": 1}, token=None)
        self.assertEqual(self.call("POST", "/1/cards", {"name": "B", "desc": "", "idList": fake_trello.LIST})[0], 502)
        self.assertEqual(len(json.loads(self.call("GET", "/1/boards/%s/cards/all" % fake_trello.BOARD)[1])), 2)  # card exists despite 502
        self.call("POST", "/__admin/fault", {"mode": "rate_limit", "count": 1}, token=None)
        status, _, headers = self.call("POST", "/1/cards", {"name": "C", "idList": fake_trello.LIST})
        self.assertEqual(status, 429)
        self.assertEqual(headers.get("Retry-After"), "1")
        self.assertEqual(self.call("GET", "/1/cards/%s" % card["shortLink"])[0], 200)
        self.call("POST", "/__admin/revoke", token=None)
        self.assertEqual(self.call("GET", "/1/members/me")[0], 401)

    def test_oauth_pkce_flow_and_single_use_refresh(self):
        verifier = "v" * 50
        challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
        q = {"client_id": "demo-client", "scope": "read", "redirect_uri": "http://127.0.0.1:8080/cb", "state": "st", "response_type": "code",
             "code_challenge": challenge, "code_challenge_method": "S256"}
        self.assertEqual(self.call("GET", "/authorize?" + urlencode(q), token=None)[0], 200)
        self.assertEqual(self.call("GET", "/authorize?" + urlencode({**q, "code_challenge_method": "plain"}), token=None)[0], 400)
        status, _, headers = self.call("POST", "/authorize/decide", {**q, "decision": "allow"}, token=None, form=True)
        self.assertEqual(status, 302)
        location = parse_qs(urlparse(headers["Location"]).query)
        self.assertEqual(location["state"], ["st"])
        token_req = {"grant_type": "authorization_code", "client_id": "demo-client", "client_secret": "demo-secret", "code": location["code"][0],
                     "redirect_uri": "http://127.0.0.1:8080/cb", "code_verifier": "wrong"}
        self.assertEqual(self.call("POST", "/oauth/token", token_req, token=None)[0], 400)
        self.assertEqual(self.call("POST", "/authorize/decide", {**q, "decision": "allow"}, token=None, form=True)[0], 302)
        code = parse_qs(urlparse(self.call("POST", "/authorize/decide", {**q, "decision": "allow"}, token=None, form=True)[2]["Location"]).query)["code"][0]
        status, body, _ = self.call("POST", "/oauth/token", {**token_req, "code": code, "code_verifier": verifier}, token=None)
        self.assertEqual(status, 200)
        tokens = json.loads(body)
        self.assertEqual(self.call("GET", "/1/members/me", token=tokens["access_token"])[0], 200)
        refresh = {"grant_type": "refresh_token", "client_id": "demo-client", "client_secret": "demo-secret", "refresh_token": tokens["refresh_token"]}
        self.assertEqual(self.call("POST", "/oauth/token", refresh, token=None)[0], 200)
        self.assertEqual(self.call("POST", "/oauth/token", refresh, token=None)[0], 400)   # refresh tokens are single-use
        denied = self.call("POST", "/authorize/decide", {**q, "decision": "deny"}, token=None, form=True)[2]["Location"]
        self.assertIn("error=access_denied", denied)


if __name__ == "__main__":
    unittest.main()
