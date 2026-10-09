import os
import time
import random
import requests

API_KEY = os.getenv("GEMINI_API_KEY")

if not API_KEY:
    raise RuntimeError("Chưa cấu hình GEMINI_API_KEY")

MODEL = "gemini-3.6-flash"

url = (
    f"https://generativelanguage.googleapis.com/v1beta/"
    f"models/{MODEL}:generateContent"
)

payload = {
    "contents": [
        {
            "role": "user",
            "parts": [
                {
                    "text": (
                        "Nam: Mai hoàn thành màn hình đăng nhập "
                        "trước 17:00 ngày 12/10/2026. "
                        "Hãy chuyển thành một task bàn giao công việc."
                    )
                }
            ]
        }
    ]
}

headers = {
    "x-goog-api-key": API_KEY,
    "Content-Type": "application/json"
}

for attempt in range(1, 5):
    print(f"\nĐang thử lần {attempt}/4...")

    try:
        response = requests.post(
            url,
            headers=headers,
            json=payload,
            timeout=60
        )

        print("HTTP STATUS:", response.status_code)
        data = response.json()

        if response.ok:
            parts = data["candidates"][0]["content"]["parts"]
            answer = "".join(
                part.get("text", "") for part in parts
            )

            print("\nGEMINI RESPONSE:")
            print(answer or "Không có nội dung văn bản.")
            break

        print("API ERROR:", data.get("error", data))

        if response.status_code not in (408, 429, 500, 502, 503, 504):
            break

    except requests.RequestException as e:
        print("NETWORK ERROR:", e)

    except ValueError as e:
        print("JSON ERROR:", e)
        break

    if attempt < 4:
        delay = min(2 ** (attempt - 1), 8) + random.uniform(0, 1)
        print(f"Thử lại sau {delay:.1f} giây...")
        time.sleep(delay)

else:
    print("\nKhông thành công sau 4 lần thử.")