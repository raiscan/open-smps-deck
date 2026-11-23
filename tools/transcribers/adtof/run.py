#!/usr/bin/env python3
"""Write GM drum attacks from an aligned drum stem using ADTOF's frame RNN."""

import argparse
import hashlib
from importlib.metadata import version
import json
import math
from pathlib import Path


PEAK_TARGET_DBFS = -4.0


def model_input(path):
    from adtof_pytorch.audio import create_adtof_processor
    import numpy as np
    import torch

    processor = create_adtof_processor()
    mono = processor.load_audio(str(path))
    peak = float(np.max(np.abs(mono), initial=0))
    if not math.isfinite(peak):
        raise ValueError("ADTOF input contains non-finite samples")
    if peak:
        mono /= peak
        mono *= 10 ** (PEAK_TARGET_DBFS / 20)
    spectrum = processor.apply_filterbank(processor.compute_stft(mono))
    # The frame RNN expects batch, time, frequency, channel.
    return torch.from_numpy(spectrum.T)[None, :, :, None], peak


def infer(args):
    import adtof_pytorch as adtof
    import torch

    device = args.device
    if device == "auto":
        device = "cuda:0" if torch.cuda.is_available() else "cpu"
    torch.set_num_threads(2)
    checkpoint = args.weights or adtof.get_default_weights_path()
    if not checkpoint or not Path(checkpoint).is_file():
        raise ValueError("ADTOF needs a local frame RNN checkpoint")
    engine = adtof.create_frame_rnn_model(adtof.calculate_n_bins())
    engine = adtof.load_pytorch_weights(engine, str(checkpoint), strict=True).eval().to(device)
    picker = adtof.PeakPicker(thresholds=adtof.FRAME_RNN_THRESHOLDS, fps=100)
    with torch.inference_mode():
        features, peak = model_input(args.input)
        predictions = engine(features.to(device)).cpu().numpy()
    peaks = picker.pick(predictions, labels=adtof.LABELS_5, label_offset=0)[0]
    with Path(checkpoint).open("rb") as stream:
        weights_hash = hashlib.file_digest(stream, "sha256").hexdigest()
    return peaks, {"version": version("adtof-pytorch"), "device": device, "weightsSha256": weights_hash,
                   "inputNormalization": "mono-peak", "peakTargetDbfs": PEAK_TARGET_DBFS,
                   "inputMonoPeak": peak,
                   "thresholds": list(adtof.FRAME_RNN_THRESHOLDS), "framesPerSecond": 100}


def transcribe(args):
    peaks, model = infer(args)
    rows = sorted([float(time), int(pitch), 96] for pitch, times in peaks.items() for time in times
                  if math.isfinite(time) and time >= 0)
    args.output.write_text(json.dumps(rows, allow_nan=False) + "\n", encoding="utf-8")
    metadata = {"tool": "ADTOF", "model": "Frame RNN", "velocity": 96, **model}
    Path(str(args.output) + ".meta.json").write_text(json.dumps(metadata) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--role", choices=("drums",), default="drums")
    parser.add_argument("--device", default="auto", help="auto, cpu, or a Torch device such as cuda:0")
    parser.add_argument("--weights", type=Path)
    transcribe(parser.parse_args())


if __name__ == "__main__":
    main()
