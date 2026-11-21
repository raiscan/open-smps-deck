import argparse
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
