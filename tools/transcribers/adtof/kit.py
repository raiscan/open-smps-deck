"""Experimental song-local percussion retrieval on the source clock."""
import base64
from bisect import bisect_left
from collections import Counter
from math import gcd

import numpy as np
from scipy.signal import find_peaks, resample_poly
import soundfile as sf

RATE = 12000
HOP = 96
FFT = 256
FRAMES = 8
BANDS = 24
MIN_CLUSTER_SCORE = .80
MIN_CLUSTER_SEEDS = 4
CLUSTER_SIMILARITY = .90
ADDITION_SIMILARITY = .945
OCCUPANCY_SECONDS = .040
# Aggregate adjacent frequency bins on a log-spaced scale, retaining bass bins.
EDGES = np.unique(np.rint(np.geomspace(1, FFT // 2 + 1, BANDS + 1)).astype(int))
WINDOW = np.hanning(FFT).astype(np.float32)


def spectrum(samples):
    padded = np.pad(samples, (0, FFT + FRAMES * HOP))
    windows = np.lib.stride_tricks.sliding_window_view(padded, FFT)[::HOP]
    result = np.empty((len(windows), len(EDGES) - 1), dtype=np.float32)
    for start in range(0, len(windows), 2048):
        frames = windows[start:start + 2048]
        power = np.abs(np.fft.rfft(frames * WINDOW))
        result[start:start + len(frames)] = np.sqrt(
            np.add.reduceat(power, EDGES[:-1], axis=1) / np.diff(EDGES))
    return result


def audio_spectrum(path):
    audio, rate = sf.read(path, always_2d=True, dtype="float32")
    factor = gcd(rate, RATE)
    mono = resample_poly(audio.mean(axis=1), RATE // factor, rate // factor)
    return spectrum(mono)


def attacks(spec):
    rise = np.maximum(0, spec - np.roll(spec, 2, axis=0))
    rise[:2] = spec[:2]
    flux = np.linalg.norm(rise, axis=1)
    peaks = find_peaks(flux, distance=5, prominence=max(.02, .06 * np.quantile(flux, .98)))[0]
    return peaks, flux


def unit(x):
    return x / max(1e-8, np.linalg.norm(x))


def descriptor(spec, frame):
    frame = min(max(0, frame), len(spec) - FRAMES)
    floor = spec[frame - 2] if frame >= 2 else 0
    return unit(np.maximum(0, spec[frame:frame + FRAMES] - floor).ravel())


def stock(catalogue):
    assets = {a["id"]: a for a in catalogue["assets"] if a["kind"] == "DAC_SAMPLE"}
    templates, ids = [], []
    for v in catalogue["variants"]:
        if v["assetId"] not in assets or v.get("target", "s3k").lower() != "s3k":
            continue
        rate = v["recipe"]["rate"]
        hz = (53693175 / 15) * 2 / (297 + 26 * ((rate or 256) - 1))
        raw = np.frombuffer(base64.b64decode(assets[v["assetId"]]["payload"]), dtype=np.uint8)
        if not len(raw):
            continue
        pcm = (raw.astype(np.float32) - 128) / 128
        onset = np.flatnonzero(np.abs(pcm) > .02 * np.max(np.abs(pcm)))
        if not len(onset):
            continue
        positions = onset[0] + np.arange(FFT + HOP * FRAMES) * hz / RATE
        signal = np.interp(positions, np.arange(len(pcm)), pcm, right=0)
        templates.append(descriptor(spectrum(signal), 0))
        ids.append((v["id"], v["name"], rate))
    # Sega PSG white-noise shift register, clocked at the three fixed noise rates.
    shift = 0x8000
    bits = []
    for _ in range(1200):
        bits.append(1. if shift & 1 else -1.)
        feedback = (shift ^ (shift >> 3)) & 1
        shift = (shift >> 1) | (feedback << 15)
    for divisor in (512, 1024, 2048):
        clocks = (np.arange(1200) * (3579545 / divisor / RATE)).astype(int)
        noise = np.asarray(bits)[clocks]
        for decay in (.018, .055):
            signal = noise * np.exp(-np.arange(len(noise)) / (RATE * decay))
            templates.append(descriptor(spectrum(signal), 0))
            ids.append((None, "PSG noise", None))
    return np.stack(templates), ids


def stock_match(proto, pitch, templates, ids):
    projection = templates @ proto
    missing = np.maximum(templates * projection[:, None] - proto, 0)
    scores = projection * (1 - (missing ** 2).sum(axis=1) / np.maximum(projection ** 2, 1e-8))
    # C0 is an accented hybrid. Demand a clear advantage over alternatives.
    scores -= np.array([.08 if name == "DAC C0" else 0 for _, name, _ in ids])
    close = np.flatnonzero(scores >= scores.max() - .008)
    compatible = [i for i in close if (ids[i][0] is None) == (pitch in (42, 44, 46))]
    best = max(compatible if compatible else close, key=scores.__getitem__)
    variant, _, _ = ids[best]
    if scores[best] < MIN_CLUSTER_SCORE or (variant is None and pitch not in (42, 44, 46)):
        return None
    return variant or "PSG-noise", float(scores[best])


def retrieve(stem, rows, catalogue, recovered_hats=(), *, additions=False):
    spec = audio_spectrum(stem)
    peaks, flux = attacks(spec)
    templates, ids = stock(catalogue)
    groups = []
    recovered_hats = set(recovered_hats)
    for time, pitch, velocity in rows:
        if pitch == 42 and time in recovered_hats:
            continue
        frame = min(round(time * RATE / HOP), len(spec) - FRAMES)
        lo, hi = max(0, frame - 3), min(len(flux), frame + 3)
        frame = lo + int(np.argmax(flux[lo:hi]))
        sample = descriptor(spec, frame)
        sims = [float(sample @ group["first"]) for group in groups]
        if sims and max(sims) >= CLUSTER_SIMILARITY:
            index = int(np.argmax(sims))
        elif len(groups) < 64 and sample.any():
            index = len(groups)
            groups.append(dict(first=sample, examples=[], frames=set(), votes=Counter()))
        else:
            continue
        group = groups[index]
        if frame in group["frames"]:
            continue
        group["frames"].add(frame)
        if len(group["examples"]) < 48:
            group["examples"].append(sample)
        group["votes"][int(pitch)] += 1
    prototypes, learned, votes, clusters = [], [], {}, []
    for group in groups:
        if group["votes"].total() < MIN_CLUSTER_SEEDS:
            continue
        proto = unit(np.median(group["examples"], axis=0))
        pitch = group["votes"].most_common(1)[0][0]
        match = stock_match(proto, pitch, templates, ids)
        if match is None:
            continue
        variant, score = match
        votes.setdefault(pitch, Counter())[variant] += group["votes"].total()
        clusters.append({"seedOnsets": len(group["frames"]), "pitch": pitch,
                         "stock": variant, "score": score})
        prototypes.append(proto)
        learned.append(pitch)
    output = list(rows)
    if additions and prototypes:
        bank = np.stack(prototypes)
        occupied = sorted(time for time, _, _ in rows)
        for frame in peaks:
            time = frame * HOP / RATE
            index = bisect_left(occupied, time)
            if any(abs(previous - time) <= OCCUPANCY_SECONDS + 1e-9 for previous in occupied[max(0, index - 1):index + 1]):
                continue
            similarities = bank @ descriptor(spec, frame)
            best = int(np.argmax(similarities))
            if similarities[best] >= ADDITION_SIMILARITY:
                output.append([time, learned[best], 96])
                occupied.insert(index, time)
    return sorted(output), {f"kitVariant.{p}": v.most_common(1)[0][0] for p, v in votes.items()} | {
        "kitClusters": clusters, "kitPrototypes": len(prototypes),
        "kitEventAdditionsEnabled": additions, "kitAdditions": len(output) - len(rows)}
