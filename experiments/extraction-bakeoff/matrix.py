#!/usr/bin/env python3
"""Create inference manifests and assemble/score runs with explicit stem roles."""
import argparse
from collections import defaultdict
import json
from pathlib import Path

from evaluate import family, inside, read_notes, scores, totals
from notes import ROLES


def dump(value):
    print(json.dumps(value, indent=2, allow_nan=False))


def manifest(source, output, roles):
    return [{"input": str(stem), "role": role,
             "output": str(output / stem.parent.name / f"{role}.json")}
            for folder in sorted(source.iterdir()) if folder.is_dir()
            for role in roles for stem in [folder / f"{role}.wav"] if stem.is_file()]


def combine(folder, inputs, assign_stem=True):
    notes, runtimes = [], []
    replace_drums = any(role == "drums" for role, _ in inputs)
    for role, path in inputs:
        record = json.loads((path / folder / f"{role}.json.meta.json").read_text())
        for a, b, p, prog, drum in record["labeledNotes"]:
            if (role == "drums" and not drum) or (role in ROLES and drum):
                continue
            if role == "mix" and drum and replace_drums:
                continue
            if assign_stem and role in ROLES and role != "other":
                prog = ROLES[role]
            notes.append((a, b, p, prog, drum))
        runtimes.append(record)
    return notes, runtimes


def summary(reference, sources, roles, vocal_only=False):
    import soundfile as sf

    results = {}
    seconds, running, peak = 0., [], []
    for folder in sorted(reference.iterdir()):
        if not (folder / "reference.mid").is_file():
            continue
        notes, timings = combine(folder.name, sources)
        if (folder / "source.json").exists():
            meta = json.loads((folder / "source.json").read_text())
            if "span" in meta:
                span = meta["span"]
                duration = span[1] - span[0]
            else:
                video = meta["youtube"]
                duration = video["end"] - video["start"]
                span = (0., duration)
        else:
            duration = sf.info(folder / "mix.wav").duration
            span = (0., duration)
        ref = inside(read_notes(folder / "reference.mid"), span)
        est = inside(notes, span)
        if roles:
            ref = [n for n in ref if ("drums" if n[4] else family(n[3])) in roles]
            est = [n for n in est if ("drums" if n[4] else family(n[3])) in roles]
        if vocal_only:
            ref = [n for n in ref if not n[4] and family(n[3]) == "vocals"]
            est = [n for n in est if not n[4]]
        results[folder.name] = scores(ref, est)
        seconds += duration
        running.append(sum(r["seconds"] for r in timings))
        peak.append(max(r["peakReservedBytes"] for r in timings))
    return {"micro": totals(results.values()), "cases": results, "audioSeconds": seconds,
            "processingSeconds": sum(running), "peakReservedBytes": max(peak, default=0)}


def write_midi(notes, output):
    import pretty_midi

    midi = pretty_midi.PrettyMIDI(resolution=960)
    grouped = defaultdict(list)
    for start, end, pitch, program, drum in notes:
        grouped[(0 if drum or program is None else program, drum)].append(
            pretty_midi.Note(velocity=85, pitch=pitch, start=start, end=end))
    for (program, drum), events in sorted(grouped.items()):
        part = pretty_midi.Instrument(int(program), is_drum=drum)
        part.notes.extend(events)
        midi.instruments.append(part)
    output.parent.mkdir(parents=True, exist_ok=True)
    midi.write(str(output))


def source_spec(argument):
    role, path = argument.split("=", 1)
    return role, Path(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_subparsers(dest="action", required=True)
    create = modes.add_parser("manifest")
    create.add_argument("source", type=Path)
    create.add_argument("output", type=Path)
    create.add_argument("roles", nargs="+")
    score = modes.add_parser("score")
    score.add_argument("reference", type=Path)
    score.add_argument("sources", type=source_spec, nargs="+", metavar="ROLE=RESULTS")
    score.add_argument("--roles", nargs="+")
    score.add_argument("--vocal-only", action="store_true")
    midi = modes.add_parser("midi")
    midi.add_argument("song")
    midi.add_argument("output", type=Path)
    midi.add_argument("--shift", type=float, default=0)
    midi.add_argument("sources", type=source_spec, nargs="+", metavar="ROLE=RESULTS")
    args = parser.parse_args()
    if args.action == "manifest":
        dump(manifest(args.source, args.output, args.roles))
    elif args.action == "score":
        dump(summary(args.reference, args.sources, args.roles, args.vocal_only))
    else:
        rows = combine(args.song, args.sources)[0]
        rows = [(max(0, a + args.shift), b + args.shift, p, prog, drum)
                for a, b, p, prog, drum in rows if b + args.shift > 0]
        write_midi(rows, args.output)


if __name__ == "__main__":
    main()
