#!/usr/bin/env python3
"""Write GM drum attacks from an aligned drum stem using ADTOF's frame RNN."""

import argparse
from bisect import bisect_left, bisect_right
import hashlib
from importlib.metadata import version
import json
import math
import os
from pathlib import Path


PEAK_TARGET_DBFS = -4.0
TOM_HAT_TOLERANCE_MS = 35
RAW_TOM_VETO = .08
RAW_TOM_HAT_RATIO = 1.0


def normalization_gain_db(peak):
    if not math.isfinite(peak) or peak < 0:
        raise ValueError("ADTOF requires a finite, nonnegative input peak")
    if peak == 0:
        return 0.0
    return min(24.0, PEAK_TARGET_DBFS - 20 * math.log10(peak))


def model_input(path):
    from adtof_pytorch.audio import create_adtof_processor
    import numpy as np
    import torch

    processor = create_adtof_processor()
    mono = processor.load_audio(str(path))
    peak = float(np.max(np.abs(mono), initial=0))
    gain_db = normalization_gain_db(peak)
    mono *= 10 ** (gain_db / 20)
    spectrum = processor.apply_filterbank(processor.compute_stft(mono))
    # The frame RNN expects batch, time, frequency, channel.
    return torch.from_numpy(spectrum.T)[None, :, :, None], peak, gain_db


def relabel_toms(stem, mix, tom_activation=(), threshold=RAW_TOM_VETO,
                 hat_activation=(), ratio=RAW_TOM_HAT_RATIO):
    hats = sorted(stem.get(42, []))
    mix_hats = sorted(mix.get(42, []))
    mix_toms = sorted(mix.get(47, []))

    tolerance = TOM_HAT_TOLERANCE_MS / 1000 + 1e-9

    def nearby(times, onset):
        index = bisect_left(times, onset - tolerance)
        return index < len(times) and times[index] <= onset + tolerance

    recovered = []
    kept = []
    used_mix_hats = set()
    for onset in sorted(stem.get(47, [])):
        frame = round(onset * 100)
        neighborhood = slice(max(0, frame - 3), frame + 4)
        raw_tom = max(tom_activation[neighborhood], default=0)
        raw_hat = max(hat_activation[neighborhood], default=0)
        tom_veto = raw_tom >= threshold and raw_tom >= ratio * raw_hat
        left = bisect_left(mix_hats, onset - tolerance)
        right = bisect_right(mix_hats, onset + tolerance)
        votes = [hat for hat in mix_hats[left:right] if hat not in used_mix_hats]
        if votes and not tom_veto and not nearby(mix_toms, onset) and not nearby(hats, onset):
            recovered.append(onset)
            used_mix_hats.add(min(votes, key=lambda h: abs(h - onset)))
        else:
            kept.append(onset)
    return {**stem, 42: sorted(hats + recovered), 47: kept}, len(recovered)


def mix_skip_reason(stem, mix, peaks):
    if not mix:
        return "no-mix"
    if not mix.is_file():
        return "mix-unavailable"
    if stem.samefile(mix):
        return "same-input"
    if not peaks.get(47):
        return "no-stem-toms"
    return None


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

    def detect(path):
        with torch.inference_mode():
            features, peak, gain_db = model_input(path)
            predictions = engine(features.to(device)).cpu().numpy()
        peaks = picker.pick(predictions, labels=adtof.LABELS_5, label_offset=0)[0]
        return peaks, peak, gain_db, predictions.reshape(-1, len(adtof.LABELS_5))

    peaks, peak, gain_db, stem_activation = detect(args.input)
    mix_metadata = {"tomToHatCount": 0, "tomToHatOnsets": [], "tomToHatToleranceMs": TOM_HAT_TOLERANCE_MS,
                    "rawTomVetoThreshold": RAW_TOM_VETO, "rawTomToHatRatio": RAW_TOM_HAT_RATIO}
    reason = mix_skip_reason(args.input, args.mix, peaks)
    if reason is None:
        mix_peaks, mix_peak, mix_gain, mix_activation = detect(args.mix)
        with args.mix.open("rb") as stream:
            mix_hash = hashlib.file_digest(stream, "sha256").hexdigest()
        mix_metadata.update(mixSha256=mix_hash, mixMonoPeak=mix_peak, mixAppliedGainDb=mix_gain)
        if abs(len(stem_activation) - len(mix_activation)) > 1:
            reason = "duration-mismatch"
        else:
            original_hats = set(peaks.get(42, []))
            peaks, count = relabel_toms(peaks, mix_peaks, mix_activation[:, adtof.LABELS_5.index(47)],
                                        hat_activation=mix_activation[:, adtof.LABELS_5.index(42)])
            mix_metadata.update(tomToHatCount=count, tomToHatOnsets=sorted(set(peaks[42]) - original_hats))
    mix_metadata["mixPassStatus"] = reason or "used"
    with Path(checkpoint).open("rb") as stream:
        weights_hash = hashlib.file_digest(stream, "sha256").hexdigest()
    return peaks, {"version": version("adtof-pytorch"), "device": device, "weightsSha256": weights_hash,
                   **mix_metadata, "inputNormalization": "mono-peak", "peakTargetDbfs": PEAK_TARGET_DBFS,
                   "appliedGainDb": gain_db, "inputMonoPeak": peak,
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
    parser.add_argument("--mix", type=Path, default=os.environ.get("OPENSMPSDECK_DRUM_REFERENCE") or None,
                        help="aligned full mix for tom/hi-hat disambiguation")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--role", choices=("drums",), default="drums")
    parser.add_argument("--device", default="auto", help="auto, cpu, or a Torch device such as cuda:0")
    parser.add_argument("--weights", type=Path)
    transcribe(parser.parse_args())


if __name__ == "__main__":
    main()
