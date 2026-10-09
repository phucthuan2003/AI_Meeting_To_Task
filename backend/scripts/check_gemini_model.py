#!/usr/bin/env python3
"""Read Gemini model metadata without generating content or printing credentials."""
import json
import os
import re
import sys
import urllib.error
import urllib.request
import gemini_http

SAFE_STATUSES = {"INVALID_ARGUMENT", "NOT_FOUND", "PERMISSION_DENIED", "UNAUTHENTICATED", "FAILED_PRECONDITION", "RESOURCE_EXHAUSTED", "UNAVAILABLE", "INTERNAL", "DEADLINE_EXCEEDED"}


def main():
    model = os.environ.get("GEMINI_MODEL", "gemini-2.5-flash")
    key = os.environ.get("GEMINI_API_KEY", "")
    if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}", model):
        print("GEMINI_MODEL cần model ID đầy đủ, ví dụ gemini-3.8-flash, không phải URL.")
        return 2
    if not key or any(ord(c) < 33 or ord(c) > 126 for c in key):
        print("Chưa nạp GEMINI_API_KEY hợp lệ. Từ Code, nạp .env trước; không in key.")
        return 2
    request = urllib.request.Request(
        "https://generativelanguage.googleapis.com/v1beta/models/" + model,
        headers={"x-goog-api-key": key}, method="GET")
    print("Kiểm tra metadata model: " + model)
    try:
        client = gemini_http.client()
        with client.open(request, timeout=20) as response:
            value = json.loads(response.read(1048577))
            supported = "generateContent" in value.get("supportedGenerationMethods", [])
            print("HTTP 200; generateContent=" + str(supported).lower())
            print("Metadata đọc được; việc này chưa xác minh schema hoặc gọi phân tích AI.")
            return 0 if supported else 1
    except urllib.error.HTTPError as error:
        status = "UNSPECIFIED"
        try:
            value = json.loads(error.read(1048577)).get("error", {}).get("status")
            if value in SAFE_STATUSES:
                status = value
        except (ValueError, AttributeError, TypeError):
            pass
        print(f"HTTP {error.code}; vendorStatus={status}")
        return 1
    except gemini_http.ERROR_TYPES as error:
        print(gemini_http.diagnostic(error))
        return 1


if __name__ == "__main__":
    sys.exit(main())
