import argparse
from importlib.util import find_spec
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import run


class AdapterTest(unittest.TestCase):
    def test_serializes_valid_sorted_output_and_provenance(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / "result.json"
            args = argparse.Namespace(input=Path("aligned.wav"), output=output, role="vocals")
            with patch.object(run, "infer", return_value=({38: [.21], 35: [float("nan"), .1, -.1]}, {"version": "fixture"})):
                run.transcribe(args)
            self.assertEqual([[.1, 35, 96], [.21, 38, 96]], json.loads(output.read_text()))
            self.assertEqual("fixture", json.loads(output.with_suffix(".json.meta.json").read_text())["version"])

    @unittest.skipUnless(find_spec("adtof_pytorch"), "requires the pinned adapter environment")
    def test_hits_are_invariant_to_input_gain(self):
        import numpy as np
        import soundfile as sf

        rate = 44100
        t = np.arange(rate // 4) / rate
        kick = np.sin(2 * np.pi * (55 * t + 2 * (1 - np.exp(-40 * t)))) * np.exp(-20 * t)
        snare = np.random.default_rng(1).uniform(-1, 1, len(t)) * np.exp(-30 * t)
        pattern = np.tile(np.concatenate((kick, snare)), 8)
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "pattern.wav"
            args = argparse.Namespace(input=path, weights=None, device="cpu")
            results = []
            for gain in (0.8, 0.05):
                sf.write(path, gain * pattern, rate, subtype="FLOAT")
                peaks, metadata = run.infer(args)
                results.append({pitch: list(times) for pitch, times in peaks.items()})
                self.assertEqual("mono-peak", metadata["inputNormalization"])
                self.assertEqual(-4.0, metadata["peakTargetDbfs"])
                self.assertGreater(metadata["inputMonoPeak"], 0)
            self.assertTrue(any(results[0].values()))
            self.assertEqual(*results)

            sf.write(path, np.zeros(rate), rate, subtype="FLOAT")
            features, peak = run.model_input(path)
            self.assertEqual(0, peak)
            self.assertEqual(0, features.count_nonzero().item())
