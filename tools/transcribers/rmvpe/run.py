#!/usr/bin/env python3
"""Transcribe an aligned vocals stem with RMVPE and emit 10 ms pitch evidence."""

import argparse
import base64
import hashlib
import json
import math
import os
from pathlib import Path
from statistics import median
import struct
import sys

SAMPLE_RATE = 16_000
CHUNK_SAMPLES = 10 * SAMPLE_RATE
HOP = 160
VOICING_THRESHOLD = .03


def chunked_pitch(samples, infer_chunk, pad):
    pitch = []
    for start in range(0, len(samples), CHUNK_SAMPLES):
        chunk = samples[start:start + CHUNK_SAMPLES]
        if len(chunk) < CHUNK_SAMPLES:
            chunk = pad(chunk, CHUNK_SAMPLES)
        frames = infer_chunk(chunk)
        if len(frames) < CHUNK_SAMPLES // HOP:
            raise ValueError("RMVPE returned a short contour")
        pitch.extend(frames[:CHUNK_SAMPLES // HOP])
    return pitch[:len(samples) // HOP]


def infer(args):
    os.environ.setdefault("MIOPEN_FIND_MODE", "FAST")
    sys.dont_write_bytecode = True
    import numpy as np
    from scipy.signal import resample_poly
    import soundfile as sf
    import torch

    sys.path.insert(0, str(args.source.resolve()))
    from infer.rmvpe import RMVPE

    accelerator = ("rocm" if torch.version.hip else "cuda") if torch.cuda.is_available() else "cpu"
    requested, _, index = args.device.partition(":")
    if (requested not in ("auto", "cpu", accelerator)
            or index and (requested not in ("cuda", "rocm") or not index.isdecimal())
            or ":" in args.device and not index):
        raise ValueError(f"RMVPE device {args.device!r} unavailable (available accelerator: {accelerator})")
    selected = accelerator if requested == "auto" else requested
    device = "cuda" + (f":{index}" if index else "") if selected in ("cuda", "rocm") else "cpu"
    if device == "cpu":
        torch.set_num_threads(min(4, torch.get_num_threads()))

    audio, rate = sf.read(args.input, dtype="float32", always_2d=True)
    if not len(audio) or not np.isfinite(audio).all():
        raise ValueError("Input must contain finite audio samples")
    factor = math.gcd(rate, SAMPLE_RATE)
    mono = resample_poly(audio.mean(axis=1), SAMPLE_RATE // factor, rate // factor)
    model = RMVPE(str(args.weights.resolve()), is_half=False, device=device)
    with torch.inference_mode():
        hz = chunked_pitch(mono, lambda chunk: model.infer_from_audio(chunk, thred=VOICING_THRESHOLD),
                           lambda chunk, length: np.pad(chunk, (0, length - len(chunk))))
    hz = np.asarray(hz)
    pitches = np.where(hz > 0, 69 + 12 * np.log2(np.maximum(hz, 1e-6) / 440), 0)
    with args.weights.open("rb") as weights:
        digest = hashlib.file_digest(weights, "sha256").hexdigest()
    return pitches, {"tool": "rmvpe", "version": "1", "torchVersion": str(torch.__version__),
                     "device": selected + (f":{index}" if index else ""), "checkpointSha256": digest,
                     "chunkSeconds": 10, "voicingThreshold": VOICING_THRESHOLD}


def segment(pitches):
    # Median smoothing keeps short isolated spikes from triggering a new note.
    smoothed = [0.0] * len(pitches)
    for frame, pitch in enumerate(pitches):
        if pitch:
            window = [p for p in pitches[max(0, frame - 2):frame + 3] if p]
            if len(window) >= 3:
                smoothed[frame] = median(window)

    notes = []
    current = []
    start = None
    pending = []
    last_voiced = 0

    def emit(end):
        if end - start >= 8:
            notes.append([start, end, round(median(current))])

    for frame, pitch in enumerate(smoothed):
        if not pitch:
            pending.clear()
            if current and frame - last_voiced >= 5:
                emit(last_voiced + 1)
                current = []
            continue
        last_voiced = frame
        if not current:
            start, current = frame, [pitch]
        elif abs(pitch - median(current[-15:])) > .8:
            pending.append(pitch)
            if len(pending) == 4:
                boundary = frame - 3
                emit(boundary)
                start, current, pending = boundary, pending, []
        else:
            current.extend(pending)
            pending.clear()
            current.append(pitch)
    if current:
        emit(last_voiced + 1)
    for note, following in zip(notes, notes[1:]):
        if 0 < following[0] - note[1] <= 12:
            note[1] = following[0]
    return [[start / 100, end / 100, pitch] for start, end, pitch in notes]


def transcribe(args):
    if args.role != "vocals":
        raise ValueError("RMVPE expects the vocals role")
    pitches, metadata = infer(args)
    pitches = [pitch if math.isfinite(pitch) and 1 <= pitch <= 127 else 0 for pitch in pitches]
    if args.mode == "syllable":
        from dataclasses import asdict
        import numpy as np
        import soundfile as sf
        from syllables import Settings, segment as segment_syllables, syllable_onsets

        settings = Settings(**json.loads(args.syllable_settings))
        audio, rate = sf.read(args.input, always_2d=True)
        contour = np.asarray(pitches)
        onsets = syllable_onsets(audio.mean(axis=1), rate, contour > 0, settings)
        notes, boundaries = segment_syllables(contour, onsets, settings)
        metadata.update(version="2", vocalMode="syllable", vocalBoundaries10ms=boundaries,
                        syllableSettings=asdict(settings))
    else:
        notes = segment(pitches)
    contour = struct.pack(f"<{len(pitches)}H", *(round(pitch * 100) for pitch in pitches))
    metadata["f0Cents10ms"] = base64.b64encode(contour).decode("ascii")
    args.output.write_text(json.dumps(notes), encoding="utf-8")
    args.output.with_name(args.output.name + ".meta.json").write_text(json.dumps(metadata), encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--source", type=Path, required=True, help="RVC source checkout")
    parser.add_argument("--weights", type=Path, required=True, help="Local RMVPE PyTorch checkpoint")
    parser.add_argument("--role", default="vocals")
    parser.add_argument("--device", default="auto")
    parser.add_argument("--mode", choices=("syllable", "contour"), default="syllable")
    parser.add_argument("--syllable-settings", default="{}", help="JSON object overriding syllable segmentation defaults")
    transcribe(parser.parse_args())
