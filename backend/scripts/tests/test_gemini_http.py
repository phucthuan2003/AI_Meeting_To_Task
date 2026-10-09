import contextlib
import io
import json
import os
from pathlib import Path
import socket
import ssl
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
import urllib.error

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import gemini_http
import check_gemini_model
import probe_gemini_request


class GeminiHttpTest(unittest.TestCase):
    def test_wrapped_errors_are_distinct_without_echoing_sensitive_messages(self):
        for error, label in [
            (ssl.SSLCertVerificationError(1, "secret-key"), "TLS_CERTIFICATE_VERIFY_FAILED"),
            (socket.gaierror(-2, "secret-key"), "DNS_RESOLUTION_FAILED"),
            (TimeoutError("secret-key"), "REQUEST_TIMEOUT"),
            (PermissionError("secret-key"), "LOCAL_NETWORK_PERMISSION_DENIED"),
        ]:
            with self.subTest(label=label):
                output = gemini_http.diagnostic(urllib.error.URLError(error))
                self.assertIn(label, output)
                self.assertNotIn("secret-key", output)

    def test_empty_default_store_loads_certifi_with_certificate_verification_enabled(self):
        context = Mock()
        context.cert_store_stats.return_value = {"x509_ca": 0}
        context.check_hostname = True
        context.verify_mode = ssl.CERT_REQUIRED
        with patch.dict(os.environ, {}, clear=True), patch.object(gemini_http.ssl, "create_default_context", return_value=context), patch.object(gemini_http.ssl, "get_default_verify_paths", return_value=SimpleNamespace(capath=None)), patch.dict(sys.modules, {"certifi": SimpleNamespace(where=lambda: "/synthetic/ca.pem")}):
            result, source = gemini_http.tls_context()
        self.assertEqual(source, "CERTIFI_FALLBACK")
        context.load_verify_locations.assert_called_once_with(cafile="/synthetic/ca.pem")
        self.assertTrue(result.check_hostname)
        self.assertEqual(result.verify_mode, ssl.CERT_REQUIRED)

    def test_explicit_trust_store_is_not_replaced(self):
        context = Mock()
        context.cert_store_stats.return_value = {"x509_ca": 0}
        with patch.dict(os.environ, {"SSL_CERT_FILE": "/custom/ca.pem"}, clear=True), patch.object(gemini_http.ssl, "create_default_context", return_value=context):
            _, source = gemini_http.tls_context()
        self.assertEqual(source, "DEFAULT")
        context.load_verify_locations.assert_not_called()

    def test_default_certificates_are_reused(self):
        context = Mock()
        context.cert_store_stats.return_value = {"x509_ca": 143}
        with patch.dict(os.environ, {}, clear=True), patch.object(gemini_http.ssl, "create_default_context", return_value=context), patch.object(gemini_http.ssl, "get_default_verify_paths", return_value=SimpleNamespace(capath=None)):
            _, source = gemini_http.tls_context()
        self.assertEqual(source, "DEFAULT")
        context.load_verify_locations.assert_not_called()

    def test_redirect_cannot_forward_credentials(self):
        self.assertIsNone(gemini_http.NoRedirect().redirect_request(None, None, 302, "", {}, "https://other.example"))

    def run_script(self, module, outcome, args=()):
        client = Mock()
        if isinstance(outcome, Exception):
            client.open.side_effect = outcome
        else:
            client.open.return_value = io.BytesIO(outcome)
        output = io.StringIO()
        with patch.dict(os.environ, {"GEMINI_MODEL": "gemini-3.8-flash", "GEMINI_API_KEY": "synthetic-key"}, clear=True), patch.object(gemini_http, "client", return_value=client), contextlib.redirect_stdout(output):
            status = module.main(args) if module is probe_gemini_request else module.main()
        client.open.assert_called_once()
        self.assertNotIn("synthetic-key", output.getvalue())
        return status, output.getvalue(), client.open.call_args.args[0]

    def test_metadata_uses_get_and_reports_tls_error(self):
        status, output, request = self.run_script(check_gemini_model, urllib.error.URLError(ssl.SSLCertVerificationError(1, "synthetic-key")))
        self.assertEqual(status, 1)
        self.assertIn("TLS_CERTIFICATE_VERIFY_FAILED", output)
        self.assertEqual(request.get_method(), "GET")
        self.assertIsNone(request.data)

    def test_probe_invalid_json_is_not_labelled_a_network_error(self):
        status, output, _ = self.run_script(probe_gemini_request, b"<html>synthetic-key</html>")
        self.assertEqual(status, 1)
        self.assertIn("INVALID_JSON_RESPONSE", output)

    def test_http_rejection_stays_separate_from_transport_error(self):
        error = urllib.error.HTTPError("https://example.test", 400, "synthetic-key", {}, io.BytesIO(json.dumps({"error": {"status": "INVALID_ARGUMENT", "details": [{"reason": "API_KEY_INVALID"}]}}).encode()))
        status, output, _ = self.run_script(probe_gemini_request, error)
        self.assertEqual(status, 1)
        self.assertIn("HTTP 400", output)
        self.assertIn("reason=API_KEY_INVALID", output)

    def test_details_show_unknown_cause_with_current_key_removed(self):
        error = urllib.error.HTTPError("https://example.test", 400, "", {}, io.BytesIO(json.dumps({"error": {"status": "INVALID_ARGUMENT", "message": "Requested output mode unsupported; credential synthetic-key"}}).encode()))
        status, output, _ = self.run_script(probe_gemini_request, error, ["--details"])
        self.assertEqual(status, 1)
        self.assertIn("vendorMessage=Requested output mode unsupported", output)
        self.assertIn("[REDACTED]", output)

    def test_details_redact_encoded_keys_and_bound_multiline_output(self):
        key = "synthetic/key+value"
        message = "Invalid mode: " + key + " synthetic%2Fkey%2Bvalue Bearer secret-token\r\n" + "x" * 2000
        details = probe_gemini_request.redacted_error_details({"error": {"message": message}}, key)
        output = details[0]
        self.assertNotIn(key, output)
        self.assertNotIn("synthetic%2Fkey%2Bvalue", output)
        self.assertNotIn("secret-token", output)
        self.assertNotIn("\n", output)
        self.assertLessEqual(len(output), 1500)

    def test_default_probe_keeps_free_form_details_hidden(self):
        error = urllib.error.HTTPError("https://example.test", 400, "", {}, io.BytesIO(b'{"error":{"status":"INVALID_ARGUMENT","message":"free form vendor detail"}}'))
        _, output, _ = self.run_script(probe_gemini_request, error)
        self.assertNotIn("vendorMessage=", output)
        self.assertNotIn("free form vendor detail", output)

    def test_isolation_stops_after_failed_baseline(self):
        with patch.object(probe_gemini_request, "send_probe", return_value=400) as send, contextlib.redirect_stdout(io.StringIO()):
            result = probe_gemini_request.isolate(Mock(), "gemini-3.8-flash", "synthetic-key", {}, True)
        self.assertEqual(result, 1)
        self.assertEqual(send.call_count, 1)
        self.assertNotIn("responseFormat", send.call_args.args[3]["generationConfig"])

    def test_isolation_compares_formats_and_caps_calls_without_mutating_payload(self):
        payload = probe_gemini_request.structured_probe_payload("gemini-3.8-flash")
        original = json.dumps(payload, sort_keys=True)
        output = io.StringIO()
        with patch.object(probe_gemini_request, "send_probe", side_effect=[200, 400, 200, 200, 200, 200]) as send, contextlib.redirect_stdout(output):
            result = probe_gemini_request.isolate(Mock(), "gemini-3.8-flash", "synthetic-key", payload, True)
        self.assertEqual(result, 0)
        self.assertEqual(send.call_count, 6)
        calls = send.call_args_list
        enum_config = calls[1].args[3]["generationConfig"]
        literal_config = calls[2].args[3]["generationConfig"]
        self.assertEqual(enum_config["responseFormat"]["text"]["mimeType"], "APPLICATION_JSON")
        self.assertEqual(literal_config["responseFormat"]["text"]["mimeType"], "application/json")
        self.assertEqual(enum_config["responseFormat"]["text"]["schema"], literal_config["responseFormat"]["text"]["schema"])
        self.assertNotIn("responseFormat", calls[3].args[3]["generationConfig"])
        self.assertEqual(calls[-1].args[3]["generationConfig"]["responseFormat"]["text"]["mimeType"], "application/json")
        self.assertEqual(original, json.dumps(payload, sort_keys=True))
        self.assertIn("ISOLATION=FULL_PROBE_ACCEPTED", output.getvalue())

    def test_isolation_stops_on_rate_limit_without_trying_more_formats(self):
        with patch.object(probe_gemini_request, "send_probe", side_effect=[200, 429]) as send, contextlib.redirect_stdout(io.StringIO()):
            result = probe_gemini_request.isolate(Mock(), "gemini-3.8-flash", "synthetic-key", {}, False)
        self.assertEqual(result, 1)
        self.assertEqual(send.call_count, 2)

    def test_full_schema_rejection_does_not_send_full_input_or_retry(self):
        payload = probe_gemini_request.structured_probe_payload("gemini-3.8-flash")
        output = io.StringIO()
        with patch.object(probe_gemini_request, "send_probe", side_effect=[200, 200, 400, 400, 400]) as send, contextlib.redirect_stdout(output):
            result = probe_gemini_request.isolate(Mock(), "gemini-3.8-flash", "synthetic-key", payload, True)
        self.assertEqual(result, 1)
        self.assertEqual(send.call_count, 5)
        self.assertIn("ISOLATION=FULL_SCHEMA_REJECTED", output.getvalue())

    def test_default_probe_uses_text_schema_and_keeps_output_limit(self):
        payload = probe_gemini_request.probe_payload("gemini-3.8-flash")
        self.assertEqual(set(payload), {"contents", "generationConfig"})
        self.assertEqual(payload["generationConfig"], {"maxOutputTokens": 4096})
        prompt = payload["contents"][0]["parts"][0]["text"]
        self.assertIn("JSON SCHEMA:", prompt)
        self.assertIn('"evidence_refs"', prompt)
        self.assertIn('"maxLength"', prompt)
        self.assertIn("00000000-0000-4000-8000-000000000001", prompt)


if __name__ == "__main__":
    unittest.main()
