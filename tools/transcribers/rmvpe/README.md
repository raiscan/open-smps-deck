# RMVPE vocal transcriber

This local adapter extracts the lead pitch from an aligned vocals stem using
RMVPE. It runs the model on 10-second, 16 kHz mono chunks with a 10 ms pitch
hop. The final chunk is padded for inference and trimmed back to the source
duration. On ROCm the adapter defaults to `MIOPEN_FIND_MODE=FAST`.

Run `./setup.sh INSTALL_DIR` to install a pinned RVC source checkout and a
Python 3.11 CPU environment with `uv`. For GPU inference, use a compatible
CUDA or ROCm PyTorch environment with NumPy, SciPy, SoundFile and librosa.
Supply a trusted local `rmvpe.pt` checkpoint and keep model weights and
source recordings outside the repository.

Add an entry to `transcribers.json`:

```json
{
  "rmvpe": [
    "/path/to/venv/bin/python", "/path/to/repo/tools/transcribers/rmvpe/run.py",
    "--input", "{input}", "--output", "{output}", "--role", "{role}", "--device", "{device}",
    "--source", "/path/to/rvc", "--weights", "/path/to/rmvpe.pt"
  ]
}
```

Set `defaults.vocals` to `muscriptor-medium,basic-pitch,rmvpe` when all
three entries are installed, or pass
`--transcriber-for vocals=muscriptor-medium,basic-pitch,rmvpe` on an
audio-only run. Set `defaults.vocals` to `rmvpe` to use it alone.

The adapter writes `[[startSeconds, endSeconds, midiPitch], ...]`. It
smooths the pitch contour over five frames and splits on a sustained pitch
change or an unvoiced gap. The `<output>.meta.json` sidecar records the
checkpoint hash, effective device and `f0Cents10ms`, a base64-encoded array
of little-endian unsigned 16-bit MIDI cents with zero for unvoiced frames.
The reconstruction uses the fractional contour to look for supported bends
on held notes. When selecting among candidates it measures agreement against
periodicity in the vocal stem.

Run the mock tests without a checkpoint or GPU with
`python3 -m unittest discover -s tools/transcribers/rmvpe -v` from the repo root.
