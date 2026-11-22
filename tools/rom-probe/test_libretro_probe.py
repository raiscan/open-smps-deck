import contextlib
import ctypes
import io
import json
import struct
import tempfile
import unittest
import wave
from argparse import Namespace
from pathlib import Path

from libretro_probe import WaveCapture, profile_fields, ram_values, scenario


class LibretroProbeTests(unittest.TestCase):
    def test_wave_sample_and_batch(self):
        stream = io.BytesIO()
        capture = WaveCapture(stream, 53267)
        capture.sample(-9, 11)
        samples = (ctypes.c_int16 * 4)(-32, 127, 1024, -1024)
        self.assertEqual(capture.batch(ctypes.addressof(samples), 2), 2)
        capture.close()
        with wave.open(io.BytesIO(stream.getvalue()), "rb") as wav:
            self.assertEqual((wav.getnchannels(), wav.getsampwidth(), wav.getframerate()), (2, 2, 53267))
            self.assertEqual(wav.readframes(3), struct.pack("=6h", -9, 11, -32, 127, 1024, -1024))

    def test_ram_byte_order_and_inactive_tracks(self):
        s1 = profile_fields("s1")
        ram = bytearray(0x10000)
        # Native 68K words in little-endian host storage.
        ram[0xF601] = 4
        ram[0xFFF0:0xFFF2] = bytes.fromhex("34 12")
        ram[0xF041] = 0x80
        ram[0xF044:0xF048] = bytes.fromhex("12 00 56 34")
        ram[0xF074:0xF078] = bytes.fromhex("01 00 01 00")
        readings = dict(zip((field[0] for field in s1), ram_values(s1, ram, None, 1, "little")))
        self.assertEqual(readings["mode"], 4)
        self.assertEqual(readings["demo_flag"], 0x1234)
        self.assertEqual(readings["dac_ptr"], 0x123456)
        self.assertEqual(readings["fm1_ptr"], 0)

        s3k = profile_fields("s3k")
        zram = bytearray(0x2000)
        zram[0x1C3E] = 0x5A
        zram[0x1C40] = 0x80
        zram[0x1C43:0x1C45] = bytes.fromhex("34 92")
        readings = dict(zip((field[0] for field in s3k), ram_values(s3k, ram, zram, 1, "little")))
        self.assertEqual(readings["bank"], 0x5A)
        self.assertEqual(readings["fm6_dac_ptr"], 0x9234)

    def test_report_distinguishes_unavailable_title_identity_from_failed_checks(self):
        args = Namespace(profile="s3k", title_song_from=1, bank=0x5A, song=None, pointer=None,
                         no_demo=0.02, press=[(3, "Start")],
                         start_game=(3, 1), watch=[])
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            (output / "probe.log").write_text(
                "# fps=60\nframe,buttons,mode,demo_flag,bank\n"
                "0,,0,0,\n1,,4,0,\n2,,4,0,\n3,Start,4C,0,\n",
                encoding="utf-8",
            )
            with contextlib.redirect_stdout(io.StringIO()):
                code = scenario.write_report(args, output, {}, None, [], unavailable={"bank"},
                                             emulator={"core": "synthetic", "version": "1",
                                                       "path_sha256": "test"})
            report = json.loads((output / "report.json").read_text())
            checks = report["checks"]
            self.assertEqual(code, 1)
            self.assertTrue(checks["no_demo"]["passed"])
            self.assertEqual(checks["start_game"]["exit_mode"], 0x4C)
            self.assertTrue(checks["start_game"]["passed"])
            self.assertFalse(checks["title_song"]["passed"])
            self.assertEqual(checks["title_song"]["unavailable_fields"], ["bank"])
            self.assertIsNone(checks["title_song"]["first_failure"])
            self.assertEqual(report["emulator"]["core"], "synthetic")

    def test_title_pointer_and_demo_checks(self):
        args = Namespace(profile="s1", title_song_from=0, bank=None, song=None,
                         pointer=("fm1", 0x100, 0x200), no_demo=2, press=[], start_game=None)
        frames = [
            {"frame": 0, "mode": 4, "demo_flag": 0, "fm1_ptr": 0x123},
            {"frame": 1, "mode": 4, "demo_flag": 0, "fm1_ptr": 0x140},
            {"frame": 2, "mode": 0x8C, "demo_flag": 1, "fm1_ptr": 0},
        ]
        checks, _ = scenario.evaluate(args, frames, 60)
        self.assertTrue(checks["title_song"]["passed"])
        self.assertFalse(checks["no_demo"]["passed"])
        self.assertEqual(checks["no_demo"]["first_demo"]["frame"], 2)
        frames[1]["fm1_ptr"] = 0
        checks, _ = scenario.evaluate(args, frames, 60)
        self.assertEqual(checks["title_song"]["first_failure"]["frame"], 1)


if __name__ == "__main__":
    unittest.main()
