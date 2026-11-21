#!/usr/bin/env python3
"""Stage full Slakh-redux mixes with the exact synthesized MIDI and grouped stems."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import io
import json
from pathlib import Path

import numpy as np
import pretty_midi
import soundfile as sf
import yaml

from prepare import HF, read

DATASET = "J1mmymm/MIMuT_Data"
RATE = 44100


def source_class(meta):
    if meta["is_drum"]:
        return "drums"
    return {"Bass": "bass", "Guitar": "guitar", "Piano": "piano"}.get(meta["inst_class"], "other")


def mono_flac(payload):
    signal, rate = sf.read(io.BytesIO(payload), dtype="float32")
    if rate != RATE or signal.ndim != 1:
        raise ValueError("Expected mono Slakh FLAC at 44.1 kHz")
    return signal


def stage(track, output):
    url = f"{HF}/datasets/{DATASET}/resolve/main/test/{track}"
    metadata = yaml.safe_load(read(f"{url}/metadata.yaml"))
    stems = {name: meta for name, meta in metadata["stems"].items() if meta["audio_rendered"]}
    if not 4 <= len(stems) <= 8:
        raise ValueError(f"{track} has {len(stems)} active sources")
    if not {"bass", "drums"} <= {source_class(meta) for meta in stems.values()}:
        raise ValueError(f"{track} lacks bass or drums")
    mix = mono_flac(read(f"{url}/mix.flac"))
    duration = len(mix) / RATE
    if duration > 240.:
        raise ValueError(f"{track} is {duration:.3f} s")
    mix_group = {}
    reference_midi = pretty_midi.PrettyMIDI(resolution=960)
    for name, meta in stems.items():
        signal = mono_flac(read(f"{url}/stems/{name}.flac"))
        if signal.shape != mix.shape:
            raise ValueError(f"{track}/{name} does not share mix length")
        group = source_class(meta)
        mix_group.setdefault(group, np.zeros_like(mix))
        mix_group[group] += signal
        source = pretty_midi.PrettyMIDI(io.BytesIO(read(f"{url}/MIDI/{name}.mid")))
        drum = meta["is_drum"]
        part = pretty_midi.Instrument(0 if drum else meta["program_num"], is_drum=drum, name=name)
        part.notes = [note for track in source.instruments for note in track.notes]
        reference_midi.instruments.append(part)
    # This checks the source ordering, clock, gain, and grouping before publishing.
    error = sum(mix_group.values()) - mix
    mixture_sdr = 10 * np.log10(np.sum(mix ** 2) / max(np.sum(error ** 2), 1e-12))
    if mixture_sdr < 45:
        raise ValueError(f"{track}: stem sum differs from published mix ({mixture_sdr:.1f} dB)")
    target = output / track
    target.mkdir(parents=True, exist_ok=True)
    sf.write(target / "mix.wav", np.repeat(mix[:, None], 2, axis=1), RATE, subtype="PCM_16")
    references = target / "references"
    references.mkdir(exist_ok=True)
    for group, signal in mix_group.items():
        sf.write(references / f"{group}.wav", np.repeat(signal[:, None], 2, axis=1), RATE,
                 subtype="FLOAT")
    reference_midi.write(str(target / "reference.mid"))
    record = {"dataset": "slakh2100-redux", "span": [0., duration], "referenceShift": 0.,
              "sourceCount": len(stems), "sourceClasses": sorted(mix_group),
              "referenceStems": str(references.relative_to(target)),
              "mixtureCheckDb": float(mixture_sdr)}
    (target / "source.json").write_text(json.dumps(record, indent=2) + "\n")
    print(track, record, flush=True)
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("ids", nargs="+")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    with ThreadPoolExecutor(max_workers=3) as pool:
        records = list(pool.map(lambda track: stage(track, args.output), args.ids))
    headings = ("# Slakh2100-redux Suno head-to-head\n\n"
                "Full stereo 44.1 kHz mixes retain the Slakh sample clock. The source mixes are "
                "mono, duplicated into two channels. Reference MIDI uses time zero at the start "
                "of the WAV. Its programs and drum flags come from the matching rendered sources. "
                "Reference stems are summed by class. The source dataset is CC BY 4.0.\n\n"
                "| Track | Duration | Scored span | Sources | Stem groups |\n"
                "|---|---:|---:|---:|---|\n")
    rows = []
    for track, row in zip(args.ids, records, strict=True):
        end = row["span"][1]
        groups = ", ".join(row["sourceClasses"])
        rows.append(f"| {track} | {end:.6f} s | 0–{end:.6f} s | {row['sourceCount']} | {groups} |")
    (args.output / "READY.md").write_text(headings + "\n".join(rows) +
        "\n\nSource: [Slakh2100-redux](https://zenodo.org/records/4599666).\n")


if __name__ == "__main__":
    main()
