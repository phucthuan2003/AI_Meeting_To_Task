#!/usr/bin/env python3
"""Run the dataset through the real backend pipeline and save predictions for evaluate.py (SDS §9).

    EVAL_EMAIL=eval@example.test EVAL_PASSWORD='...' python3 evaluation/run_eval.py \
        --base-url http://127.0.0.1:8080 --provider gemini --dataset evaluation/dataset/seed_v1.json \
        --split dev --out evaluation/out/predictions-dev.json --delete-after

Uses a dedicated evaluation account. Each transcript becomes a meeting, one analysis job is started with the chosen
provider and polled until terminal. Predictions record task name/assignee/deadline/priority, the AI-proposed due time
and evidence line numbers (segment sequence + 1). With --delete-after the meeting is deleted afterwards.
Transcripts are synthetic; never point this at a real account's data. Sending them to a provider may incur cost.
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from eval_lib import load_dataset  # noqa: E402


class Api:
    def __init__(self, base):
        self.base = base.rstrip("/")
        self.token = None

    def call(self, method, path, body=None, key=None):
        data = None if body is None else json.dumps(body).encode("utf-8")
        request = urllib.request.Request(self.base + path, data=data, method=method)
        request.add_header("Accept", "application/json")
        if data is not None:
            request.add_header("Content-Type", "application/json")
        if self.token:
            request.add_header("Authorization", "Bearer " + self.token)
        if key:
            request.add_header("Idempotency-Key", key)
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                raw = response.read()
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as error:
            payload = error.read().decode("utf-8", "replace")
            try:
                payload = json.loads(payload)
            except ValueError:
                payload = {"message": payload[:200]}
            raise RuntimeError("HTTP %s %s %s" % (error.code, payload.get("code"), payload.get("message"))) from None

    def login(self, email, password):
        self.token = self.call("POST", "/api/v1/auth/login", {"email": email, "password": password})["accessToken"]


def predict(api, item, provider, poll_seconds, timeout_seconds):
    body = {"title": "EVAL " + item["id"], "transcriptText": item["transcript"]}
    if item.get("meeting_date"):
        body["meetingDate"] = item["meeting_date"]
    if item.get("timezone"):
        body["timezone"] = item["timezone"]
    meeting = api.call("POST", "/api/v1/meetings", body)
    policies = {p["providerId"]: p for p in api.call("GET", "/api/v1/analysis-policies")}
    policy = policies[provider]
    if not policy["providerReady"]:
        raise RuntimeError("Provider %s chưa có API key ở backend" % provider)
    started = time.time()
    job = api.call("POST", "/api/v1/meetings/%s/analysis-jobs" % meeting["meetingId"],
                   {"expectedInputVersion": meeting["inputVersion"], "providerId": provider, "processingPolicyId": policy["processingPolicyId"]},
                   key="eval-%s-%d" % (item["id"], int(started)))
    while job["status"] in ("QUEUED", "PROCESSING", "CANCEL_REQUESTED"):
        if time.time() - started > timeout_seconds:
            api.call("POST", "/api/v1/jobs/%s/cancel" % job["jobId"])
            break
        time.sleep(poll_seconds)
        job = api.call("GET", "/api/v1/jobs/%s" % job["jobId"])
    result = {"id": item["id"], "meetingId": meeting["meetingId"], "status": job["status"], "error": (job.get("error") or {}).get("code"),
              "wallMs": int((time.time() - started) * 1000), "model": job.get("model"), "promptVersion": job.get("promptVersion"),
              "schemaVersion": job.get("schemaVersion"), "chunks": job.get("totalChunks"), "tasks": []}
    if job["status"] == "COMPLETED":
        listing = api.call("GET", "/api/v1/meetings/%s/tasks?analysisJobId=%s" % (meeting["meetingId"], job["jobId"]))
        analysis = listing.get("analysis") or {}
        result.update({"latencyMs": analysis.get("latencyMs"), "inputTokens": analysis.get("inputTokens"), "outputTokens": analysis.get("outputTokens")})
        for task in listing["tasks"]:
            if task["origin"] != "AI":
                continue
            suggestion = task.get("aiSuggestion") or {}
            result["tasks"].append({"name": task["taskName"], "assignee": suggestion.get("assigneeRaw"), "deadline_raw": suggestion.get("deadlineRaw"),
                                    "due_local": suggestion.get("dueLocal"), "priority": suggestion.get("priority"),
                                    "evidence_lines": sorted({e["sequence"] + 1 for e in task["evidence"]})})
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--provider", required=True, choices=["openai", "gemini"])
    parser.add_argument("--dataset", required=True)
    parser.add_argument("--split", choices=["dev", "test", "all"], default="dev")
    parser.add_argument("--out", required=True)
    parser.add_argument("--poll-seconds", type=float, default=2)
    parser.add_argument("--timeout-seconds", type=float, default=300)
    parser.add_argument("--delete-after", action="store_true")
    args = parser.parse_args()
    email, password = os.environ.get("EVAL_EMAIL"), os.environ.get("EVAL_PASSWORD")
    if not email or not password:
        sys.exit("Đặt EVAL_EMAIL và EVAL_PASSWORD cho tài khoản đánh giá riêng.")
    dataset = load_dataset(args.dataset)
    api = Api(args.base_url)
    api.login(email, password)
    items = [i for i in dataset["items"] if args.split == "all" or i["split"] == args.split]
    output = {"run": {"provider": args.provider, "split": args.split, "datasetVersion": dataset["datasetVersion"], "startedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z")}, "items": []}
    for item in items:
        try:
            prediction = predict(api, item, args.provider, args.poll_seconds, args.timeout_seconds)
        except RuntimeError as error:
            prediction = {"id": item["id"], "status": "ERROR", "error": str(error), "tasks": []}
        output["items"].append(prediction)
        output["run"].update({k: prediction.get(k) for k in ("model", "promptVersion", "schemaVersion") if prediction.get(k)})
        print("%-24s %-14s tasks=%d %s" % (item["id"], prediction["status"], len(prediction["tasks"]), prediction.get("error") or ""))
        if args.delete_after and prediction.get("meetingId"):
            try:
                api.call("DELETE", "/api/v1/meetings/%s" % prediction["meetingId"])
            except RuntimeError as error:
                print("  không xóa được meeting:", error)
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump(output, handle, ensure_ascii=False, indent=2)
    print("Đã lưu", args.out)


if __name__ == "__main__":
    main()
