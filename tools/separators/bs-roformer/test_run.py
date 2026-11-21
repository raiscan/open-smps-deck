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
                                              config=root / "mock.yaml", device="cpu", vocal_checkpoint=None)
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

    def test_cascade_subtracts_vocals_before_sw(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            source = root / "mix.wav"
            sf.write(source, np.full((1024, 2), .25), 44100)
            weights = root / "weights"
            weights.touch()
            config = root / "config"
            config.touch()
            args = argparse.Namespace(input=source, output_dir=root / "out", checkpoint=weights,
                                      config=config, vocal_checkpoint=weights, vocal_config=config, device="cpu")

            def sw(instrumental):
                np.testing.assert_allclose(instrumental, .125)
                return np.stack([instrumental] * 6)

            with patch.object(run, "load_model", side_effect=[
                (lambda mix: mix[None] / 2, "viperx-test", {}),
                (sw, "sw-test", {}),
            ]):
                run.separate(args)
            vocals, _ = sf.read(args.output_dir / "stems/vocals.wav")
            np.testing.assert_allclose(vocals, .125)
            metadata = json.loads((args.output_dir / "separation.json").read_text())
            self.assertEqual("Viperx 317", metadata["settings"]["vocalPass"]["model"])


if __name__ == "__main__":
    unittest.main()
