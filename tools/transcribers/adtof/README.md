# ADTOF drum adapter

[ADTOF-pytorch](https://github.com/xavriley/ADTOF-pytorch) runs its Frame RNN
on an aligned drum stem. The local extraction bake-off recommends using
the drum stem from BS-RoFormer SW. The pinned package includes the default
checkpoint. Use `--weights` for a compatible local Frame RNN checkpoint.

Install with Python 3.11, Git and uv in a directory outside the checkout.

```sh
tools/transcribers/adtof/setup.sh /path/to/adtof
/path/to/adtof/venv/bin/python tools/transcribers/adtof/run.py \
  --input drums.wav --output hits.json --device cpu
```

The adapter writes `[[seconds, gmPitch, velocity], ...]` in source seconds.
ADTOF peak-normalizes its decoded mono signal to −4 dBFS with at most +24 dB gain before computing model features.
The sidecar records this preprocessing in `inputNormalization`, `peakTargetDbfs`, `inputMonoPeak` and `appliedGainDb`.
Velocities are fixed at 96. GM pitches are 35 (kick), 38 (snare), 47 (tom),
42 (hi-hat) and 49 (cymbal). The `<output>.meta.json` sidecar saves the
model, package version, checkpoint hash, thresholds and device. The setup
script installs CPU PyTorch. A compatible local CUDA or ROCm environment
can use `--device auto` or a Torch device such as `cuda:0`.

See the [local extraction configuration](../../../headless/README.md#audio-only-reconstruction).
Run the weight-free test with `python3 -m unittest discover -s tools/transcribers/adtof`.

Pass an aligned full mix with `--mix` to resolve ambiguous stem toms. Reconstruction supplies the mix through `OPENSMPSDECK_DRUM_REFERENCE`. A mix hi-hat within 35 ms confirms a relabel when neither a mix tom nor an existing stem hi-hat is nearby. The original attack time and velocity are preserved. The sidecar records the mix hash, gain and relabel count when the mix pass runs.
