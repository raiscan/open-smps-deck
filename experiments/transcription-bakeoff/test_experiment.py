from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

import numpy as np

from assemble import note_f1
from evaluate import WORK_ARTIFACTS, capture_work, lead_attacks
from transcribe import segment


def hertz(*pitch):
    return np.array([0 if value is None else 440 * 2 ** ((value - 69) / 12) for value in pitch])


class ExperimentTest(unittest.TestCase):
    def test_vibrato_preserves_gate_and_sustained_jump_splits(self):
        contour = hertz(None, 69.45, 69.45, 69.15, 68.55, 68.55, 69.05,
                        71, 71, 70.9, None)
        self.assertEqual([(0.01, 0.07, 69), (0.07, 0.10, 71)], segment(contour, 0.11))

    def test_isolated_pitch_spike_does_not_end_note(self):
        self.assertEqual([(0, 0.05, 69)], segment(hertz(69, 69, 81, 69, 69, None), 0.06))

    def test_raw_note_matching_requires_pitch_and_onset(self):
        truth = [(0, 0.1, 60), (0.1, 0.2, 64)]
        estimate = [(0.04, 0.15, 60), (0.16, 0.3, 64)]
        self.assertEqual(0.5, note_f1(truth, estimate))
        self.assertEqual(1, note_f1([], []))

    def test_bench_capture_waits_for_complete_report(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            work = root / ".bench-fixture" / "0"
            work.mkdir(parents=True)
            snapshots = root / "snapshots"
            snapshots.mkdir()
            for name in WORK_ARTIFACTS:
                (work / name).write_text("{}")
            report = work / "report.json"
            report.write_text("{")
            capture_work(root, snapshots, 1)
            self.assertFalse((snapshots / "0").exists())
            report.write_text('{"presentations": 1800}')
            capture_work(root, snapshots, 1)
            self.assertEqual(report.read_text(), (snapshots / "0" / "report.json").read_text())
            self.assertEqual(set(WORK_ARTIFACTS), {path.name for path in (snapshots / "0").iterdir()})

    def test_pitch_accuracy_conditioned_on_finding_the_attack(self):
        truth = [(0, 0.1, 60), (0.1, 0.2, 64), (0.2, 0.3, 67)]
        stats = lead_attacks(truth, [(0.01, 60), (0.11, 61)])
        self.assertEqual({"leadAttackF1": 0.8, "leadPitchAccuracy": 0.5}, stats)


if __name__ == "__main__":
    unittest.main()
