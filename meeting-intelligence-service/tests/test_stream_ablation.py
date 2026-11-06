import unittest

import numpy as np

from benchmarks.stream_ablation import FRAME, MAX_NEW, MIN_NEW, fixed_cuts, merge_repair, vad_cuts
from src.services.audio_buffer import OVERLAP, WINDOW
from src.services.stt_engine import RecognizedSegment


class StreamAblationTests(unittest.TestCase):
    def test_fixed_replay_keeps_the_trailing_overlap_for_flush(self):
        first, tail = fixed_cuts(WINDOW)
        self.assertEqual((first.start, first.end, first.final), (0, WINDOW, False))
        self.assertEqual((tail.start, tail.end, tail.final), (WINDOW - OVERLAP, WINDOW, True))

    def test_vad_uses_natural_pause_after_the_minimum(self):
        scores = np.ones(125)
        scores[55:74] = 0
        first, tail = vad_cuts(125 * FRAME, scores)
        self.assertFalse(first.forced)
        self.assertGreaterEqual(first.end, MIN_NEW)
        self.assertLess(first.end, MAX_NEW)
        self.assertEqual(tail.start, first.end - OVERLAP)
        self.assertTrue(tail.final)

    def test_continuous_speech_forces_cut_at_cap(self):
        scores = np.ones(150)
        first, tail = vad_cuts(150 * FRAME, scores)
        self.assertTrue(first.forced)
        self.assertEqual(first.end, MAX_NEW)
        self.assertTrue(tail.final)

    def test_repair_replaces_only_the_requested_band(self):
        earlier = RecognizedSegment(0.4, 0.6, "đầu")
        original = RecognizedSegment(1.1, 1.3, "cũ")
        later = RecognizedSegment(1.7, 1.9, "cuối")
        replacement = RecognizedSegment(1.15, 1.35, "mới")
        primary = [earlier, original, later]
        merged, used = merge_repair(primary, [earlier, replacement, later], 1.0, 1.5)
        self.assertTrue(used)
        self.assertEqual(merged, [earlier, replacement, later])
        self.assertEqual(merge_repair(primary, [earlier], 1.0, 1.5), (primary, False))
