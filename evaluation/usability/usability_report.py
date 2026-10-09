#!/usr/bin/env python3
"""Summarise a crossover usability session sheet (SDS §9.5): manual Trello entry vs extension.

    python3 evaluation/usability/usability_report.py sessions.csv

Reports per-condition median/mean total time (including onboarding/AI wait/review/fixes), paired differences per
participant, card correctness, duplicates and edits, plus an order-effect check. It does not claim significance;
sample size and limitations must be written in the report.
"""
import csv
import statistics
import sys
from collections import defaultdict


def number(value):
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def summarise(rows):
    by_condition = defaultdict(list)
    by_participant = defaultdict(dict)
    for row in rows:
        total = number(row.get("total_seconds"))
        if total is None:
            continue
        row = dict(row, total=total + (number(row.get("onboarding_seconds")) or 0))
        by_condition[row["condition"]].append(row)
        by_participant[row["participant"]][row["condition"]] = row
    result = {"conditions": {}, "paired": [], "orderEffect": {}}
    for condition, items in sorted(by_condition.items()):
        times = [r["total"] for r in items]
        expected = sum(number(r.get("cards_expected")) or 0 for r in items)
        correct = sum(number(r.get("cards_correct")) or 0 for r in items)
        result["conditions"][condition] = {
            "n": len(items), "median_seconds": statistics.median(times), "mean_seconds": round(statistics.mean(times), 1),
            "card_accuracy": None if not expected else round(correct / expected, 4),
            "duplicates": sum(number(r.get("duplicate_cards")) or 0 for r in items),
            "fields_edited_mean": round(statistics.mean([number(r.get("fields_edited")) or 0 for r in items]), 2),
        }
    for participant, conditions in sorted(by_participant.items()):
        if {"manual", "extension"} <= conditions.keys():
            result["paired"].append({"participant": participant, "order": conditions["manual"].get("order"),
                                     "extension_minus_manual_seconds": conditions["extension"]["total"] - conditions["manual"]["total"]})
    diffs = [p["extension_minus_manual_seconds"] for p in result["paired"]]
    if diffs:
        result["paired_median_difference_seconds"] = statistics.median(diffs)
    for order in sorted({p["order"] for p in result["paired"]}):
        values = [p["extension_minus_manual_seconds"] for p in result["paired"] if p["order"] == order]
        result["orderEffect"][order] = {"n": len(values), "median_difference_seconds": statistics.median(values)}
    return result


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    with open(sys.argv[1], encoding="utf-8") as handle:
        result = summarise(list(csv.DictReader(handle)))
    print("| Điều kiện | n | Median (s) | Mean (s) | Card đúng | Trùng | Trường sửa TB |")
    print("| --- | --- | --- | --- | --- | --- | --- |")
    for condition, s in result["conditions"].items():
        acc = "—" if s["card_accuracy"] is None else f"{s['card_accuracy']:.0%}"
        print(f"| {condition} | {s['n']} | {s['median_seconds']:.0f} | {s['mean_seconds']:.0f} | {acc} | {s['duplicates']:.0f} | {s['fields_edited_mean']} |")
    if result["paired"]:
        print(f"\nChênh lệch theo cặp (extension − manual), median: {result['paired_median_difference_seconds']:.0f} s trên {len(result['paired'])} người.")
        for order, s in result["orderEffect"].items():
            print(f"  Thứ tự {order}: n={s['n']}, median {s['median_difference_seconds']:.0f} s")
    print("\nKhông kết luận ý nghĩa thống kê với mẫu nhỏ; ghi rõ số người và hạn chế trong báo cáo.")


if __name__ == "__main__":
    main()
