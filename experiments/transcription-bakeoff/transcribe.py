"""Run CPU transcribers on the three tonal stems and cache note events and timings."""

import argparse
import contextlib
import json
import math
from pathlib import Path
import sys
import time
import wave

import numpy as np

ROLES = ("lead", "bass", "backing", "drums")
HOP = 0.01


def read_json(path):
    return json.loads(path.read_text())


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    pending = path.with_suffix(path.suffix + ".pending")
    pending.write_text(json.dumps(data, indent=2) + "\n")
    pending.replace(path)


def stem_path(corpus, case, role):
    mix = Path(case["audio"]["path"])
    return corpus / mix.with_name(f"{role}-{mix.name}")


def segment(frequencies, duration):
    """Use sustained jumps relative to the current median and close at unvoicing."""
    pitch = np.full(len(frequencies), np.nan)
    voiced = np.isfinite(frequencies) & (frequencies > 0)
    pitch[voiced] = 69 + 12 * np.log2(frequencies[voiced] / 440)
    notes = []
    start = 0
    frames = []
    jump = []

    def emit(end):
        if len(frames) >= 2:
            key = round(float(np.median(frames)))
            offset = min(end * HOP, duration)
            if 0 <= key <= 127 and start * HOP < offset:
                notes.append((start * HOP, offset, key))

    for index, value in enumerate(pitch):
        if not np.isfinite(value):
            emit(index)
            frames.clear()
            jump.clear()
        elif not frames:
            start, frames = index, [value]
        elif abs(value - round(float(np.median(frames)))) > 0.5 + 1e-8:
            if jump and abs(value - jump[-1]) <= 0.5:
                emit(index - 1)
                start, frames = index - 1, [jump[-1], value]
                jump.clear()
            else:
                jump = [value]
        else:
            if jump:
                # An isolated outlier stays inside the gate but outside its pitch median.
                jump.clear()
            frames.append(value)
    emit(len(pitch))
    return notes


def mono_16k(path):
    import soundfile as sf
    from scipy.signal import resample_poly

    signal, rate = sf.read(path, always_2d=True, dtype="float32")
    divisor = math.gcd(rate, 16000)
    return resample_poly(signal.mean(axis=1), 16000 // divisor, rate // divisor)


def predictor(args):
    if args.model == "basic-pitch" and args.device != "cpu":
        raise ValueError("Basic Pitch is pinned to ONNX CPU in this experiment")
    if args.model == "basic-pitch":
        from basic_pitch import FilenameSuffix, build_icassp_2022_model_path
        from basic_pitch.inference import Model, predict

        model = Model(build_icassp_2022_model_path(FilenameSuffix.onnx))

        def basic(path, role, duration):
            with contextlib.redirect_stdout(sys.stderr):
                _, _, notes = predict(path, model, minimum_note_length=50)
            return [(float(start), float(end), int(pitch)) for start, end, pitch, *_ in notes]
        return basic

    import torch

    device = "cpu"
    if args.device == "rocm":
        if torch.version.hip is None or not torch.cuda.is_available():
            raise RuntimeError("Requested ROCm but this PyTorch cannot see a HIP GPU")
        device = "cuda:0"  # PyTorch uses the CUDA device namespace for HIP.
    torch.set_num_threads(args.threads)
    torch.set_num_interop_threads(1)
    torch.manual_seed(0)
    np.random.seed(0)
    if args.model == "muscriptor":
        from muscriptor.events import NoteEndEvent
        from muscriptor.transcription_model import TranscriptionModel

        if args.weights is None:
            raise ValueError("--weights must point to the small model safetensors")
        model = TranscriptionModel.load_model(args.weights, device=device)
        instruments = {"lead": ["synth_lead"], "bass": ["electric_bass"],
                       "backing": ["synth_lead", "synth_pad"]}

        def muscriptor(path, role, duration):
            with torch.inference_mode():
                return [(event.start_event.start_time, event.end_time, event.start_event.pitch)
                        for event in model.transcribe(path, instruments=instruments[role])
                        if isinstance(event, NoteEndEvent)]
        return muscriptor

    if args.model == "rmvpe":
        from infer.rmvpe import RMVPE

        if args.weights is None:
            raise ValueError("--weights must point to rmvpe.pt")
        model = RMVPE(args.weights, is_half=False, device="cpu")
        if device != "cpu":
            # The RVC constructor's CUDA branch chooses an NVIDIA SM dtype.
            # Keep the same float32 model on HIP and let PyTorch dispatch it.
            model.device = device
            model.model.to(device)
            model.mel_extractor.to(device)

        def rmvpe(path, role, duration):
            with torch.inference_mode():
                f0 = model.infer_from_audio(mono_16k(path), thred=0.05)
            return segment(f0, duration)
        return rmvpe

    import torchcrepe

    def crepe(path, role, duration):
        # CREPE dithers the decoded pitch bins; seed per file so resuming a run is reproducible.
        np.random.seed(0)
        signal = torch.from_numpy(mono_16k(path))[None]
        with torch.inference_mode():
            f0, confidence = torchcrepe.predict(
                signal, 16000, 160, fmin=40, fmax=2000, model="tiny",
                batch_size=512, device=device, return_periodicity=True)
            confidence = torchcrepe.threshold.Silence(-60)(confidence, signal, 16000, 160)
        return segment(torch.where(confidence[0] >= 0.20, f0[0], 0).numpy(), duration)
    return crepe


def duration_of(path):
    with wave.open(str(path)) as audio:
        return audio.getnframes() / audio.getframerate()


def transcribe_case(predict, path, role):
    duration = duration_of(path)
    start = time.perf_counter()
    notes = predict(path, role, duration)
    elapsed = time.perf_counter() - start
    notes = sorted((max(0, start), min(end, duration), pitch) for start, end, pitch in notes
                   if max(0, start) < min(end, duration) and 0 <= pitch <= 127)
    print(f"{path.name} {role}: {elapsed:.2f}s / {duration:.2f}s, {len(notes)} notes", flush=True)
    return {"audioSeconds": duration, "wallSeconds": elapsed, "notes": notes}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("model", choices=("basic-pitch", "muscriptor", "rmvpe", "torchcrepe"))
    parser.add_argument("corpora", type=Path, nargs="+")
    parser.add_argument("--device", choices=("cpu", "rocm"), default="cpu")
    parser.add_argument("--weights", type=Path)
    parser.add_argument("--threads", type=int, default=4)
    parser.add_argument("--pilot", type=Path, help="Time one WAV and print projected seconds per audio minute")
    parser.add_argument("--pilot-role", choices=ROLES[:3], default="lead")
    parser.add_argument("--shard", nargs=2, type=int, default=(0, 1), metavar=("INDEX", "TOTAL"))
    args = parser.parse_args()
    index, total = args.shard
    if not 0 <= index < total:
        parser.error("--shard expects 0 <= INDEX < TOTAL")

    print(f"{args.model} device: {args.device}", flush=True)
    predict = predictor(args)
    if args.pilot:
        pilot = transcribe_case(predict, args.pilot, args.pilot_role)
        print(f'{60 * pilot["wallSeconds"] / pilot["audioSeconds"]:.2f} wall s/audio min')
        return
    roles = ROLES[:3] if args.model in ("basic-pitch", "muscriptor") else ROLES[:1]
    for corpus in args.corpora:
        cases = read_json(corpus / "manifest.json")["cases"]
        for case in cases[index::total]:
            for role in roles:
                target = corpus / "transcriptions" / args.model / case["id"] / f"{role}.json"
                if target.is_file():
                    if read_json(target).get("device", "cpu") != args.device:
                        raise ValueError(f"Cached device differs for {target}; move that cache before switching")
                    continue
                result = transcribe_case(predict, stem_path(corpus, case, role), role)
                write_json(target, {**result, "device": args.device})


if __name__ == "__main__":
    main()
