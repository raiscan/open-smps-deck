"""Align two PCM WAVs and measure RMS envelope correlation and clock drift."""
import argparse
import json
import math
import sys
import wave
from pathlib import Path

import numpy as np

from run import input_hashes


ENVELOPE_RATE = 100
WINDOW = 10 * ENVELOPE_RATE


def rms_envelope(path):
    chunks = []
    with wave.open(str(path), "rb") as wav:
        rate, channels, width = wav.getframerate(), wav.getnchannels(), wav.getsampwidth()
        if rate < ENVELOPE_RATE or width not in (1, 2, 3, 4):
            raise ValueError(f"{path}: expected PCM at {ENVELOPE_RATE} Hz or higher")
        # Whole-second reads keep the bin grid exact even at rates like 22,050 Hz.
        while raw := wav.readframes(10 * rate):
            if width == 3:
                triples = np.frombuffer(raw, dtype=np.uint8).reshape(-1, 3).astype(np.int32)
                packed = triples[:, 0] | triples[:, 1] << 8 | triples[:, 2] << 16
                samples = (packed ^ 0x800000) - 0x800000
            else:
                samples = np.frombuffer(raw, dtype={1: "u1", 2: "<i2", 4: "<i4"}[width])
            samples = samples.astype(np.float64).reshape(-1, channels)
            if width == 1:
                samples -= 128
            power = np.mean(samples ** 2, axis=1)
            bins = len(power) * ENVELOPE_RATE // rate
            if bins:
                edges = np.arange(bins + 1) * rate // ENVELOPE_RATE
                totals = np.add.reduceat(power[:edges[-1]], edges[:-1])
                chunks.append(np.sqrt(totals / np.diff(edges)))
    return np.concatenate(chunks) if chunks else np.empty(0)


def best_match(template, capture, low, high):
    """Return the start bin and peak sliding Pearson correlation, or None."""
    low = max(0, low)
    high = min(len(capture) - len(template), high)
    centered = template - template.mean()
    norm = np.linalg.norm(centered)
    if low > high or not norm:
        return None
    search = capture[low:high + len(template)]
    size = len(template)
    fft_size = 1 << (len(search) + size - 2).bit_length()
    product = np.fft.rfft(search, fft_size) * np.fft.rfft(centered[::-1], fft_size)
    numerators = np.fft.irfft(product, fft_size)[size - 1:len(search)]
    sums = np.r_[0, np.cumsum(search)]
    squares = np.r_[0, np.cumsum(search ** 2)]
    variance = squares[size:] - squares[:-size]
    variance -= (sums[size:] - sums[:-size]) ** 2 / size
    valid = variance > np.max(variance) * 1e-12
    scores = np.full(len(variance), -np.inf)
    scores[valid] = numerators[valid] / (norm * np.sqrt(variance[valid]))
    winner = int(np.argmax(scores))
    if not np.isfinite(scores[winner]):
        return None
    return low + winner, float(np.clip(scores[winner], -1, 1))


def fit_drift(windows, initial_offset):
    """Use a median pairwise slope to reject isolated correlation peaks."""
    if len(windows) < 2:
        return initial_offset, None
    points = np.array([(w["center_seconds"], w["local_offset_seconds"]) for w in windows])
    times, offsets = points.T
    left, right = np.triu_indices(len(points), 1)
    slope = np.median((offsets[right] - offsets[left]) / (times[right] - times[left]))
    intercept = np.median(offsets - slope * times)
    residuals = np.abs(offsets - (intercept + slope * times))
    keep = residuals <= max(0.04, 3 * np.median(residuals))
    if keep.sum() < 2:
        return initial_offset, None
    slope, intercept = np.polyfit(times[keep], offsets[keep], 1)
    return float(intercept), float(slope)


def pearson(first, second):
    first, second = first - first.mean(), second - second.mean()
    denominator = np.linalg.norm(first) * np.linalg.norm(second)
    return float(np.clip(first @ second / denominator, -1, 1)) if denominator else None


def analyze(capture_path, reference_path, hint=None):
    capture, reference = rms_envelope(capture_path), rms_envelope(reference_path)
    rate = ENVELOPE_RATE
    if min(len(capture), len(reference)) < rate:
        raise ValueError("both WAVs must have at least one second of audio")
    if hint is not None and not math.isfinite(hint):
        raise ValueError("offset hint must be finite")

    hint_bins = None if hint is None else round(hint * rate)
    anchor = max(0, -(hint_bins or 0))
    length = min(WINDOW, len(reference) - anchor, max(rate, len(capture) // 2))
    if length < rate:
        raise ValueError("offset hint leaves less than one second of reference audio")
    low, high = (0, len(capture)) if hint_bins is None else (
        anchor + hint_bins - 3 * rate, anchor + hint_bins + 3 * rate)
    match = best_match(reference[anchor:anchor + length], capture, low, high)
    if match is None:
        raise ValueError("could not align varying RMS envelopes in the search range")
    initial_bins = match[0] - anchor

    windows = []
    for start in range(anchor, len(reference), WINDOW):
        # Retain a useful partial window when the capture ends before the reference.
        end = min(start + WINDOW, len(reference), len(capture) - initial_bins)
        if end - start < rate:
            break
        center = (start + end - 1) / (2 * rate)
        radius = round((0.25 + 0.005 * center) * rate)
        expected = start + initial_bins
        peak = best_match(reference[start:end], capture, expected - radius, expected + radius)
        if peak is not None:
            windows.append({
                "reference_start_seconds": start / rate,
                "reference_end_seconds": end / rate,
                "center_seconds": center,
                "local_offset_seconds": (peak[0] - start) / rate,
                "local_correlation": peak[1],
            })

    offset, drift = fit_drift(windows, initial_bins / rate)
    indices = np.arange(len(reference))
    mapped = indices * (1 + (drift or 0.0)) + offset * rate
    valid = (0 <= mapped) & (mapped <= len(capture) - 1)
    if valid.sum() < rate:
        raise ValueError("fitted alignment leaves less than one second of overlapping audio")
    aligned = np.interp(mapped[valid], np.arange(len(capture)), capture)
    overlap_reference = reference[valid]
    overlap_indices = indices[valid]
    for window in windows:
        lower = round(window["reference_start_seconds"] * rate)
        upper = round(window["reference_end_seconds"] * rate)
        start, end = np.searchsorted(overlap_indices, (lower, upper))
        window["aligned_correlation"] = (
            pearson(overlap_reference[start:end], aligned[start:end]) if start < end else None
        )

    overlap = len(overlap_reference) / rate
    return {
        "inputs": {"capture": input_hashes(capture_path), "reference": input_hashes(reference_path)},
        "envelope_rate_hz": rate,
        "initial_alignment_correlation": match[1],
        "offset_seconds": offset,
        "drift_seconds_per_second": drift,
        "drift_seconds_over_overlap": None if drift is None else drift * overlap,
        "overlap_seconds": overlap,
        "overall_correlation": pearson(overlap_reference, aligned),
        "windows": windows,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("capture", type=Path)
    parser.add_argument("reference", type=Path)
    parser.add_argument("--offset-hint", type=float, help="approximate capture minus reference time, in seconds")
    parser.add_argument("--out", type=Path, help="write JSON to a new file instead of stdout")
    args = parser.parse_args()
    try:
        formatted = json.dumps(analyze(args.capture, args.reference, args.offset_hint), indent=2,
                               allow_nan=False) + "\n"
        if args.out:
            with args.out.open("x") as output:
                output.write(formatted)
        else:
            sys.stdout.write(formatted)
    except (OSError, ValueError, OverflowError, EOFError, wave.Error) as error:
        parser.exit(2, f"Audio analysis: {error}\n")


if __name__ == "__main__":
    main()
