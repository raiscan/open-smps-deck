#!/usr/bin/env python3
"""Write the external-transcriber note array and a labeled benchmark sidecar."""
import argparse
import contextlib
import io
import json
import math
from pathlib import Path
import sys
import time

import numpy as np
import soundfile as sf

ROLES = {"piano": 0, "keyboard": 0, "keys": 0, "guitar": 27, "bass": 33,
         "vocals": 53, "vocal": 53, "other": 80, "lead": 80, "backing": 80}
HERE = Path(__file__).resolve().parent
MODEL_PACKAGES = {"basic-pitch": "basic-pitch", "yourmt3": "mt3-infer",
                  "muscriptor": "muscriptor", "adtof": "adtof-pytorch"}


def midi_notes(source):
    import pretty_midi

    if isinstance(source, Path):
        source = str(source)
    midi = pretty_midi.PrettyMIDI(source)
    return [(n.start, n.end, n.pitch, int(part.program), part.is_drum)
            for part in midi.instruments for n in part.notes]


def band_onsets(path):
    from scipy.signal import find_peaks, stft

    audio, sr = sf.read(path, dtype="float32", always_2d=True)
    hop = max(1, round(sr * .01))
    nfft = min(4 * hop, len(audio))
    if nfft < hop:
        return []
    f, t, bins = stft(audio.mean(axis=1), fs=sr, nperseg=nfft, noverlap=nfft - hop)
    power = abs(bins) ** 2
    if power.max(initial=0) <= 1e-12:
        return []
    bands = np.array([power[(f >= low) & (f < high)].sum(axis=0)
                      for low, high in ((20, 160), (160, 3000), (5000, sr / 2))])
    floor = max(float(power.max()) * 1e-5, 1e-12)
    flux = np.maximum(np.diff(np.log1p(bands / floor), prepend=0, axis=1), 0)
    peaks, _ = find_peaks(flux.sum(axis=0), height=2.5, prominence=1.5, distance=6)
    return [(float(t[index]), float(t[index] + .08),
             (36, 38, 42)[int(flux[:, index].argmax())], 0, True) for index in peaks]


def legacy():
    # Use the same RMVPE segmentation as the prior benchmark.
    sys.path.insert(0, str(HERE.parent / "transcription-bakeoff"))
    import transcribe

    return transcribe


def backend(args, device):
    model = args.model
    if model == "bands":
        return lambda path, role, duration: band_onsets(path)

    if model == "basic-pitch":
        from basic_pitch import FilenameSuffix, build_icassp_2022_model_path
        from basic_pitch.inference import Model, predict
        from onnxruntime import InferenceSession, SessionOptions

        # Bound ONNX's thread pool during multi-model runs.
        opts = SessionOptions()
        opts.intra_op_num_threads = 2
        opts.inter_op_num_threads = 1
        checkpoint = build_icassp_2022_model_path(FilenameSuffix.onnx)
        predictor = Model.__new__(Model)
        predictor.model_type = Model.MODEL_TYPES.ONNX
        predictor.model = InferenceSession(str(checkpoint), sess_options=opts,
                                           providers=["CPUExecutionProvider"])

        def basic(path, role, duration):
            with contextlib.redirect_stdout(sys.stderr):
                _, _, notes = predict(path, predictor, minimum_note_length=50)
            return [(a, b, p, ROLES.get(role), False) for a, b, p, *_ in notes]

        return basic

    import torch

    if model == "yourmt3":
        import mt3_infer

        engine = mt3_infer.load_model("yourmt3", checkpoint_path=str(args.weights),
                                      device=device, auto_download=False)

        def ymt3(path, role, duration):
            audio, sr = sf.read(path, dtype="float32", always_2d=True)
            result = engine.transcribe(audio.mean(axis=1), sr=sr)
            stream = io.BytesIO()
            result.save(file=stream)
            stream.seek(0)
            return midi_notes(stream)

        return ymt3

    if model == "muscriptor":
        sys.path.insert(0, str(HERE.parents[1] / "tools/transcribers/muscriptor"))
        from run import instrument_prompt
        from muscriptor.events import NoteEndEvent
        from muscriptor.transcription_model import TranscriptionModel

        precision = args.precision
        load_device = device if precision == "float32" else "cpu"
        engine = TranscriptionModel.load_model(args.weights, device=load_device, dtype=precision)
        if load_device != device:
            engine._model.to(device)
            engine._device = torch.device(device)
            provider = engine._model.condition_provider
            provider.device = engine._device
            for conditioner in provider.conditioners.values():
                conditioner.device = engine._device
        engine._model.autocast.enabled = False
        variant = json.loads(args.weights.with_name("config.json").read_text())["variant"]

        def mu(path, role, duration):
            if role == "drums":
                prompt = ["drums"]
            elif role in {"bass", "guitar", "piano", "lead", "keyboard"}:
                prompt = instrument_prompt("keyboard" if role == "piano" else role, None)
                if role == "piano":
                    prompt = ["acoustic_piano", "electric_piano"]
            else:
                prompt = None
            events = engine.transcribe(path, instruments=prompt, batch_size=1,
                                       prelude_forcing=variant == "large")
            rows = []
            for event in events:
                if isinstance(event, NoteEndEvent):
                    start = event.start_event
                    drum = start.instrument == "drums"
                    program = 0 if drum else engine._program_for_instrument(start.instrument)
                    rows.append((start.start_time, event.end_time, start.pitch, program, drum))
            return rows

        return mu

    if model == "rmvpe":
        old = legacy()
        from infer.rmvpe import RMVPE

        engine = RMVPE(str(args.weights), is_half=False, device="cpu")
        engine.device = device
        engine.model.to(device)
        engine.mel_extractor.to(device)

        def vocal(path, role, duration):
            pitch = engine.infer_from_audio(old.mono_16k(path), thred=.05)
            return [(a, b, p, 53, False) for a, b, p in old.segment(pitch, duration)]

        return vocal

    import adtof_pytorch as adtof

    engine = adtof.create_frame_rnn_model(adtof.calculate_n_bins())
    weights = args.weights or adtof.get_default_weights_path()
    if not weights or not Path(weights).is_file():
        raise ValueError("ADTOF requires a local checkpoint")
    engine = adtof.load_pytorch_weights(engine, str(weights), strict=True).eval().to(device)
    picker = adtof.PeakPicker(thresholds=adtof.FRAME_RNN_THRESHOLDS, fps=100)

    def drums(path, role, duration):
        features = adtof.load_audio_for_model(str(path)).to(device)
        predictions = engine(features).cpu().numpy()
        peaks = picker.pick(predictions, labels=adtof.LABELS_5, label_offset=0)[0]
        return [(float(time), float(time + .1), int(pitch), 0, True)
                for pitch, times in peaks.items() for time in times]

    return drums


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", required=True, choices=[*MODEL_PACKAGES, "rmvpe", "bands"])
    inputs = parser.add_mutually_exclusive_group(required=True)
    inputs.add_argument("--input", type=Path)
    inputs.add_argument("--manifest", type=Path, help="JSON array of input, output, role objects")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--role", default="mix")
    parser.add_argument("--device", default="auto")
    parser.add_argument("--weights", type=Path)
    parser.add_argument("--benchmark-labels", action="store_true",
                        help="Store program and drum metadata with notes for evaluation")
    parser.add_argument("--precision", choices=["float32", "float16", "bfloat16"], default="float32")
    args = parser.parse_args()
    if args.input and not args.output:
        parser.error("--input requires --output")
    if args.model in {"muscriptor", "yourmt3", "rmvpe"} and not args.weights:
        parser.error(f"{args.model} requires --weights")
    cases = json.loads(args.manifest.read_text()) if args.manifest else [
        {"input": args.input, "output": args.output, "role": args.role}
    ]
    cases = [case for case in cases if not args.manifest or not
             Path(str(case["output"]) + ".meta.json").exists()]
    if not cases:
        return

    torch = None
    if args.model not in {"basic-pitch", "bands"}:
        import torch

        torch.set_num_threads(4)
        torch.set_num_interop_threads(1)
        torch.manual_seed(0)
    device = args.device
    if device == "auto":
        device = "cuda:0" if torch and torch.cuda.is_available() else "cpu"
    if not torch and device != "cpu":
        parser.error(f"{args.model} requires CPU")
    load_started = time.monotonic()
    infer = backend(args, device)
    load_seconds = time.monotonic() - load_started
    from importlib.metadata import version
    package_version = version(MODEL_PACKAGES[args.model]) if args.model in MODEL_PACKAGES else None

    for case in cases:
        path, output, role = Path(case["input"]), Path(case["output"]), case["role"]
        started = time.monotonic()
        duration = sf.info(path).duration
        if device.startswith("cuda"):
            torch.cuda.reset_peak_memory_stats(device)
        with torch.inference_mode() if torch else contextlib.nullcontext():
            raw = infer(path, role, duration)
        rows = []
        for start, end, pitch, program, drum in raw:
            if not (math.isfinite(start) and math.isfinite(end) and 0 <= pitch <= 127):
                continue
            start, end = max(0., float(start)), min(duration, float(end))
            if start < end:
                rows.append([start, end, int(pitch), program, bool(drum)])
        rows.sort(key=lambda note: note[:3])
        output.parent.mkdir(parents=True, exist_ok=True)
        contract_rows = [n[:3] for n in rows
                         if role == "mix" or n[4] == (role == "drums")]
        output.write_text(json.dumps(contract_rows, allow_nan=False) + "\n")
        record = {"tool": args.model, "version": package_version, "role": role,
                  "device": device, "precision": args.precision if args.model == "muscriptor" else None,
                  "seconds": time.monotonic() - started, "modelLoadSeconds": load_seconds,
                  "audioSeconds": duration, "peakReservedBytes": torch.cuda.max_memory_reserved(device)
                  if device.startswith("cuda") else 0}
        if args.manifest or args.benchmark_labels:
            record["labeledNotes"] = rows
        Path(str(output) + ".meta.json").write_text(json.dumps(record, allow_nan=False) + "\n")
        print(output.name, len(rows), round(record["seconds"], 3), flush=True)


if __name__ == "__main__":
    main()
