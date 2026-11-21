# MuScriptor adapter

This optional adapter runs one local MuScriptor 0.4.0 checkpoint on an aligned
WAV stem. OpenSMPSDeck ships the adapter code only. The
[MuScriptor package](https://pypi.org/project/muscriptor/0.4.0/) uses the
[MIT licence](https://github.com/muscriptor/muscriptor/blob/v0.4.0/LICENSE).
The gated [Large](https://huggingface.co/MuScriptor/muscriptor-large),
[Medium](https://huggingface.co/MuScriptor/muscriptor-medium), and
[Small](https://huggingface.co/MuScriptor/muscriptor-small) weights use
CC BY-NC 4.0 with additional model-card conditions. Review and accept the
conditions for your chosen checkpoint using your own Hugging Face account.

## Install locally

Install [uv](https://docs.astral.sh/uv/) and authenticate with Hugging Face
using `hf auth login` or a locally supplied `HF_TOKEN`. Choose an install
directory outside the repository. The setup script creates the environment,
installs MuScriptor 0.4.0, and downloads the weights and matching model
configuration from a single revision of the gated repository.

```sh
./tools/transcribers/muscriptor/setup.sh /path/to/muscriptor-env cpu medium --accept-license
```

For ROCm, set `MUSCRIPTOR_TORCH_INDEX` to the wheel index recommended for
your hardware and ROCm release. Optionally specify exact packages with
`MUSCRIPTOR_TORCH` and `MUSCRIPTOR_TORCHAUDIO`. Use `MUSCRIPTOR_PYTHON`
if the wheel requires a specific Python version. See the
[AMD PyTorch installation guide](https://rocm.docs.amd.com/projects/ai-ecosystem/en/latest/frameworks/pytorch/install.html).

```sh
MUSCRIPTOR_TORCH_INDEX=https://download.pytorch.org/whl/rocm7.2 \
  ./tools/transcribers/muscriptor/setup.sh /path/to/muscriptor-env rocm medium --accept-license
```

ROCm uses Torch device names such as `cuda:0`. Large uses fp32 by default.
If it hits GPU memory limits, the adapter retries in bf16 when the device
supports it and fp16 otherwise. Reduced-precision GPU runs stage model loading
in host RAM. To make precision explicit, pass `--precision float32`,
`--precision bfloat16`, or `--precision float16`. The reference run used
CPython 3.11.14 and Torch 2.13.0+rocm10.1.0 on a 16 GB RX 9070 XT at
approximately real time. CPU inference works and takes longer.

## Configure OpenSMPSDeck

Save a user-local `~/.config/opensmpsdeck/transcribers.json` with absolute paths.
The same configuration is used by the headless MCP server.

```json
{
  "muscriptor-medium": [
    "/path/to/muscriptor-env/venv/bin/python",
    "/path/to/OpenSMPSDeck/tools/transcribers/muscriptor/run.py",
    "--model", "medium",
    "--weights", "/path/to/muscriptor-env/weights/medium/model.safetensors",
    "--input", "{input}", "--output", "{output}",
    "--role", "{role}", "--device", "{device}"
  ]
}
```

For another size, change `--model` and use the matching weights. MuScriptor
0.4.0 generates fresh output in non-overlapping five-second chunks. For
Small and Medium, tie forcing defaults to off because combining it
with a learned instrument prompt can suppress long spans. Large defaults to
tie forcing on. Override this with `--tie-forcing` or `--no-tie-forcing`.

| Stem role | Prompt |
| --- | --- |
| `lead`, `synth` | `synth_lead` |
| `vocals`, `backing_vocals` | `voice` |
| `bass` | `electric_bass` |
| `keys`, `keyboard`, `backing` | `electric_piano, chromatic_percussion, synth_lead, synth_pad` |
| `guitar`, `strings`, `brass`, `woodwinds` | Corresponding instrument families |

Override with `--prompt GROUP,GROUP` or `--prompt none` in the command
template. Use separate named entries for different fixed prompt overrides.
The wrapper writes `[[start, end, pitch], ...]` in stem seconds and a
`<output>.meta.json` sidecar with model, prompt, precision, version, and
checkpoint, model config, and adapter hashes.

Use `--transcriber muscriptor-medium` for audio-only imports.
From this directory, run `python -m unittest` to check prompt mapping and
note serialization without Torch, audio, or weights. See the
[headless guide](../../../headless/README.md#external-transcription) for CLI
and MCP examples.
