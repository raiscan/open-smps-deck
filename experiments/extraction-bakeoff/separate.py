#!/usr/bin/env python3
"""Separate one mix or benchmark a directory of aligned mixes."""
import argparse
import json
from pathlib import Path
import time

import numpy as np
import soundfile as sf

from prepare import ffmpeg


def decoded_mix(path, output):
    if path.suffix.lower() == ".wav":
        audio, sr = sf.read(path, dtype="float32", always_2d=True)
        if sr == 44100 and audio.shape[1] == 2:
            return audio.T.copy()
    decoded = output / "decoded.wav"
    ffmpeg("-i", path, "-ac", 2, "-ar", 44100, "-c:a", "pcm_f32le", decoded)
    try:
        return sf.read(decoded, dtype="float32", always_2d=True)[0].T.copy()
    finally:
        decoded.unlink()


def roformer(weights, config_path, device, model_name):
    from bs_roformer import BSRoformerSession, demix_track

    session = BSRoformerSession(model_path=weights, config_path=config_path,
                                model_name=model_name, device=str(device)).load()

    def run(mix):
        return demix_track(session._config, session._model, mix, device)[0]

    return run


def backend(args, torch, device):
    if args.model in ("sw", "cascade"):
        sw = roformer(args.weights, args.config, device,
                      "roformer-model-bs-roformer-sw-by-jarredou")
        if args.model == "sw":
            return sw
        vocal = roformer(args.vocal_weights, args.vocal_config, device, "custom-vocal")

        def cascade(mix):
            vocals = vocal(mix)["vocals"]
            separated = sw(mix - torch.from_numpy(vocals))
            separated["vocals"] = vocals
            return separated

        return cascade

    if args.model == "htdemucs_ft":
        from demucs.apply import apply_model
        from demucs.pretrained import get_model

        model = get_model("htdemucs_ft").eval()
        model.to(device)

        def demucs(mix):
            ref = mix.mean(0)
            mean, std = ref.mean(), ref.std().clamp_min(1e-8)
            stems = apply_model(model, ((mix - mean) / std)[None], shifts=1,
                                split=True, overlap=.25, device=device)[0]
            return dict(zip(model.sources, (stems * std + mean).cpu().numpy(), strict=True))

        return demucs

    from utils.model_utils import demix
    from utils.settings import get_model_from_config

    model, config = get_model_from_config("scnet", str(args.config))
    checkpoint = torch.load(args.weights, map_location="cpu", weights_only=True)
    model.load_state_dict(checkpoint.get("state_dict", checkpoint))
    model.to(device).eval()
    config.inference.batch_size = 1
    return lambda mix: demix(config, model, mix, device, model_type="scnet")


def main():
    import torch

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", choices=["sw", "cascade", "scnet", "htdemucs_ft"],
                        required=True)
    inputs = parser.add_mutually_exclusive_group(required=True)
    inputs.add_argument("--input", type=Path)
    inputs.add_argument("--batch", type=Path, help="Directory of folders with mix.wav")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--device", default="auto")
    parser.add_argument("--weights", type=Path)
    parser.add_argument("--config", type=Path)
    parser.add_argument("--vocal-weights", type=Path)
    parser.add_argument("--vocal-config", type=Path)
    args = parser.parse_args()
    if args.model != "htdemucs_ft" and not (args.weights and args.config):
        parser.error("--weights and --config are required for this model")
    if args.model == "cascade" and not (args.vocal_weights and args.vocal_config):
        parser.error("--vocal-weights and --vocal-config are required for the cascade")
    device = torch.device(("cuda:0" if torch.cuda.is_available() else "cpu")
                          if args.device == "auto" else args.device)
    torch.set_num_threads(4)
    torch.manual_seed(0)
    load_started = time.monotonic()
    separate = backend(args, torch, device)
    load_seconds = time.monotonic() - load_started
    tracks = [(args.input, args.output)] if args.input else [
        (path, args.output / path.parent.name) for path in sorted(args.batch.glob("*/mix.wav"))
        if not (args.output / path.parent.name / "runtime.json").exists()
    ]
    if not tracks and args.batch and not list(args.batch.glob("*/mix.wav")):
        parser.error("no input mixes found")
    for path, output in tracks:
        start = time.monotonic()
        output.mkdir(parents=True, exist_ok=True)
        mix = decoded_mix(path, output)
        if device.type == "cuda":
            torch.cuda.reset_peak_memory_stats(device)
        with torch.inference_mode():
            stems = separate(torch.from_numpy(mix))
        for stem, audio in stems.items():
            if audio.shape != mix.shape or not np.isfinite(audio).all():
                raise ValueError(f"Invalid {stem} output shape or non-finite samples")
            sf.write(output / f"{stem}.wav", audio.T, 44100, subtype="FLOAT")
        runtime = {"model": args.model, "device": str(device), "modelLoadSeconds": load_seconds,
                   "seconds": time.monotonic() - start, "audioSeconds": mix.shape[1] / 44100,
                   "peakReservedBytes": torch.cuda.max_memory_reserved(device)
                   if device.type == "cuda" else 0}
        (output / "runtime.json").write_text(json.dumps(runtime) + "\n")
        print(path.parent.name, runtime, flush=True)


if __name__ == "__main__":
    main()
