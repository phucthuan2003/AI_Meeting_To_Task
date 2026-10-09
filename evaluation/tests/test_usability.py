import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "usability"))
from usability_report import summarise  # noqa: E402


class UsabilityTest(unittest.TestCase):
    def test_paired_crossover_summary_includes_onboarding(self):
        rows = [
            {"participant": "P1", "order": "A-first", "condition": "manual", "total_seconds": "600", "onboarding_seconds": "0", "cards_correct": "5", "cards_expected": "5", "duplicate_cards": "0", "fields_edited": "0"},
            {"participant": "P1", "order": "A-first", "condition": "extension", "total_seconds": "300", "onboarding_seconds": "120", "cards_correct": "4", "cards_expected": "5", "duplicate_cards": "0", "fields_edited": "3"},
            {"participant": "P2", "order": "B-first", "condition": "extension", "total_seconds": "350", "onboarding_seconds": "60", "cards_correct": "5", "cards_expected": "5", "duplicate_cards": "1", "fields_edited": "2"},
            {"participant": "P2", "order": "B-first", "condition": "manual", "total_seconds": "500", "onboarding_seconds": "0", "cards_correct": "5", "cards_expected": "5", "duplicate_cards": "0", "fields_edited": "0"},
            {"participant": "P3", "order": "A-first", "condition": "manual", "total_seconds": "", "onboarding_seconds": "0"},
        ]
        result = summarise(rows)
        self.assertEqual(result["conditions"]["extension"]["n"], 2)
        self.assertEqual(result["conditions"]["extension"]["median_seconds"], 415.0)   # onboarding counted
        self.assertEqual(result["conditions"]["extension"]["card_accuracy"], 0.9)
        self.assertEqual(result["conditions"]["extension"]["duplicates"], 1)
        self.assertEqual([p["extension_minus_manual_seconds"] for p in result["paired"]], [-180.0, -90.0])
        self.assertEqual(set(result["orderEffect"]), {"A-first", "B-first"})


if __name__ == "__main__":
    unittest.main()
