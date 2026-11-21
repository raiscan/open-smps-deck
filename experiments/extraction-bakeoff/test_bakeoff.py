import json
from pathlib import Path
import tempfile
import unittest

import numpy as np

from evaluate import counts, inside, scores, sdr, totals
from listen import disagreements, overlay_notes
from matrix import write_midi
from suno_h2h import RATE, aligned, delay, evaluate, suno_midi, takes


class EvaluationTest(unittest.TestCase):
    def test_note_offsets_and_instrument_class(self):
        reference = [(1., 2., 60, 0, False), (1., 1.1, 36, 0, True)]
        predicted = [(1.01, 1.2, 60, 33, False), (1.01, 1.11, 35, 0, True)]
        result = totals([scores(reference, predicted)])
        self.assertEqual(result["note"]["f1"], 1.)
        self.assertEqual(result["note+offset"]["f1"], 0.)
        self.assertEqual(result["instrument"]["f1"], 0.)
        self.assertEqual(result["drum/kick"]["f1"], 1.)
        self.assertIsNone(result["drum/hat"]["f1"])

    def test_match_is_one_to_one_and_pitch_exact_at_semitone_scale(self):
        target = [(1., 2., 60, 0, False)]
        self.assertEqual(counts(target, target + target), (1, 1, 2))
        self.assertEqual(counts(target, [(1., 2., 61, 0, False)]), (0, 1, 1))

    def test_span_alignment_clips_releases_and_excludes_end(self):
        rows = [(0., 99., 60, 0, False), (2., 3., 61, 0, False)]
        self.assertEqual(inside(rows, [10., 12.], 10.), [(10., 12., 60, 0, False)])

    def test_unknown_releases_are_ignored_only_for_offset_f1(self):
        predicted = [(1., 1.2, 60, 0, False)]
        reference = [(1., 2., 60, 0, False)]
        result = scores(reference, predicted, [(1., 2., 60)])
        self.assertEqual(result["note"], (1, 1, 1))
        self.assertEqual(result["note+offset"], (0, 0, 0))

    def test_sdr_distinguishes_zero_reference_and_scale_error(self):
        source = np.ones((32, 2))
        self.assertIsNone(sdr(source * 0, source))
        self.assertAlmostEqual(sdr(source, source * .5), 6.0206, places=4)
        with self.assertRaises(ValueError):
            sdr(source, source[:-1])

    def test_h2h_waits_for_done_and_compares_two_takes(self):
        import soundfile as sf

        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            case = root / "slakh" / "song"
            suno = case / "suno"
            ref = case / "references"
            suno.mkdir(parents=True)
            ref.mkdir()
            audio = np.ones((128, 2), dtype=np.float32) * .1
            (case / "source.json").write_text(json.dumps({
                "span": [0, len(audio) / RATE], "referenceStems": "references"
            }))
            sf.write(case / "mix.wav", audio * 3, RATE, subtype="FLOAT")
            for stem in ("bass", "drums", "lead"):
                sf.write(ref / f"{stem}.wav", audio, RATE, subtype="FLOAT")
                for take in (1, 2):
                    sf.write(suno / f"{stem} take {take}.wav", audio, RATE, subtype="FLOAT")
            note = [(0.0001, 0.002, 60, 0, False)]
            write_midi(note, case / "reference.mid")
            write_midi(note, suno / "suno.mid")
            local = root / "local"
            write_midi(note, local / "song" / "local.mid")
            self.assertEqual(evaluate(root / "slakh", local, [])["pendingSuno"], ["song"])
            rejected = evaluate(root / "slakh", local, [], rejected=["song"])
            self.assertEqual(rejected["rejectedSuno"], ["song"])
            self.assertEqual(rejected["pendingSuno"], [])
            self.assertEqual(rejected["local"]["micro"]["note"]["f1"], 1.)
            (suno / "DONE").touch()
            result = evaluate(root / "slakh", local, [])
            self.assertEqual(result["pairedSuno"]["micro"]["note+offset"]["f1"], 1.)
            self.assertEqual(result["pairedSuno"], result["pairedLocal"])
            self.assertGreater(result["separation"]["Suno-2/common"]["medianDb"]["bass"], 60)
            self.assertGreater(result["separation"]["Suno-2/common"]["medianDb"]["other"], 60)
            self.assertEqual(set(takes(suno)), {"1", "2"})
            self.assertEqual(result["separation"]["Suno-best/common"]["medianDb"],
                             result["separation"]["Suno-2/common"]["medianDb"])

    def test_per_stem_midi_keeps_cross_stem_duplicates_and_scores_matching_class(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            ref = root / "reference.mid"
            bass = (.1, .3, 40, 33, False)
            lead = (.2, .4, 60, 80, False)
            write_midi([bass, lead], ref)
            write_midi([bass], root / "midi" / "bass.mid")
            write_midi([bass, lead], root / "midi" / "synth.mid")
            union, parts = suno_midi(root, ref, (0., 1.))
            self.assertEqual(union["note"], (2, 2, 3))
            self.assertEqual(parts["bass"]["counts"]["note"], (1, 1, 1))
            self.assertEqual(parts["synth"]["counts"]["note"], (1, 1, 2))

    def test_nested_stems_with_underscore_take_suffix(self):
        with tempfile.TemporaryDirectory() as scratch:
            folder = Path(scratch)
            stems = folder / "stems"
            stems.mkdir()
            for part in ("bass", "drums"):
                for take in (1, 2):
                    (stems / f"{part}_take{take}.wav").touch()
            result = takes(folder)
            self.assertEqual(set(result), {"1", "2"})
            self.assertEqual({name for name, _ in result["2"]}, {"bass", "drums"})

    def test_take_delay_uses_mix_clock(self):
        mix = np.random.default_rng(0).normal(size=(2 * RATE, 2)).astype(np.float32)
        delayed = aligned(mix, len(mix), 32)
        shift, corr = delay(mix, delayed)
        self.assertEqual(shift, -32)
        self.assertGreater(corr, .99)

    def test_listen_clips_a_held_note_and_reports_unmatched_notes(self):
        notes = [(3., 6., 60, 0, False)]
        self.assertGreater(np.max(abs(overlay_notes(notes, 4., 200))), .9)
        self.assertEqual(disagreements([], notes, 16, 8)[0]["unmatched"], 1)


if __name__ == "__main__":
    unittest.main()
