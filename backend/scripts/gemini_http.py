"""Verified HTTPS and bounded, non-secret diagnostics shared by local Gemini checks."""
import http.client
import json
import os
import socket
import ssl
import urllib.error
import urllib.request


def tls_context():
    context = ssl.create_default_context()
    source = "DEFAULT"
    # Respect explicitly configured trust stores and lazily loaded default CA directories.
    paths = ssl.get_default_verify_paths()
    explicit = bool(os.environ.get("SSL_CERT_FILE") or os.environ.get("SSL_CERT_DIR"))
    if not explicit and not paths.capath and context.cert_store_stats()["x509_ca"] == 0:
        try:
            import certifi
        except ImportError:
            pass
        else:
            context.load_verify_locations(cafile=certifi.where())
            source = "CERTIFI_FALLBACK"
    return context, source


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def client():
    context, source = tls_context()
    print(f"TLS_CA_SOURCE={source}; loaded_CA_count={context.cert_store_stats()['x509_ca']}")
    return urllib.request.build_opener(NoRedirect(), urllib.request.HTTPSHandler(context=context))


ERROR_TYPES = (urllib.error.URLError, OSError, ValueError, AttributeError, TypeError, http.client.HTTPException)


def diagnostic(error):
    cause = error.reason if isinstance(error, urllib.error.URLError) else error
    if isinstance(cause, ssl.SSLCertVerificationError):
        return "TLS_CERTIFICATE_VERIFY_FAILED; Python không xác minh được chuỗi chứng chỉ HTTPS."
    if isinstance(cause, ssl.SSLError):
        return "TLS_HANDSHAKE_FAILED; kết nối TLS thất bại."
    if isinstance(cause, socket.gaierror):
        return "DNS_RESOLUTION_FAILED; không phân giải được hostname."
    if isinstance(cause, TimeoutError):
        return "REQUEST_TIMEOUT; hết thời gian chờ kết nối/phản hồi."
    if isinstance(cause, PermissionError):
        return "LOCAL_NETWORK_PERMISSION_DENIED; môi trường chặn quyền kết nối."
    if isinstance(cause, ConnectionRefusedError):
        return "CONNECTION_REFUSED; kết nối bị từ chối."
    if isinstance(cause, ConnectionError):
        return "CONNECTION_INTERRUPTED; kết nối bị ngắt."
    if isinstance(cause, http.client.HTTPException):
        return "HTTP_RESPONSE_READ_FAILED; không đọc được phản hồi HTTP đầy đủ."
    if isinstance(cause, (json.JSONDecodeError, UnicodeError)):
        return "INVALID_JSON_RESPONSE; phản hồi không phải JSON hợp lệ."
    if isinstance(cause, (AttributeError, TypeError, ValueError)):
        return "UNEXPECTED_RESPONSE_STRUCTURE; cấu trúc phản hồi không như dự kiến."
    return "NETWORK_ERROR_UNCLASSIFIED; chưa phân loại được lỗi kết nối."
