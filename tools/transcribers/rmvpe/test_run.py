import argparse
import base64
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch

import numpy as np

import run
from syllables import Settings, segment, supported_attack


class AdapterTest(unittest.TestCase):
    def test_chunks_have_fixed_shape_and_trim_to_input(self):
        calls = []

        def infer(chunk):
            calls.append(chunk)
            return [220] * 1001

        contour = run.chunked_pitch([.25] * 160_320, infer, lambda chunk, length: chunk + [0] * (length - len(chunk)))
        self.assertEqual([160_000, 160_000], list(map(len, calls)))
        self.assertEqual([.25] * 320 + [0] * (160_000 - 320), calls[1])
        self.assertEqual([220] * 1002, contour)

    def test_syllable_boundaries_preserve_same_pitch_attacks(self):
        pitch = np.array([0] * 3 + [48.25] * 24 + [0] * 5 + [48.25] * 12
                         + [0] * 7 + [52.1] * 10)
        notes, boundaries = segment(pitch, [15], Settings())
        self.assertEqual([[.03, .15, 48], [.15, .32, 48], [.32, .44, 48], [.51, .61, 52]], notes)
        self.assertEqual([3, 32, 51], boundaries["voicing"])

    def test_default_mode_snapshots_flux_peaks_only_with_voiced_lookahead(self):
        from types import SimpleNamespace

        rate = 16000
        audio = np.zeros((rate * 2, 1))
        for start, end in ((3200, 9200), (16000, 20000), (24000, 28000)):
            audio[start:end, 0] = .4 * np.sin(2 * np.pi * 220 * np.arange(end - start) / rate)
        pitch = np.zeros(200)
        pitch[20:58] = 57
        pitch[150:175] = 57
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / "notes.json"
            args = argparse.Namespace(input=Path("aligned.wav"), output=output, role="vocals",
                                      mode="syllable", syllable_settings='{"min_pitch": 50}')
            audio_reader = SimpleNamespace(read=lambda *args, **kwargs: (audio, rate))
            with patch.object(run, "infer", return_value=(pitch, {})), patch.dict("sys.modules", soundfile=audio_reader):
                run.transcribe(args)
            metadata = json.loads(output.with_suffix(".json.meta.json").read_text())
            self.assertEqual([20, 150], metadata["vocalBoundaries10ms"]["syllable"])
            self.assertEqual([20, 150], metadata["vocalBoundaries10ms"]["voicing"])
            self.assertEqual("3", metadata["version"])
            self.assertEqual(50, metadata["syllableSettings"]["min_pitch"])
            self.assertEqual([[.20, .58, 57], [1.50, 1.75, 57]], json.loads(output.read_text()))

    def test_flux_at_the_end_of_a_vowel_does_not_make_an_attack(self):
        voiced = np.zeros(70, dtype=bool)
        voiced[12:30] = True
        voiced[50:65] = True
        rms = np.full(70, -50.0)
        rms[26:30] = -20
        self.assertIsNone(supported_attack(voiced, rms, 26, Settings()))

    def test_segments_pitch_changes_and_writes_fractional_f0_sidecar(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / "result.json"
            args = argparse.Namespace(input=Path("aligned.wav"), output=output, role="vocals", mode="contour")
            pitches = [float("nan")] * 10 + [48.2] * 25 + [52.3] * 20 + [0] * 10
            with patch.object(run, "infer", return_value=(pitches, {"version": "fixture"})):
                run.transcribe(args)
            self.assertEqual([[.1, .35, 48], [.35, .55, 52]], json.loads(output.read_text()))
            metadata = json.loads(output.with_suffix(".json.meta.json").read_text())
            self.assertEqual("fixture", metadata["version"])
            cents = struct.unpack("<65H", base64.b64decode(metadata["f0Cents10ms"]))
            self.assertEqual((0, 4820, 5230, 0), (cents[0], cents[15], cents[40], cents[-1]))
