#!/usr/bin/env python3
"""Render user-supplied MIDI to aligned, mix-ready FluidSynth references."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from copy import copy
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

import numpy as np
import pretty_midi
import soundfile as sf


RATE = 44100
ROOM = {"synth.reverb.room-size": .32, "synth.reverb.damp": .35,
        "synth.reverb.width": .75, "synth.reverb.level": .22}
TARGET_DB = {"lead": -20, "bass": -22, "drums": -20, "piano": -24, "guitar": -24, "other": -25}


def mapped_reference(case, target):
    midi = pretty_midi.PrettyMIDI(case["midi"])
    mapping = case["parts"]
    if (len(mapping) < 4 or len(midi.instruments) != len(mapping)
            or not {"lead", "bass", "drums"} <= {part["role"] for part in mapping}):
        raise ValueError("Map every MIDI part, including distinct lead, bass and drums")
    for original, part in zip(midi.instruments, mapping, strict=True):
        role = part["role"]
        if original.is_drum != (role == "drums"):
            raise ValueError("The drum role must match the original MIDI drum flag")
        old_program = int(original.program)
        if role != "drums":
            original.program = part["program"]
        original.control_changes = [event for event in original.control_changes
                                    if event.number not in {0, 32}]
        original.name = role
        part["sourceProgram"] = old_program
        part["program"] = int(original.program)
    duration = min(240., max(n.end for part in midi.instruments for n in part.notes) + 1.5)
    for part in midi.instruments:
        part.notes = [n for n in part.notes if n.start < duration]
        for note in part.notes:
            note.end = min(note.end, duration)
        part.pitch_bends = [event for event in part.pitch_bends if event.time < duration]
        part.control_changes = [event for event in part.control_changes if event.time < duration]
    midi.write(str(target))
    return pretty_midi.PrettyMIDI(str(target)), duration


def render(song, part, temp, font, samples):
    solo = copy(song)
    solo.instruments = [part]
    midi, output = temp / "part.mid", temp / "part.wav"
    solo.write(str(midi))
    options = [item for key, value in ROOM.items() for item in ("-o", f"{key}={value}")]
    subprocess.run(["fluidsynth", "-ni", "-r", str(RATE), "-g", ".5", "-R", "1", "-C", "0",
                    "-o", "player.timing-source=sample", "-F", str(output), "-T", "wav",
                    "-O", "float", *options, str(font), str(midi)], check=True,
                   capture_output=True)
    audio, rate = sf.read(output, dtype="float32", always_2d=True, frames=samples)
    if rate != RATE or audio.shape[1] != 2:
        raise ValueError("FluidSynth must emit 44.1 kHz stereo")
    return np.pad(audio, ((0, samples - len(audio)), (0, 0)))


def gain_for_part(audio, target_db):
    # Estimate level from active 50 ms windows so sparse lines are not over-amplified.
    size = RATE // 20
    rms = np.sqrt(np.mean(audio[:len(audio) // size * size].reshape(-1, size, 2) ** 2, axis=(1, 2)))
    active = rms[rms > .001]
    if not len(active):
        raise ValueError("FluidSynth rendered a silent part")
    db = 20 * np.log10(np.percentile(active, 75))
    return float(np.clip(target_db - db, -14, 14))


def stage(case, output, font, digest):
    target = output / case["id"]
    target.mkdir(parents=True, exist_ok=True)
    song, duration = mapped_reference(case, target / "reference.mid")
    samples = min(round(duration * RATE), 240 * RATE)
    tracks = {}
    with tempfile.TemporaryDirectory() as work:
        for part, mapping in zip(song.instruments, case["parts"], strict=True):
            role = mapping["role"]
            audio = render(song, part, Path(work), font, samples)
            gain_db = gain_for_part(audio, TARGET_DB[role]) + mapping.get("trimDb", 0.)
            pan = mapping.get("pan", 0.)
            if not -1 <= pan <= 1:
                raise ValueError("Pan is in [-1, 1]")
            audio *= (10 ** (gain_db / 20)) * np.sqrt([1 - pan, 1 + pan])
            tracks.setdefault(role, np.zeros((samples, 2), dtype=np.float32))
            tracks[role] += audio
            mapping["gainDb"] = gain_db
    mix = sum(tracks.values())
    bus = 10 ** (-1.2 / 20) / max(1e-6, float(abs(mix).max()))
    mix *= bus
    sf.write(target / "mix.wav", mix, RATE, subtype="PCM_24")
    references = target / "references"
    references.mkdir(exist_ok=True)
    for role, audio in tracks.items():
        sf.write(references / f"{role}.wav", bus * audio, RATE, subtype="FLOAT")
    check, _ = sf.read(target / "mix.wav", dtype="float32")
    error = check - sum(sf.read(path, dtype="float32")[0] for path in references.glob("*.wav"))
    exact_db = 10 * np.log10(np.sum(check ** 2) / max(np.sum(error ** 2), 1e-12))
    if exact_db < 90:
        raise ValueError(f"Stem mixes do not reproduce {case['id']}: {exact_db:.1f} dB")
    duration = samples / RATE
    metadata = {"dataset": "original-midi-render", "span": [0., duration], "referenceShift": 0.,
                "referenceStems": "references", "sourceCount": len(song.instruments),
                "sourceClasses": sorted(tracks), "programMapping": case["parts"],
                "soundfont": font.name, "soundfontSha256": digest,
                "mixBusGainDb": 20 * np.log10(bus), "mixBusPeakDbfs": -1.2,
                "effects": {"reverb": ROOM, "chorus": False}, "mixtureCheckDb": float(exact_db)}
    (target / "source.json").write_text(json.dumps(metadata, indent=2, allow_nan=False) + "\n")
    print(case["id"], f"{duration:.3f} s", sorted(tracks), f"{exact_db:.1f} dB", flush=True)
    return metadata


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("soundfont", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    cases = json.loads(args.manifest.read_text())
    with args.soundfont.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    with ThreadPoolExecutor(max_workers=2) as pool:
        rows = list(pool.map(lambda case: stage(case, args.output, args.soundfont, digest), cases))
    lines = ["# Original MIDI renders ready for Suno", "",
             "These tracks use the supplied original MIDI arrangements and General MIDI patches. "
             "The full 44.1 kHz stereo mix and aligned MIDI and audio references share time zero. "
             "Upload only mix.wav for each track.", "",
             "| ID | Parts | Duration | Scored span | Stem groups |",
             "|---|---:|---:|---:|---|"]
    for case, row in zip(cases, rows, strict=True):
        end = row["span"][1]
        roles = ", ".join(row["sourceClasses"])
        lines.append(f"| {case['id']} | {row['sourceCount']} | {end:.6f} s | 0–{end:.6f} s | {roles} |")
    (args.output / "READY.md").write_text("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
