#!/usr/bin/env python3
"""Run a locally installed MuScriptor 0.4.0 checkpoint on an aligned WAV."""
import argparse
import hashlib
from importlib.metadata import version
import json
import math
from pathlib import Path
import platform

KEYS = ("electric_piano", "chromatic_percussion", "synth_lead", "synth_pad")
PROMPTS = {
    "lead": ("synth_lead",),
    "vocals": ("voice",),
    "backing_vocals": ("voice",),
    "piano": ("acoustic_piano", "electric_piano"),
    "other": None,
    "synth": ("synth_lead",),
    "bass": ("electric_bass",),
    "keys": KEYS,
    "keyboard": KEYS,
    "backing": KEYS,
    "guitar": ("acoustic_guitar", "clean_electric_guitar", "distorted_electric_guitar"),
    "strings": ("violin", "viola", "cello", "contrabass", "string_ensemble", "synth_strings"),
    "brass": ("trumpet", "trombone", "tuba", "french_horn", "brass_section", "soprano_and_alto_sax"),
    "woodwinds": ("oboe", "bassoon", "clarinet", "flutes", "soprano_and_alto_sax"),
}


def instrument_prompt(role, override):
    if override is not None:
        if override.strip().lower() == "none":
            return None
        names = [name.strip() for name in override.split(",")]
        if not all(names):
            raise ValueError("Expected comma-separated instrument names or 'none'")
        return names
    if role.lower() not in PROMPTS:
        raise ValueError(f"No MuScriptor prompt for role {role!r}. Supply --prompt.")
    default = PROMPTS[role.lower()]
    return None if default is None else list(default)


def note_rows(events, duration):
    rows = []
    for event in events:
        start, end, pitch = event.start_event.start_time, event.end_time, event.start_event.pitch
        if math.isfinite(start) and math.isfinite(end) and 0 <= pitch <= 127:
            start, end = max(0, start), min(duration, end)
            if start < end:
                rows.append([start, end, pitch])
    return sorted(rows)


def write_notes(output, rows):
    output.write_text(json.dumps(rows, allow_nan=False) + "\n", encoding="utf-8")


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def infer(args, prompt, tie_forcing, duration):
    import torch
    from muscriptor.events import NoteEndEvent
    from muscriptor.transcription_model import TranscriptionModel

    device_name = args.device
    if device_name == "auto":
        device_name = "cuda:0" if torch.cuda.is_available() else "cpu"
    device = torch.device(device_name)
    if device.type == "cuda" and not torch.cuda.is_available():
        raise ValueError(f"Torch device {device} is unavailable")

    torch.set_num_threads(2)
    torch.set_num_interop_threads(1)
    torch.manual_seed(0)

    def decode(precision):
        # The upstream loader allocates its fp32 model and checkpoint on the
        # load device before casting. Stage reduced-precision loads in RAM
        # so they also work on GPUs that cannot fit those fp32 allocations.
        load_device = "cpu" if precision != "float32" else device
        model = TranscriptionModel.load_model(args.weights, device=load_device, dtype=precision)
        if load_device == "cpu" and device.type != "cpu":
            model._model.to(device)
            model._device = device
            # These conditioners also store their allocation device as a
            # plain attribute, which Module.to does not update.
            provider = model._model.condition_provider
            provider.device = device
            for conditioner in provider.conditioners.values():
                conditioner.device = device
        model._model.autocast.enabled = False
        with torch.inference_mode():
            events = model.transcribe(args.input, instruments=prompt, batch_size=1,
                                      prelude_forcing=tie_forcing)
            return note_rows((event for event in events if isinstance(event, NoteEndEvent)
                              and (args.role.lower() != "other" or event.start_event.instrument != "drums")),
                             duration)

    precision = "float32" if args.precision == "auto" else args.precision
    try:
        rows = decode(precision)
    except torch.cuda.OutOfMemoryError:
        if device.type != "cuda" or args.precision != "auto":
            raise
        precision = None

    if precision is None:
        # The exception's traceback (and its tensors) has gone out of scope.
        torch.cuda.empty_cache()
        with torch.cuda.device(device):
            precision = "bfloat16" if torch.cuda.is_bf16_supported(including_emulation=False) else "float16"
        rows = decode(precision)
    return rows, {
        "precision": precision,
        "device": str(device),
        "torch": torch.__version__,
        "python": platform.python_version(),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--weights", type=Path, required=True, help="Local model.safetensors from setup.sh")
    parser.add_argument("--model", choices=["small", "medium", "large"], default="large")
    parser.add_argument("--role", default="lead")
    parser.add_argument("--device", default="auto", help="auto, cpu, or cuda:0 (also used by ROCm)")
    parser.add_argument("--prompt", help="Comma-separated model instrument names, or none")
    parser.add_argument("--precision", choices=["auto", "float32", "bfloat16", "float16"], default="auto")
    parser.add_argument("--tie-forcing", action=argparse.BooleanOptionalAction)
    args = parser.parse_args()
    try:
        package_version = version("muscriptor")
        if package_version != "0.4.0":
            raise ValueError(f"Expected MuScriptor 0.4.0, found {package_version}")
        from soundfile import info

        with args.weights.with_name("config.json").open(encoding="utf-8") as stream:
            config = json.load(stream)
        # The local config is authoritative for architecture. Check the label
        # before using it to choose defaults and report provenance.
        if config.get("variant") != args.model:
            raise ValueError("--model must match the checkpoint's config.json")
        prompt = instrument_prompt(args.role, args.prompt)
        tie_forcing = args.model == "large" if args.tie_forcing is None else args.tie_forcing
        audio = info(args.input)
        duration = audio.frames / audio.samplerate
        rows, runtime = infer(args, prompt, tie_forcing, duration)
        metadata = {
            "tool": "MuScriptor",
            "version": package_version,
            "model": args.model,
            "prompt": prompt,
            "tieForcing": tie_forcing,
            "weightsSha256": sha256(args.weights),
            "modelConfigSha256": sha256(args.weights.with_name("config.json")),
            "adapterSha256": sha256(Path(__file__)),
            **runtime,
        }
        write_notes(args.output, rows)
        Path(str(args.output) + ".meta.json").write_text(json.dumps(metadata) + "\n", encoding="utf-8")
    except (ValueError, OSError) as exc:
        parser.exit(1, f"MuScriptor: {exc}\n")


if __name__ == "__main__":
    main()
