#!/usr/bin/env python3
"""Evaluate sparse, synth-specific human annotations on the original mix clock."""
import argparse
import json
from pathlib import Path

from evaluate import matches, read_notes, scores, totals


def select(path, midi_track):
    if midi_track is None:
        return [n for n in read_notes(path) if not n[4]]
    import pretty_midi

    part = pretty_midi.PrettyMIDI(str(path)).instruments[midi_track]
    if part.is_drum:
        raise ValueError("Gold describes tonal notes")
    return [(n.start, n.end, n.pitch, int(part.program), False) for n in part.notes]


def report(annotations, estimate):
    regions, result = [], {}
    reference, predicted, unknown = [], [], []
    for region in annotations["regions"]:
        lo, hi = region["start_s"], region["end_s"]
        label, missing_releases = [], []
        for event in region["notes"]:
            note = (event["attack_s"], event["release_s"] or hi, event["midi"], 80, False)
            label.append(note)
            if event["release_s"] is None:
                missing_releases.append(note[:3])
        local = [n for n in estimate if lo <= n[0] < hi]
        hit = dict(matches(label, local))
        regions.append({"id": region["id"], "span": [lo, hi], "notes": [
            {"reference": n[:3], "onsetMatch": local[hit[i]][:3] if i in hit else None}
            for i, n in enumerate(label)
        ]})
        reference.extend(label)
        predicted.extend(local)
        unknown.extend(missing_releases)
    result["regions"] = regions
    all_scores = totals([scores(reference, predicted, unknown)])
    result["scores"] = {k: all_scores[k] for k in ("note", "note+offset")}
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("annotations", type=Path)
    parser.add_argument("estimate", type=Path, help="Synth JSON sidecar or original MIDI")
    parser.add_argument("--midi-track", type=int, help="Zero-based part index in the MIDI")
    args = parser.parse_args()
    annotations = json.loads(args.annotations.read_text())
    print(json.dumps(report(annotations, select(args.estimate, args.midi_track)), indent=2))


if __name__ == "__main__":
    main()
