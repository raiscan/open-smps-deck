import json
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest

from run import instrument_prompt, note_rows, write_notes


class WrapperTest(unittest.TestCase):
    def test_role_defaults_and_override(self):
        self.assertEqual(instrument_prompt("lead", None), ["synth_lead"])
        self.assertEqual(instrument_prompt("synth", None), ["synth_lead"])
        self.assertEqual(instrument_prompt("bass", None), ["electric_bass"])
        self.assertEqual(instrument_prompt("vocals", None), ["voice"])
        self.assertEqual(instrument_prompt("piano", None), ["acoustic_piano", "electric_piano"])
        self.assertIsNone(instrument_prompt("other", None))
        self.assertEqual(instrument_prompt("keys", None),
                         ["electric_piano", "chromatic_percussion", "synth_lead", "synth_pad"])
        self.assertEqual(instrument_prompt("lead", "organ, synth_pad"), ["organ", "synth_pad"])
        self.assertIsNone(instrument_prompt("lead", "none"))
        with self.assertRaises(ValueError):
            instrument_prompt("unknown", None)

    def test_json_rows_are_sorted_clipped_and_valid(self):
        def event(start, end, pitch):
            return SimpleNamespace(start_event=SimpleNamespace(start_time=start, pitch=pitch),
                                   end_time=end)

        rows = note_rows([event(2, 7, 65), event(-1, 1, 60), event(5, 6, 67),
                          event(1, 1, 70), event(1, 2, 128)], 4)
        with TemporaryDirectory() as directory:
            output = Path(directory) / "notes.json"
            write_notes(output, rows)
            self.assertEqual(json.loads(output.read_text()), [[0, 1, 60], [2, 4, 65]])


if __name__ == "__main__":
    unittest.main()
