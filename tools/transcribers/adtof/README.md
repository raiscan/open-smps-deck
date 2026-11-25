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

Pass an aligned full mix with `--mix` to resolve ambiguous stem toms. Reconstruction supplies the mix through `OPENSMPSDECK_DRUM_REFERENCE`. A mix hi-hat within 35 ms confirms a relabel when neither a mix tom nor an existing stem hi-hat is nearby. The original attack time and velocity are preserved. Each mix hi-hat can confirm one stem tom. Within three model frames of the stem onset, a raw tom activation of at least 0.08 vetoes the relabel if it equals or exceeds the strongest raw hat activation. The sidecar always records `mixPassStatus`, `tomToHatCount` and the recovered source times in `tomToHatOnsets`. A completed mix pass also records its input hash and gain. Reconstruction fits the hat sample to original stem hats. When every hat was recovered, it uses the aligned mix for that target.

### Experimental kit retrieval

Register a second drum command named `kit` using the same interpreter and arguments as `adtof`, with `--kit-retrieval` appended. Select it with `reconstruct run --audio-only ... --drum-detector kit`. The CLI supplies the catalogue path, or the adapter accepts `--catalogue INDEX.json` for a standalone run with S3K stock samples.

The adapter groups similar attacks in the drum stem and builds a median spectral prototype from recurrent examples. It compares prototypes against stock PCM at catalogue playback rates and clocked Sega PSG noise bursts. A nearby acoustic tie can use the detected GM class. DAC C0 needs an additional score margin. A stock vote requires a cluster score of at least 0.8. A PSG noise match from a non-hat cluster is discarded. Reconstruction checks the vote against the regular fit, including its class penalty, and records the fit distances and decision in voicing evidence. Kit retrieval preserves the detector onsets by default. Add `--kit-additions` to the adapter command to search for repeated attacks on the drum stem. It suppresses additions within 40 ms of an existing attack of any class.
