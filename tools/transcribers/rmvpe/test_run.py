import argparse
import base64
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch

import run


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

    def test_segments_pitch_changes_and_writes_fractional_f0_sidecar(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / "result.json"
            args = argparse.Namespace(input=Path("aligned.wav"), output=output, role="vocals")
            pitches = [float("nan")] * 10 + [48.2] * 25 + [52.3] * 20 + [0] * 10
            with patch.object(run, "infer", return_value=(pitches, {"version": "fixture"})):
                run.transcribe(args)
            self.assertEqual([[.1, .35, 48], [.35, .55, 52]], json.loads(output.read_text()))
            metadata = json.loads(output.with_suffix(".json.meta.json").read_text())
            self.assertEqual("fixture", metadata["version"])
            cents = struct.unpack("<65H", base64.b64decode(metadata["f0Cents10ms"]))
            self.assertEqual((0, 4820, 5230, 0), (cents[0], cents[15], cents[40], cents[-1]))
