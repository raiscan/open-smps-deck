# Basic Pitch adapter

[Basic Pitch](https://github.com/spotify/basic-pitch) runs its bundled ICASSP
2022 ONNX model on the CPU. The local extraction bake-off recommends it for
the separated vocal stem.

Install with Python 3.11 and uv in a directory outside the checkout.

```sh
tools/transcribers/basic-pitch/setup.sh /path/to/basic-pitch
/path/to/basic-pitch/venv/bin/python tools/transcribers/basic-pitch/run.py \
  --input vocals.wav --output vocals.json --role vocals
```

The adapter writes `[[start, end, pitch], ...]` in source seconds and a
`<output>.meta.json` sidecar with the package version, checkpoint hash,
ONNX Runtime version, minimum note length and the dominant model pitch sampled
at 10 ms for audio-only vocal cleanup. Use `--device cpu` or `auto`.
See the [local extraction configuration](../../../headless/README.md#audio-only-reconstruction)
for CLI and MCP selection. Run the weight-free test with
`python3 -m unittest discover -s tools/transcribers/basic-pitch`.
