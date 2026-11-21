#!/usr/bin/env python3
"""Score aligned note outputs and MUSDB-style separation directories."""
import argparse
from collections import defaultdict
import json
from pathlib import Path

import mir_eval
import numpy as np
import soundfile as sf

from notes import midi_notes


DRUMS = {
    "kick": {35, 36},
    "snare": {37, 38, 39, 40},
    "tom": {41, 43, 45, 47, 48, 50},
    "hat": {42, 44, 46},
    "cymbal": {49, 51, 52, 53, 55, 57, 59},
}
STEMS = ("bass", "drums", "vocals", "other")


def family(program):
    if program is None:
        return "unclassified"
    if program < 8:
        return "piano"
    if 24 <= program < 32:
        return "guitar"
    if 32 <= program < 40:
        return "bass"
    if 52 <= program < 56:
        return "vocals"
    return "other"


def read_notes(path):
    path = Path(path)
    if path.suffix.lower() in {".mid", ".midi"}:
        return midi_notes(path)
    record = json.loads(path.read_text())
    return record["labeledNotes"] if isinstance(record, dict) else record


def inside(notes, span, shift=0):
    return [(a + shift, min(b + shift, span[1]), p, program, drum) for a, b, p, program, drum in notes
            if span[0] <= a + shift < span[1]]


def matches(reference, estimate, offsets=False):
    if not reference or not estimate:
        return []
    ref, est = (np.asarray([row[:3] for row in notes]) for notes in (reference, estimate))
    return mir_eval.transcription.match_notes(
        ref[:, :2], 440 * 2 ** ((ref[:, 2] - 69) / 12),
        est[:, :2], 440 * 2 ** ((est[:, 2] - 69) / 12),
        onset_tolerance=.05, pitch_tolerance=50.,
        offset_ratio=.2 if offsets else None, offset_min_tolerance=.05)


def counts(reference, estimate, offsets=False):
    return (len(matches(reference, estimate, offsets)), len(reference), len(estimate))


def scores(reference, estimate, unknown_releases=()):
    tonal = [[n for n in notes if not n[4]] for notes in (reference, estimate)]
    drums = [[n for n in notes if n[4]] for notes in (reference, estimate)]
    ref, est = tonal
    onset = matches(ref, est)
    unknown_releases = set(tuple(note) for note in unknown_releases)
    skip = {j for i, j in onset if tuple(ref[i][:3]) in unknown_releases}
    offset_ref = [n for n in ref if tuple(n[:3]) not in unknown_releases]
    offset_est = [n for j, n in enumerate(est) if j not in skip]
    result = {"note": (len(onset), len(ref), len(est)),
              "note+offset": counts(offset_ref, offset_est, True)}
    groups = sorted({family(n[3]) for n in ref + est})
    for group in groups:
        parts = [[n for n in notes if family(n[3]) == group] for notes in tonal]
        result[f"instrument/{group}"] = counts(*parts)
        parts = [[n for n in notes if family(n[3]) == group] for notes in (offset_ref, offset_est)]
        result[f"instrument+offset/{group}"] = counts(*parts, True)
    for name in ("instrument", "instrument+offset"):
        groups = [v for k, v in result.items() if k.startswith(name + "/")]
        result[name] = tuple(map(sum, zip(*groups))) if groups else (0, 0, 0)
    for name, pitches in DRUMS.items():
        times = [[n[0] for n in notes if n[2] in pitches] for notes in drums]
        result[f"drum/{name}"] = (len(mir_eval.util.match_events(*times, .05)), *map(len, times))
    result["drum"] = tuple(map(sum, zip(*(result[f"drum/{name}"] for name in DRUMS))))
    return result


def totals(rows):
    summed = defaultdict(lambda: [0, 0, 0])
    for row in rows:
        for key, counts in row.items():
            for i, value in enumerate(counts):
                summed[key][i] += value
    return {key: {"hits": hits, "reference": ref, "estimated": est,
                  "f1": 2 * hits / (ref + est) if ref + est else None}
            for key, (hits, ref, est) in summed.items()}


def note_cases(cases):
    records = {}
    for case in cases:
        span = case["span"]
        ref = inside(read_notes(case["reference"]), span, case.get("referenceShift", 0))
        est = inside(read_notes(case["estimate"]), span, case.get("estimateShift", 0))
        records[case["id"]] = scores(ref, est, case.get("unknownReleases", []))
    return {"micro": totals(records.values()), "cases": records}


def audio(path):
    data, sr = sf.read(path, dtype="float64", always_2d=True)
    if sr != 44100:
        raise ValueError(f"Expected 44.1 kHz audio in {path}")
    return data


def sdr(ref, est):
    if ref.shape != est.shape:
        raise ValueError(f"Unaligned stem lengths or channel counts: {ref.shape} vs {est.shape}")
    energy = np.square(ref).sum()
    if energy <= 1e-9:
        return None
    return float(10 * np.log10((energy + 1e-9) / (np.square(ref - est).sum() + 1e-9)))


def separation(reference, estimate):
    tracks = {}
    for mix in sorted(reference.glob("*/mix.wav")):
        if estimate is None:
            predicted = {stem: audio(mix) for stem in STEMS}
        else:
            folder = estimate / mix.parent.name
            predicted = {stem: audio(folder / f"{stem}.wav") for stem in STEMS}
            for extra in ("piano", "guitar"):
                path = folder / f"{extra}.wav"
                if path.exists():
                    predicted["other"] += audio(path)
        tracks[mix.parent.name] = {stem: sdr(audio(mix.with_name(f"{stem}.wav")), predicted[stem])
                                   for stem in STEMS}
    summary = {}
    for stem in STEMS:
        available = [row[stem] for row in tracks.values() if row[stem] is not None]
        summary[stem] = float(np.median(available)) if available else None
    return {"cases": tracks, "medianDb": summary}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_subparsers(dest="mode", required=True)
    notes = modes.add_parser("notes")
    notes.add_argument("manifest", type=Path)
    sep = modes.add_parser("separation")
    sep.add_argument("reference", type=Path)
    sep.add_argument("estimate", type=Path, nargs="?", help="Omit to copy the mix into every stem")
    args = parser.parse_args()
    if args.mode == "notes":
        result = note_cases(json.loads(args.manifest.read_text()))
    else:
        result = separation(args.reference, args.estimate)
    print(json.dumps(result, indent=2, allow_nan=False))


if __name__ == "__main__":
    main()
