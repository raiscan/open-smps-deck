# BS-RoFormer SW stem separator

This optional adapter uses [BS-RoFormer-Infer](https://github.com/openmirlab/bs-roformer-infer)
with the SW six-stem checkpoint. The weight licence is unknown. Review the
terms applicable to your use before downloading. Install the checkpoint in
your own directory outside the checkout.

## Install

You need FFmpeg, Git, uv and Python 3.11 or newer. Choose CPU, CUDA or ROCm with a PyTorch wheel
index for your OS and GPU. ROCm uses PyTorch's CUDA device API internally.

```sh
tools/separators/bs-roformer/setup.sh "$HOME/.local/share/opensmpsdeck/bs-roformer" cpu --download-weights
# Example for AMD ROCm 10.1 and Radeon RX 9070 (gfx1201)
BS_ROFORMER_TORCH_INDEX=https://stable.repo.amd.com/rocm/whl-next/ \
BS_ROFORMER_TORCH='torch[device-gfx1201]==2.13.0+rocm10.1.0' \
  tools/separators/bs-roformer/setup.sh "$HOME/.local/share/opensmpsdeck/bs-roformer-rocm" rocm --download-weights
```

Setup pins the inference engine revision. With `--download-weights`, setup
prints the pinned mirror URLs, verifies the checkpoint and YAML hashes and
records both in `weights/SHA256SUMS`. You can also supply your own local
checkpoint and matching configuration. Inference requires both paths and
uses the installed files offline.

## Configure and run

Add a command to `~/.config/opensmpsdeck/separators.json` using absolute paths.

```json
{
  "bs-roformer": [
    "/path/to/install/venv/bin/python",
    "/path/to/repo/tools/separators/bs-roformer/run.py",
    "{input}", "{outputDir}", "--device", "{device}",
    "--checkpoint", "/path/to/install/weights/BS-Rofo-SW-Fixed.ckpt",
    "--config", "/path/to/install/weights/BS-Rofo-SW-Fixed.yaml"
  ]
}
```

```sh
java -jar "$CLI" reconstruct separate mix.mp3 separated --separator bs-roformer --device rocm
java -jar "$CLI" reconstruct import separated import-work \
  --catalogue catalogue.json --transcriber muscriptor-medium --run
```

Set up the tonal adapter before import using the
[local extraction guide](../../../headless/README.md#external-stem-separation).
`auto` selects a PyTorch GPU when available and CPU otherwise. Explicit GPU
requests require the matching backend. The adapter decodes the mix once to
44.1 kHz stereo float WAV. Every stem starts at the same sample and has the
same sample count as that decoded mix. The six model outputs are bass, drums,
other, vocals, guitar and piano. Import classifies piano under the keyboard
family and gives it the piano role.

`separation.json` records the inference package version, model, checkpoint
hash, stem order, device, chunk size and overlap used for the run.

## Vocal-first quality option

Keep a local [Viperx 317 vocal checkpoint and YAML](https://github.com/ZFTurbo/Music-Source-Separation-Training/blob/main/docs/pretrained_models.md)
outside the checkout. Copy the SW command under a new name such as
`bs-roformer-cascade` and append these arguments.

```json
[
  "--vocal-checkpoint", "/path/to/weights/model_bs_roformer_ep_317_sdr_12.9755.ckpt",
  "--vocal-config", "/path/to/weights/config_bs_roformer_384_1024_2.yaml"
]
```

Select it with `--separator bs-roformer-cascade`. The adapter subtracts the
Viperx vocal estimate from the mix, runs SW on the instrumental residual
and publishes the Viperx vocal stem alongside the SW instrumental stems.
The extra pass takes longer and records its own hashes and settings in
`separation.json`.

## Test with a mock model

```sh
/path/to/install/venv/bin/python -m unittest discover -s tools/separators/bs-roformer
```

This exercises WAV conversion, stem writing and provenance with a mock model.
