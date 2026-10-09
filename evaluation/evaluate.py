#!/usr/bin/env python3
"""Compute SDS §9.3 metrics from predictions produced by run_eval.py.

    python3 evaluation/evaluate.py --dataset evaluation/dataset/seed_v1.json --predictions evaluation/out/predictions.json \
        --out evaluation/out/report

Reports micro (whole dataset) and macro (per transcript) task precision/recall/F1, assignee and deadline accuracy
on matched tasks (null and ambiguous counted separately), evidence validity/support, cancellation accuracy,
unsupported-field rate, and false positives on transcripts without tasks. Grouped by split and tag.
The rubric (keywords + evidence lines) is deterministic; the model under test is never the judge.
"""
import argparse
import json
import os
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from eval_lib import keyword_score, load_dataset, match, same_deadline_raw, same_person  # noqa: E402


def ratio(num, den):
    return None if den == 0 else round(num / den, 4)


def f1(p, r):
    return None if p is None or r is None or p + r == 0 else round(2 * p * r / (p + r), 4)


def score_item(item, prediction):
    truth = item["tasks"]
    predicted = prediction.get("tasks", []) if prediction else []
    lines = len(item["transcript"].split("\n"))
    pairs = match(predicted, truth)
    c = defaultdict(int)
    c["predicted"], c["truth"], c["matched"] = len(predicted), len(truth), len(pairs)
    for i, j in pairs:
        p, t = predicted[i], truth[j]
        # Assignee: exact identity on matched tasks; truth-null handled as its own bucket.
        if t["assignee"] is None:
            c["assignee_null_truth"] += 1
            c["assignee_null_correct"] += p.get("assignee") is None
        else:
            c["assignee_truth"] += 1
            c["assignee_correct"] += same_person(p.get("assignee"), t["assignee"])
        # Deadline: raw phrase and normalized local time under the dataset interpretation policy.
        c["deadline_raw_total"] += 1
        c["deadline_raw_correct"] += same_deadline_raw(p.get("deadline_raw"), t["deadline_raw"])
        if t.get("deadline_local"):
            c["deadline_norm_total"] += 1
            c["deadline_norm_correct"] += (p.get("due_local") or "")[:16] == t["deadline_local"][:16]
        elif t["deadline_raw"] is not None:
            c["deadline_ambiguous_total"] += 1
            c["deadline_ambiguous_kept"] += not p.get("due_local")
        if t.get("priority") is not None or p.get("priority") is not None:
            c["priority_total"] += 1
            c["priority_correct"] += p.get("priority") == t.get("priority")
        ev = set(p.get("evidence_lines") or [])
        c["evidence_support_total"] += 1
        c["evidence_support"] += bool(ev & set(t.get("evidence_lines") or []))
    for p in predicted:
        ev = p.get("evidence_lines") or []
        c["evidence_refs"] += len(ev)
        c["evidence_valid"] += sum(1 for line in ev if 1 <= line <= lines)
        for field, truth_field in (("assignee", "assignee"), ("deadline_raw", "deadline_raw"), ("priority", "priority")):
            if p.get(field) is not None:
                c["fields_predicted"] += 1
    # Unsupported fields: predicted values where the matched truth has none (or the task is unmatched).
    matched_truth = {i: truth[j] for i, j in pairs}
    for i, p in enumerate(predicted):
        t = matched_truth.get(i)
        for field in ("assignee", "deadline_raw", "priority"):
            if p.get(field) is not None and (t is None or t.get(field) is None):
                c["fields_unsupported"] += 1
    for cancelled in item.get("cancelled", []):
        c["cancel_total"] += 1
        c["cancel_correct"] += not any(keyword_score(p.get("name"), cancelled["keywords"]) >= 0.5 for p in predicted)
    if not truth:
        c["no_task_items"] += 1
        c["no_task_false_positive_tasks"] += len(predicted)
        c["no_task_items_with_fp"] += 1 if predicted else 0
    return c


def summarize(counts, per_item):
    c = counts
    precision, recall = ratio(c["matched"], c["predicted"]), ratio(c["matched"], c["truth"])
    macro = [(ratio(x["matched"], x["predicted"]), ratio(x["matched"], x["truth"])) for x in per_item if x["truth"] or x["predicted"]]
    macro_p = [p for p, _ in macro if p is not None]
    macro_r = [r for _, r in macro if r is not None]
    return {
        "items": len(per_item),
        "task_precision_micro": precision, "task_recall_micro": recall, "task_f1_micro": f1(precision, recall),
        "task_precision_macro": ratio(sum(macro_p), len(macro_p)) if macro_p else None,
        "task_recall_macro": ratio(sum(macro_r), len(macro_r)) if macro_r else None,
        "assignee_accuracy": ratio(c["assignee_correct"], c["assignee_truth"]),
        "assignee_null_kept": ratio(c["assignee_null_correct"], c["assignee_null_truth"]),
        "deadline_raw_accuracy": ratio(c["deadline_raw_correct"], c["deadline_raw_total"]),
        "deadline_normalized_accuracy": ratio(c["deadline_norm_correct"], c["deadline_norm_total"]),
        "deadline_ambiguous_kept_unresolved": ratio(c["deadline_ambiguous_kept"], c["deadline_ambiguous_total"]),
        "priority_accuracy": ratio(c["priority_correct"], c["priority_total"]),
        "evidence_validity": ratio(c["evidence_valid"], c["evidence_refs"]),
        "evidence_support": ratio(c["evidence_support"], c["evidence_support_total"]),
        "cancellation_accuracy": ratio(c["cancel_correct"], c["cancel_total"]),
        "unsupported_field_rate": ratio(c["fields_unsupported"], c["fields_predicted"]),
        "no_task_items": c["no_task_items"], "no_task_false_positive_tasks": c["no_task_false_positive_tasks"],
        "no_task_items_with_false_positive": c["no_task_items_with_fp"],
        "counts": dict(c),
    }


def evaluate(dataset, predictions):
    by_id = {p["id"]: p for p in predictions.get("items", [])}
    groups = defaultdict(lambda: (defaultdict(int), []))
    missing = []
    for item in dataset["items"]:
        prediction = by_id.get(item["id"])
        if prediction is None or prediction.get("status") != "COMPLETED":
            missing.append({"id": item["id"], "status": None if prediction is None else prediction.get("status"), "error": None if prediction is None else prediction.get("error")})
            continue
        c = score_item(item, prediction)
        for key in ["all", "split:" + item["split"]] + ["tag:" + t for t in item.get("tags", [])]:
            total, items = groups[key]
            for k, v in c.items():
                total[k] += v
            items.append(c)
    report = {
        "datasetVersion": dataset.get("datasetVersion"), "interpretationPolicy": dataset.get("interpretationPolicy"),
        "run": predictions.get("run", {}), "notCompleted": missing,
        "groups": {key: summarize(total, items) for key, (total, items) in sorted(groups.items())},
    }
    latencies = sorted(p.get("latencyMs") for p in predictions.get("items", []) if p.get("latencyMs") is not None)
    if latencies:
        report["latencyMs"] = {"p50": latencies[len(latencies) // 2], "p95": latencies[min(len(latencies) - 1, int(len(latencies) * 0.95))], "n": len(latencies)}
    tokens = [p for p in predictions.get("items", []) if p.get("inputTokens") is not None]
    if tokens:
        report["tokens"] = {"input": sum(p["inputTokens"] for p in tokens), "output": sum(p.get("outputTokens") or 0 for p in tokens), "n": len(tokens)}
    return report


def markdown(report):
    fmt = lambda v: "—" if v is None else (f"{v:.2%}" if isinstance(v, float) else str(v))
    run = report.get("run", {})
    lines = ["# Báo cáo đánh giá AI", "",
             f"Dataset: `{report['datasetVersion']}` · provider `{run.get('provider')}` · model `{run.get('model')}` · prompt `{run.get('promptVersion')}` · schema `{run.get('schemaVersion')}`",
             f"Chính sách deadline: {report['interpretationPolicy']}", ""]
    if report.get("latencyMs"):
        lines.append(f"Latency P50 {report['latencyMs']['p50']} ms · P95 {report['latencyMs']['p95']} ms (n={report['latencyMs']['n']})")
    if report.get("tokens"):
        lines.append(f"Tokens: input {report['tokens']['input']}, output {report['tokens']['output']} (n={report['tokens']['n']})")
    if report["notCompleted"]:
        lines.append(f"Không hoàn tất: {len(report['notCompleted'])} transcript — " + ", ".join(f"{m['id']} ({m['status']})" for m in report["notCompleted"]))
    keys = ["items", "task_precision_micro", "task_recall_micro", "task_f1_micro", "task_precision_macro", "task_recall_macro", "assignee_accuracy",
            "deadline_raw_accuracy", "deadline_normalized_accuracy", "evidence_validity", "evidence_support", "cancellation_accuracy",
            "unsupported_field_rate", "no_task_items_with_false_positive"]
    lines += ["", "| Nhóm | " + " | ".join(keys) + " |", "|" + " --- |" * (len(keys) + 1)]
    for name, summary in report["groups"].items():
        lines.append(f"| {name} | " + " | ".join(fmt(summary.get(k)) for k in keys) + " |")
    lines += ["", "Ghi chú: matching one-to-one theo rubric từ khóa + dòng bằng chứng; không dùng model đang đánh giá làm trọng tài. "
              "'no_task' báo số task tạo sai, không gọi là FPR vì chưa định nghĩa true negative."]
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dataset", required=True)
    parser.add_argument("--predictions", required=True)
    parser.add_argument("--out", required=True, help="output path prefix; writes .json and .md")
    args = parser.parse_args()
    dataset = load_dataset(args.dataset)
    with open(args.predictions, encoding="utf-8") as handle:
        predictions = json.load(handle)
    report = evaluate(dataset, predictions)
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out + ".json", "w", encoding="utf-8") as handle:
        json.dump(report, handle, ensure_ascii=False, indent=2)
    with open(args.out + ".md", "w", encoding="utf-8") as handle:
        handle.write(markdown(report))
    print(markdown(report))


if __name__ == "__main__":
    main()
