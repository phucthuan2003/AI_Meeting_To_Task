import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
from eval_lib import fold, keyword_score, load_dataset, match, transcript  # noqa: E402
from evaluate import evaluate, markdown  # noqa: E402

DATASET = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "dataset", "seed_v1.json")


class EvaluateTest(unittest.TestCase):
    def setUp(self):
        self.dataset = load_dataset(DATASET)
        self.by_id = {i["id"]: i for i in self.dataset["items"]}

    def perfect(self):
        items = []
        for item in self.dataset["items"]:
            items.append({"id": item["id"], "status": "COMPLETED", "latencyMs": 1000, "inputTokens": 10, "outputTokens": 5,
                          "tasks": [{"name": " ".join(t["keywords"]), "assignee": t["assignee"], "deadline_raw": t["deadline_raw"],
                                     "due_local": t["deadline_local"], "priority": t["priority"], "evidence_lines": t["evidence_lines"]} for t in item["tasks"]]})
        return {"run": {"provider": "test", "model": "m"}, "items": items}

    def test_dataset_is_well_formed_and_long_template_expands(self):
        self.assertGreaterEqual(len(self.dataset["items"]), 12)
        self.assertEqual({i["split"] for i in self.dataset["items"]}, {"dev", "test"})
        long = self.by_id["vi-12-long"]
        lines = long["transcript"].split("\n")
        self.assertEqual(len(lines), 125)
        self.assertIn("Mai làm login", lines[123])
        for item in self.dataset["items"]:
            n = len(item["transcript"].split("\n"))
            for task in item["tasks"]:
                self.assertTrue(all(1 <= line <= n for line in task["evidence_lines"]), item["id"])
            self.assertNotIn("\n\n", item["transcript"])

    def test_perfect_predictions_score_one(self):
        report = evaluate(self.dataset, self.perfect())
        overall = report["groups"]["all"]
        for key in ("task_precision_micro", "task_recall_micro", "task_f1_micro", "assignee_accuracy", "deadline_raw_accuracy",
                    "deadline_normalized_accuracy", "evidence_validity", "evidence_support", "cancellation_accuracy"):
            self.assertEqual(overall[key], 1.0, key)
        self.assertEqual(overall["unsupported_field_rate"], 0.0)
        self.assertEqual(overall["no_task_items_with_false_positive"], 0)
        self.assertIn("split:dev", report["groups"])
        self.assertIn("| all |", markdown(report))

    def test_errors_hallucinations_and_cancelled_tasks_are_penalised(self):
        predictions = self.perfect()
        for p in predictions["items"]:
            if p["id"] == "vi-02-no-task":
                p["tasks"] = [{"name": "Chào mọi người", "assignee": "Nam", "deadline_raw": None, "due_local": None, "priority": None, "evidence_lines": [1]}]
            if p["id"] == "vi-04-cancel":
                p["tasks"].append({"name": "Viết tài liệu API", "assignee": "Hùng", "deadline_raw": None, "due_local": None, "priority": None, "evidence_lines": [99]})
            if p["id"] == "vi-03-correction":
                p["tasks"][0]["assignee"] = "Long"
            if p["id"] == "en-01-basic":
                p["status"] = "FAILED"
        report = evaluate(self.dataset, predictions)
        overall = report["groups"]["all"]
        self.assertLess(overall["task_precision_micro"], 1.0)
        self.assertLess(overall["assignee_accuracy"], 1.0)
        self.assertLess(overall["cancellation_accuracy"], 1.0)
        self.assertLess(overall["evidence_validity"], 1.0)
        self.assertEqual(overall["no_task_items_with_false_positive"], 1)
        self.assertGreater(overall["unsupported_field_rate"], 0.0)
        self.assertEqual([m["id"] for m in report["notCompleted"]], ["en-01-basic"])

    def test_matching_is_one_to_one_and_diacritic_insensitive(self):
        self.assertEqual(fold("Sửa ĐĂNG nhập"), "sua dang nhap")
        self.assertEqual(keyword_score("Sua dang nhap", ["sửa", "đăng nhập"]), 1.0)
        truth = [{"keywords": ["login"], "evidence_lines": [1]}]
        predicted = [{"name": "Làm login", "evidence_lines": [1]}, {"name": "Login lần 2", "evidence_lines": [1]}]
        self.assertEqual(len(match(predicted, truth)), 1)

    def test_template_transcript(self):
        self.assertEqual(transcript({"transcript_template": {"head": ["a"], "filler": "x{i}", "filler_count": 2, "tail": ["b"]}}), "a\nx0\nx1\nb")


if __name__ == "__main__":
    unittest.main()
