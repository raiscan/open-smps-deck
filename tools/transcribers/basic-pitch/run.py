#!/usr/bin/env python3
"""Transcribe an aligned stem with the bundled Basic Pitch ONNX model."""

import argparse
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
    _, _, notes = predict(path, predictor, minimum_note_length=50)
    return notes, {
        "version": version("basic-pitch"),
        "onnxRuntimeVersion": version("onnxruntime"),
        "weightsSha256": hashlib.sha256(checkpoint.read_bytes()).hexdigest(),
    }


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
