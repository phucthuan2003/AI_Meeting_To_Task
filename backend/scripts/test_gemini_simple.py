import os
import requests

API_KEY = os.getenv("GEMINI_API_KEY")

if not API_KEY:
    raise RuntimeError("Chưa cấu hình GEMINI_API_KEY")

url = (
    "https://generativelanguage.googleapis.com/v1beta/"
    "models/gemini-3.8-flash:generateContent"
)

payload = {
    "contents": [
        {
            "role": "user",
            "parts": [
                {"text": "1 + 1 bằng bao nhiêu? Trả lời ngắn gọn."}
            ]
        }
    ]
}

response = requests.post(
    url,
    headers={
        "x-goog-api-key": API_KEY,
        "Content-Type": "application/json"
    },
    json=payload,
    timeout=60
)

print("HTTP STATUS:", response.status_code)

try:
    data = response.json()

    if response.ok:
        print("GEMINI RESPONSE:")
        print(data["candidates"][0]["content"]["parts"][0]["text"])
    else:
        print("API ERROR:")
        print(data)

except (ValueError, KeyError, IndexError, TypeError) as e:
    print("Lỗi đọc response:", e)
    print(response.text[:2000])