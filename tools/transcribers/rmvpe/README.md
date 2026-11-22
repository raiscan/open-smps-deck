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

When the command is named `rmvpe`, audio-only reconstruction uses it by
default for vocals. Set `vocalLead.choice` to `muscriptor-medium`,
`basic-pitch` or `fusion` to choose an alternative. Use `defaults.vocals`
to list the adapters to compare for fusion. Set `vocalLead.registerShift`
to the number of semitones to add to the selected vocal line (default 12).

The default adapter mode uses strong spectral-flux onsets, voicing starts
and sustained pitch changes as boundaries. Each note takes the rounded
median of the raw voiced frames in its segment. Short gaps can extend
the previous release while preserving both attacks. Add `"--mode",
"contour"` to the command to use the earlier pitch-only segmentation.

To override segmentation, add `"--syllable-settings", "{...}"` to the
configured command, with the JSON object encoded as one argument. All frame
values use the 10 ms grid. The default object is:

```json
{
  "window_samples": 2048,
  "flux_scale": 100,
  "flux_percentile": 93,
  "onset_spacing_frames": 14,
  "onset_voiced_frames": 6,
  "rms_before_frames": 8,
  "rms_after_frames": 5,
  "rms_rise_db": 6,
  "median_frames": 11,
  "pitch_change_semitones": 1,
  "pitch_change_frames": 8,
  "pitch_reference_update": 0.15,
  "bridge_frames": 6,
  "min_voiced_frames": 10,
  "min_note_frames": 9,
  "min_pitch": 40
}
```

The adapter writes `[[startSeconds, endSeconds, midiPitch], ...]`.
The `<output>.meta.json` sidecar records the checkpoint hash, effective
device, effective segmentation settings, detected boundary frames by
type and `f0Cents10ms`, a base64 array of little-endian unsigned 16-bit
MIDI cents with zero for unvoiced frames. Reconstruction saves the note
output under `snapshots/` and the sidecar evidence in the manifest so
replay and review can use it with the adapter and checkpoint offline.

Run the mock tests without a checkpoint or GPU with
`python3 -m unittest discover -s tools/transcribers/rmvpe -v` from the repo root.
