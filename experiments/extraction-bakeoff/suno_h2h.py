#!/usr/bin/env python3
"""Score finished Suno downloads against full, aligned Slakh references."""
import argparse
from collections import defaultdict
import json
import math
from pathlib import Path
import re

import numpy as np
import soundfile as sf
from scipy.signal import correlate, resample_poly

from evaluate import note_cases, sdr

RATE = 44100


def at_rate(path):
    audio, rate = sf.read(path, dtype="float32", always_2d=True)
    if rate != RATE:
        divisor = math.gcd(rate, RATE)
        audio = resample_poly(audio, RATE // divisor, rate // divisor, axis=0)
    if audio.shape[1] == 1:
        audio = np.repeat(audio, 2, axis=1)
    if audio.shape[1] != 2:
        raise ValueError(f"Expected mono or stereo audio: {path}")
    return audio


def aligned(audio, samples, shift=0):
    result = np.zeros((samples, 2), dtype="float32")
    source_start, dest_start = max(0, -shift), max(0, shift)
    length = max(0, min(len(audio) - source_start, samples - dest_start))
    result[dest_start:dest_start + length] = audio[source_start:source_start + length]
    return result


def delay(mix, summed):
    # Estimate one shared transport delay per take from the input mix. The
    # references play no part in this search. Limit it to a half-second.
    radius = RATE // 2
    begin = min(15 * RATE, len(mix) // 3)
    end = min(begin + 12 * RATE, len(mix) - radius, len(summed) - radius)
    if begin < radius or end <= begin:
        return 0, None
    target = mix[begin:end].mean(axis=1)
    search = summed[begin - radius:end + radius].mean(axis=1)
    cross = correlate(search, target, mode="valid", method="fft")
    best = int(np.argmax(cross))
    match = search[best:best + len(target)]
    quality = float(cross[best] / max(1e-10, np.linalg.norm(target) * np.linalg.norm(match)))
    return (radius - best if quality >= .5 else 0), quality


def group(label):
    name = re.sub(r"[^a-z0-9]+", " ", label.lower()).strip()
    if "bass" in name:
        return "bass"
    if "drum" in name or "percussion" in name:
        return "drums"
    if "guitar" in name:
        return "guitar"
    if any(word in name.split() for word in ("keys", "keyboard", "piano")):
        return "piano"
    return "other"


def takes(folder):
    override = folder / "stems.json"
    result = defaultdict(list)
    if override.exists():
        manifest = json.loads(override.read_text())
        for take, parts in manifest.items():
            result[take] = [(group(part), folder / name)
                            for part, files in parts.items() for name in files]
    else:
        stem_folder = folder / "stems"
        if not stem_folder.is_dir():
            stem_folder = folder
        for path in sorted(stem_folder.glob("*.wav")):
            label = path.stem
            match = re.search(r"(?i)(?<![a-z0-9])take[ _-]*(\d+)\b", label)
            if not match:
                match = re.search(r"[ _-]+(\d+)$", label)
            if not match:
                raise ValueError(f"Cannot infer take for {path.name}. Supply suno/stems.json.")
            take = match[1]
            part = f"{label[:match.start()]} {label[match.end():]}"
            result[take].append((group(part), path))
    if len(result) != 2:
        raise ValueError(f"Expected two Suno stem takes in {folder}; found {len(result)}")
    for take, stems in result.items():
        if not {"bass", "drums"} <= {name for name, _ in stems}:
            raise ValueError(f"Take {take} lacks mapped bass or drums")
    return result


def grouped(stems, length):
    parts = {}
    for name, path in stems:
        audio = aligned(at_rate(path), length)
        if name in parts:
            parts[name] += audio
        else:
            parts[name] = audio
    return parts


def score_stems(reference, predicted, length, shift, span, fine):
    groups = ("bass", "drums", "guitar", "piano", "other") if fine else ("bass", "drums", "other")
    start, end = (round(t * RATE) for t in span)
    zero = np.zeros((end - start, 2), dtype=np.float32)

    def reduce_parts(parts, name, delay_samples):
        keys = ("guitar", "piano", "other") if name == "other" and not fine else (name,)
        return sum((aligned(parts[key], length, delay_samples)[start:end]
                    for key in keys if key in parts), zero)

    return {name: sdr(reduce_parts(reference, name, 0), reduce_parts(predicted, name, shift))
            for name in groups}


def summarize_stems(by_track):
    by_role = defaultdict(list)
    for record in by_track.values():
        for name, value in record["dB"].items():
            if value is not None:
                by_role[name].append(value)
    return {"medianDb": {key: float(np.median(v)) for key, v in by_role.items()},
            "trackCounts": {key: len(v) for key, v in by_role.items()}, "tracks": by_track}


def evaluate(root, local, separators):
    pending, all_suno, all_local = [], [], []
    sep_scores = defaultdict(dict)
    for meta in sorted(root.glob("*/source.json")):
        record = json.loads(meta.read_text())
        song = meta.parent
        reference_dir = song / record["referenceStems"]
        suno = song / "suno"
        span = record["span"]
        case = {"id": song.name, "span": span, "reference": song / "reference.mid"}
        reference_mix = at_rate(song / "mix.wav")
        length = len(reference_mix)
        ref = grouped([(group(p.stem), p) for p in sorted(reference_dir.glob("*.wav"))], length)
        for name, folder in separators:
            directory = folder / song.name
            if not (directory / "runtime.json").is_file():
                continue
            files = [(group(p.stem), p) for p in sorted(directory.glob("*.wav"))]
            estimated = grouped(files, length)
            sep_scores[f"{name}/common"][song.name] = {"dB": score_stems(
                ref, estimated, length, 0, span, fine=False)}
            if {"guitar", "piano"} <= estimated.keys():
                sep_scores[f"{name}/fine"][song.name] = {"dB": score_stems(
                    ref, estimated, length, 0, span, fine=True)}
        local_midi = local / song.name / "local.mid"
        if local_midi.is_file():
            all_local.append(dict(case, estimate=local_midi))
        if not (suno / "DONE").is_file():
            pending.append(song.name)
            continue
        all_suno.append(dict(case, estimate=suno / "suno.mid"))
        for take, files in takes(suno).items():
            # Keep the transport search allowance at the tail until after alignment.
            estimated = grouped(files, length + RATE // 2)
            shift, quality = delay(reference_mix, sum(estimated.values()))
            alignment = {"shiftSamples": shift, "mixCorrelation": quality}
            for fine in (False, True):
                kind = "fine" if fine else "common"
                sep_scores[f"Suno-{take}/{kind}"][song.name] = {
                    **alignment, "dB": score_stems(ref, estimated, length, shift, span, fine)}
    paired = {case["id"] for case in all_suno} & {case["id"] for case in all_local}
    suno_stem_tracks = {case["id"] for case in all_suno}
    paired_stems = set.intersection(suno_stem_tracks, *(set(rows) for rows in sep_scores.values()))
    return {"pendingSuno": pending, "pairedTracks": sorted(paired),
            "pairedStemTracks": sorted(paired_stems),
            "local": note_cases(all_local), "suno": note_cases(all_suno),
            "pairedLocal": note_cases([case for case in all_local if case["id"] in paired]),
            "pairedSuno": note_cases([case for case in all_suno if case["id"] in paired]),
            "separation": {name: summarize_stems(rows) for name, rows in sep_scores.items()},
            "pairedSeparation": {name: summarize_stems({
                song_id: row for song_id, row in rows.items() if song_id in paired_stems
            }) for name, rows in sep_scores.items()}}


def separator(argument):
    name, path = argument.split("=", 1)
    return name, Path(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    parser.add_argument("local", type=Path, help="Directory of track folders with local.mid")
    parser.add_argument("--separator", action="append", type=separator, default=[])
    args = parser.parse_args()
    print(json.dumps(evaluate(args.root, args.local, args.separator), indent=2, allow_nan=False))


if __name__ == "__main__":
    main()
