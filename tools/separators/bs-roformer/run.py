#!/usr/bin/env python3
"""Separate one mix with an explicitly installed BS-RoFormer SW checkpoint."""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

import numpy as np
import soundfile as sf


STEMS = ("bass", "drums", "other", "vocals", "guitar", "piano")
RATE = 44100


def load_model(config_file, checkpoint, requested_device):
    from importlib.metadata import version

    import torch
    import yaml
    from bs_roformer.utils import demix_track, get_model_from_config
    from ml_collections import ConfigDict

    accelerator = ("rocm" if torch.version.hip else "cuda") if torch.cuda.is_available() else "cpu"
    device = accelerator if requested_device == "auto" else requested_device
    if device != "cpu" and device != accelerator:
        raise ValueError(f"Requested {device}, but PyTorch reports {accelerator}")
    # PyTorch exposes ROCm under the CUDA device API.
    torch_device = torch.device("cpu" if device == "cpu" else "cuda")
    # FullLoader accepts the published YAML's tuple tags.
    config = ConfigDict(yaml.load(config_file.read_text(), Loader=yaml.FullLoader))
    if (tuple(config.training.instruments) != STEMS or config.training.target_instrument is not None
            or config.model.num_stems != len(STEMS) or not config.model.stereo
            or config.audio.sample_rate != RATE):
        raise ValueError("Expected the stereo 44.1 kHz BS-RoFormer SW six-stem configuration")
    model = get_model_from_config("bs_roformer", config)
    # The published state dict references GELU. Keep PyTorch's restricted loader enabled.
    with torch.serialization.safe_globals([torch._C._nn.gelu]):
        state = torch.load(checkpoint, map_location="cpu", weights_only=True)
    state.pop("_metadata", None)
    model.load_state_dict(state)
    del state
    model.to(torch_device).eval()

    def demix(mix):
        estimates, _ = demix_track(config, model, torch.from_numpy(mix), torch_device)
        return np.stack([estimates[name] for name in STEMS])

    settings = {
        "device": device,
        "torchVersion": str(torch.__version__),
        "chunkSamples": config.inference.get("chunk_size", config.audio.chunk_size),
        "overlap": config.inference.num_overlap,
        "sampleRate": RATE,
        "channels": 2,
    }
    return demix, version("bs-roformer-infer"), settings


def sha256(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def separate(args):
    checkpoint_hash = sha256(args.checkpoint)
    demix, version, settings = load_model(args.config, args.checkpoint, args.device)
    root = args.output_dir
    stems_dir = root / "stems"
    stems_dir.mkdir(parents=True, exist_ok=False)
    mix_file = root / "mix.wav"
    subprocess.run([
        "ffmpeg", "-nostdin", "-v", "error", "-n", "-i", str(args.input),
        "-map", "0:a:0", "-ar", str(RATE), "-ac", "2", "-c:a", "pcm_f32le", str(mix_file),
    ], check=True)
    mix, _ = sf.read(mix_file, dtype="float32", always_2d=True)
    if not len(mix) or not np.isfinite(mix).all():
        raise ValueError("Input must contain finite audio samples")
    estimates = demix(np.ascontiguousarray(mix.T))
    expected = (len(STEMS), 2, len(mix))
    if estimates.shape != expected or not np.isfinite(estimates).all():
        raise ValueError(f"Expected finite aligned output of shape {expected}, got {estimates.shape}")
    for name, audio in zip(STEMS, estimates):
        sf.write(stems_dir / f"{name}.wav", audio.T, RATE, subtype="FLOAT")
    with (root / "separation.json").open("x", encoding="utf-8") as output:
        json.dump({
            "tool": "bs-roformer-infer",
            "model": "BS-RoFormer SW 6-stem",
            "version": version,
            "checkpointSha256": checkpoint_hash,
            "stemOrder": STEMS,
            "settings": settings,
        }, output, indent=2)
        output.write("\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("output_dir", type=Path)
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--device", choices=("auto", "cpu", "rocm", "cuda"), default="auto")
    try:
        separate(parser.parse_args())
    except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError) as error:
        sys.exit(f"BS-RoFormer: {error}")


if __name__ == "__main__":
    main()
