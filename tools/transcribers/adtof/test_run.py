import argparse
from importlib.util import find_spec
import json
import os
import sys
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import run


class AdapterTest(unittest.TestCase):
    def test_empty_catalogue_environment_is_unset(self):
        with patch.dict(os.environ, {"OPENSMPSDECK_DRUM_CATALOGUE": ""}), patch.object(
                sys, "argv", ["adtof", "--input", "drums.wav", "--output", "events.json"]), patch.object(
                run, "transcribe") as transcribe:
            run.main()
        self.assertIsNone(transcribe.call_args.args[0].catalogue)

    def test_relabel_preserves_times_and_requires_unambiguous_mix_hat(self):
        stem = {35: [1.0], 42: [3.0], 47: [1., 2., 3., 4.]}
        mix = {42: [1.035, 2., 3., 4.036], 47: [2.01]}
        peaks, count = run.relabel_toms(stem, mix)
        self.assertEqual(1, count)
        self.assertEqual({35: [1.0], 42: [1., 3.], 47: [2., 3., 4.]}, peaks)

    def test_raw_tom_veto_and_single_mix_vote(self):
        stem = {47: [1.0, 1.04, 2.0]}
        mix = {42: [1.02, 2.01]}
        raw = [0.] * 205
        raw[198] = .25
        peaks, count = run.relabel_toms(stem, mix, raw, threshold=.20)
        self.assertEqual(1, count)
        self.assertEqual([1.0], peaks[42])
        self.assertEqual([1.04, 2.0], peaks[47])
        hat = [0.] * 205
        hat[201] = .90
        peaks, count = run.relabel_toms(stem, mix, raw, .20, hat, 1.0)
        self.assertEqual(2, count)
        self.assertEqual([1.0, 2.0], peaks[42])

    def test_skipped_mix_reasons(self):
        with tempfile.TemporaryDirectory() as folder:
            stem = Path(folder) / "drums.wav"
            stem.touch()
            mix = Path(folder) / "mix.wav"
            self.assertEqual("no-mix", run.mix_skip_reason(stem, None, {}))
            self.assertEqual("mix-unavailable", run.mix_skip_reason(stem, mix, {}))
            self.assertEqual("same-input", run.mix_skip_reason(stem, stem, {}))
            mix.touch()
            self.assertEqual("no-stem-toms", run.mix_skip_reason(stem, mix, {}))

    def test_normalization_gain(self):
        for peak in (0.1, 1.0):
            gain = run.normalization_gain_db(peak)
            self.assertAlmostEqual(10 ** (-4 / 20), peak * 10 ** (gain / 20))
        self.assertEqual(0, run.normalization_gain_db(0))
        self.assertEqual(24, run.normalization_gain_db(0.001))
        with self.assertRaises(ValueError):
            run.normalization_gain_db(float("nan"))

    def test_serializes_valid_sorted_output_and_provenance(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / "result.json"
            args = argparse.Namespace(input=Path("aligned.wav"), output=output, role="vocals", kit_retrieval=False)
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
            args = argparse.Namespace(input=path, weights=None, device="cpu", mix=None)
            results = []
            for gain in (0.8, 0.05):
                sf.write(path, gain * pattern, rate, subtype="FLOAT")
                peaks, metadata = run.infer(args)
                results.append({pitch: list(times) for pitch, times in peaks.items()})
                self.assertEqual("mono-peak", metadata["inputNormalization"])
                self.assertEqual(-4.0, metadata["peakTargetDbfs"])
                self.assertGreater(metadata["inputMonoPeak"], 0)
                self.assertEqual("no-mix", metadata["mixPassStatus"])
                self.assertEqual(0, metadata["tomToHatCount"])
                self.assertEqual([], metadata["tomToHatOnsets"])
            self.assertTrue(any(results[0].values()))
            self.assertEqual(*results)

            sf.write(path, np.zeros(rate), rate, subtype="FLOAT")
            features, peak, gain_db = run.model_input(path)
            self.assertEqual(0, gain_db)
            self.assertEqual(0, peak)
            self.assertEqual(0, features.count_nonzero().item())
