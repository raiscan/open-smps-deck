import tempfile
import unittest
import wave
from pathlib import Path

import numpy as np

from analyze import analyze


class AnalyzeTest(unittest.TestCase):
    def test_offset_and_linear_drift(self):
        rate = 8000
        offset = 0.73
        drift = 0.0025
        rng = np.random.default_rng(42)
        anchors = np.arange(0, 41, 0.08)
        levels = rng.uniform(0.05, 0.9, len(anchors))

        def write_wav(path, duration, delay, stretch):
            times = np.arange(round(duration * rate)) / rate
            envelope = np.interp((times - delay) / stretch, anchors, levels,
                                 left=0, right=0)
            pcm = envelope * np.sin(2 * np.pi * 400 * times)
            with wave.open(str(path), "wb") as wav:
                wav.setparams((1, 2, rate, 0, "NONE", "not compressed"))
                wav.writeframes((pcm * 32767).astype("<i2").tobytes())

        with tempfile.TemporaryDirectory() as directory:
            capture = Path(directory) / "capture.wav"
            reference = Path(directory) / "reference.wav"
            write_wav(reference, 40, 0, 1)
            write_wav(capture, 42, offset, 1 + drift)
            for hint in (None, 0.8):
                with self.subTest(hint=hint):
                    result = analyze(capture, reference, hint)
                    self.assertAlmostEqual(result["offset_seconds"], offset, delta=0.015)
                    self.assertAlmostEqual(result["drift_seconds_per_second"], drift,
                                           delta=0.0005)
                    self.assertGreater(result["overall_correlation"], 0.97)
                    self.assertTrue(result["windows"])
                    self.assertEqual(len(result["inputs"]["reference"]["sha256"]), 64)

            write_wav(capture, 4, 1.25, 1)
            result = analyze(capture, reference, hint=1.25)
            self.assertAlmostEqual(result["offset_seconds"], 1.25, delta=0.015)
            self.assertIsNone(result["drift_seconds_per_second"])
            self.assertGreater(result["overall_correlation"], 0.97)

    def test_silence_has_no_alignment(self):
        with tempfile.TemporaryDirectory() as directory:
            silence = Path(directory) / "silence.wav"
            with wave.open(str(silence), "wb") as wav:
                wav.setparams((1, 2, 8000, 0, "NONE", "not compressed"))
                wav.writeframes(bytes(8000 * 4))
            with self.assertRaisesRegex(ValueError, "varying"):
                analyze(silence, silence)


if __name__ == "__main__":
    unittest.main()
