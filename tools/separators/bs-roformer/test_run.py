import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import numpy as np
import soundfile as sf

import run


class SeparationTest(unittest.TestCase):
    def test_writes_aligned_stereo_stems_in_model_order(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            source = root / "mono mix.wav"
            sf.write(source, np.linspace(-0.2, 0.2, 1024), 22050)
            checkpoint = root / "mock.ckpt"
            checkpoint.write_bytes(b"mock model")
            mp3 = root / "mono mix.mp3"
            subprocess.run(["ffmpeg", "-v", "error", "-i", str(source), str(mp3)], check=True)

            def mock_demix(mix):
                return np.stack([mix * i for i in range(1, 7)])

            for path in (source, mp3):
                with self.subTest(format=path.suffix):
                    out = root / path.suffix[1:]
                    args = argparse.Namespace(input=path, output_dir=out, checkpoint=checkpoint,
                                              config=root / "mock.yaml", device="cpu")
                    with patch.object(run, "load_model", return_value=(mock_demix, "test", {
                        "chunkSamples": 588800, "overlap": 2, "device": "cpu",
                    })):
                        run.separate(args)

                    mix, rate = sf.read(out / "mix.wav")
                    self.assertEqual((2048, 2), mix.shape)
                    self.assertEqual(44100, rate)
                    order = ["bass", "drums", "other", "vocals", "guitar", "piano"]
                    for index, name in enumerate(order, 1):
                        stem, rate = sf.read(out / "stems" / f"{name}.wav")
                        self.assertEqual(44100, rate)
                        np.testing.assert_allclose(stem, index * mix, atol=1e-6)
                    metadata = json.loads((out / "separation.json").read_text())
                    self.assertEqual(order, metadata["stemOrder"])
                    self.assertEqual(hashlib.sha256(b"mock model").hexdigest(), metadata["checkpointSha256"])
                    self.assertEqual(2, metadata["settings"]["overlap"])


if __name__ == "__main__":
    unittest.main()
