from importlib.util import find_spec
import base64
from pathlib import Path
import tempfile
import unittest


@unittest.skipUnless(all(find_spec(m) for m in ("numpy", "scipy", "soundfile")), "requires DSP dependencies")
class KitTest(unittest.TestCase):
    def test_stock_match_rejects_weak_matches_and_non_hat_noise(self):
        import numpy as np
        from kit import stock_match

        templates = np.eye(2)
        ids = [("dac", "DAC 86", 4), (None, "PSG noise", None)]
        self.assertIsNone(stock_match(np.sqrt([.5, .5]), 42, templates, ids))
        self.assertIsNone(stock_match(templates[1], 47, templates, ids))
        self.assertEqual(("PSG-noise", 1.), stock_match(templates[1], 42, templates, ids))

    def test_repeated_stock_attack_recovers_missing_instance_and_ignores_silence(self):
        import numpy as np
        import soundfile as sf
        from kit import RATE, retrieve

        hz = (53693175 / 15) * 2 / (297 + 26 * 3)
        t = np.arange(3000) / hz
        pcm = np.rint(128 + 115 * np.exp(-28 * t) * np.sin(2 * np.pi * 100 * t)).astype(np.uint8)
        asset = {"id": "kick", "kind": "DAC_SAMPLE", "payload": base64.b64encode(pcm).decode()}
        variant = {"id": "one", "name": "Kick", "assetId": "kick", "recipe": {"rate": 4}}
        audio = np.zeros(3 * RATE)
        pulse = np.interp(np.arange(1800) * hz / RATE, np.arange(len(pcm)), pcm.astype(float) / 128 - 1)
        times = [.24, .64, 1.04, 1.44, 1.84]
        for time in times:
            start = round(time * RATE)
            audio[start:start + len(pulse)] = pulse
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "drums.wav"
            sf.write(path, audio, RATE)
            rows = [[time, 35, 96] for time in times[:-1]]
            cat = {"assets": [asset], "variants": [variant]}
            self.assertEqual(rows, retrieve(path, rows, cat)[0])
            output, meta = retrieve(path, rows, cat, additions=True)
            occupied = sorted(rows + [[times[-1], 38, 96]])
            self.assertEqual(occupied, retrieve(path, occupied, cat, additions=True)[0])
        self.assertEqual("one", meta["kitVariant.35"])
        self.assertTrue(any(abs(t - times[-1]) < .035 for t, _, _ in output))
        self.assertFalse(any(t > 2.1 for t, _, _ in output))
