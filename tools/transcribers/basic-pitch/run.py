#!/usr/bin/env python3
"""Transcribe an aligned stem with the bundled Basic Pitch ONNX model."""

import argparse
import base64
import hashlib
from importlib.metadata import version
import json
import math
from pathlib import Path


def infer(path):
    from basic_pitch import FilenameSuffix, build_icassp_2022_model_path
    from basic_pitch.inference import Model, predict
    from onnxruntime import InferenceSession, SessionOptions

    checkpoint = Path(build_icassp_2022_model_path(FilenameSuffix.onnx))
    opts = SessionOptions()
    opts.intra_op_num_threads = 2
    opts.inter_op_num_threads = 1
    predictor = Model.__new__(Model)
    predictor.model_type = Model.MODEL_TYPES.ONNX
    predictor.model = InferenceSession(str(checkpoint), sess_options=opts,
                                       providers=["CPUExecutionProvider"])
    outputs, _, notes = predict(path, predictor, minimum_note_length=50)
    return notes, {
        "version": version("basic-pitch"),
        "onnxRuntimeVersion": version("onnxruntime"),
        "weightsSha256": hashlib.sha256(checkpoint.read_bytes()).hexdigest(),
        "f0Midi10ms": contour_midi(outputs["contour"]),
    }


def contour_midi(contour):
    import numpy as np
    from basic_pitch.note_creation import CONTOURS_BINS_PER_SEMITONE, MIDI_OFFSET, model_frames_to_time

    if len(contour) == 0:
        return ""
    # The model's pitch head is independent of note boundaries and tracks
    # sub-semitone motion. Keep one dominant pitch for an isolated vocal.
    strongest = contour.argmax(axis=1)
    confidence = contour[np.arange(len(contour)), strongest]
    pitch = np.where(confidence >= .24,
                     np.rint(MIDI_OFFSET + strongest / CONTOURS_BINS_PER_SEMITONE), 0)
    times = model_frames_to_time(len(pitch))
    grid = np.arange(0, times[-1] + .005, .010)
    # Interpolation across silence would manufacture a glide. Use the nearest
    # raw pitch sample on this uniform clock.
    after = np.minimum(np.searchsorted(times, grid), len(pitch) - 1)
    before = np.maximum(after - 1, 0)
    indices = np.where(abs(times[before] - grid) <= abs(times[after] - grid), before, after)
    result = pitch[indices].astype("uint8")
    return base64.b64encode(result.tobytes()).decode("ascii")


def transcribe(args):
    notes, model = infer(args.input)
    rows = sorted([float(start), float(end), int(pitch)] for start, end, pitch, *_ in notes
                  if math.isfinite(start) and math.isfinite(end) and 0 <= start < end and 0 <= pitch <= 127)
    args.output.write_text(json.dumps(rows, allow_nan=False) + "\n", encoding="utf-8")
    metadata = {"tool": "Basic Pitch", "model": "ICASSP 2022 ONNX", "device": "cpu",
                "role": args.role, "minimumNoteMilliseconds": 50, **model}
    Path(str(args.output) + ".meta.json").write_text(json.dumps(metadata) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--role", default="vocals")
    parser.add_argument("--device", choices=("auto", "cpu"), default="auto")
    transcribe(parser.parse_args())


if __name__ == "__main__":
    main()
