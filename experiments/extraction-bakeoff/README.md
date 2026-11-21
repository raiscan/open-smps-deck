# Local extraction bake-off

Evaluate local source separation and automatic transcription from a production
mix. The experiment works on aligned audio and note events before OpenSMPSDeck's
reconstruction. It uses the same Basic Pitch and RMVPE baselines as the
[earlier transcription bake-off](../transcription-bakeoff/). See
[RESULTS.md](RESULTS.md) for the public subsets and measurements.

## Setup

Use Linux, Python 3.11, FFmpeg and a separate virtual environment for each
candidate. Install [requirements.txt](requirements.txt) in the scoring
environment. The test device was an AMD RX 9070 XT. Use a ROCm PyTorch build
that supports gfx1201. An existing working ROCm environment can be shared
read-only by placing its site-packages path in a `.pth` file in each candidate
environment. Select the discrete device with `HIP_VISIBLE_DEVICES`. PyTorch
addresses the selected ROCm device as `cuda:0`.

Keep inputs, checkpoints, caches and generated files outside the checkout.
Use the same working directory for the commands in this guide.

```bash
umask 077
export WORK="$(mktemp -d "${SCRATCH:?set a disk-backed scratch root}/task-XXXXXXXX")"
touch "$WORK/.keep"
mkdir "$WORK/tmp"
export TMPDIR="$WORK/tmp" XDG_CACHE_HOME="$WORK/cache"
export UV_CACHE_DIR="$WORK/uv" HF_HOME="$WORK/hf" TORCH_HOME="$WORK/torch"
export TRITON_CACHE_DIR="$WORK/triton" MIOPEN_CUSTOM_CACHE_DIR="$WORK/miopen"
export NUMBA_CACHE_DIR="$WORK/numba"
export OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=2
```

Install only the requested inference backends. Keep local weight and YAML
files together where required. The adapters never download weights during
inference, except that Demucs resolves its named model through its normal cache.

| Adapter | Tested setup |
|---|---|
| SW and vocal cascade | [bs-roformer-infer](https://github.com/openmirlab/bs-roformer-infer/tree/e3ad851fb24333ae36ae4bfd5ec327dbbf764bea) 0.1.6 with ROCm PyTorch, einops, rotary-embedding-torch, beartype, ml-collections, PyYAML and absl-py. [SW checkpoint and YAML](https://huggingface.co/koidn/BS-RoFormer-SW-6stem). The cascade first extracts vocals with [viperx 317](https://github.com/ZFTurbo/Music-Source-Separation-Training/blob/main/docs/pretrained_models.md), then runs SW on mix minus vocals. |
| SCNet XL | [Music-Source-Separation-Training](https://github.com/ZFTurbo/Music-Source-Separation-Training/tree/1523206d12a521bdb20673726562524cf0f9635b) on `PYTHONPATH`, plus omegaconf, ml-collections and absl-py. Use the [v1.0.13 XL checkpoint and config](https://github.com/ZFTurbo/Music-Source-Separation-Training/releases/tag/v1.0.13). |
| HTDemucs-ft | [demucs](https://github.com/adefossez/demucs) 4.1.0, named model `htdemucs_ft`. |
| YourMT3+ | [mt3-infer](https://github.com/openmirlab/mt3-infer/tree/2ea7d3ebbce9ea7bb65110a779e59ac357f2d7e4), Transformers 4.45.2, tokenizers <0.21 and [YPTF.MoE+Multi (noPS)](https://huggingface.co/spaces/Richhiey/YourMT3/tree/main/amt/logs/2024/mc13_256_g4_all_v7_mt3f_sqr_rms_moe_wf4_n8k2_silu_rope_rp_b36_nops/checkpoints). Use a local checkpoint path in `--weights`. |
| Basic Pitch | [basic-pitch](https://github.com/spotify/basic-pitch) 0.4.0, ONNX Runtime 1.23.2, librosa and resampy. Optionally install with `--no-deps` and install its non-TensorFlow requirements separately. |
| MuScriptor | [Repository setup](../../tools/transcribers/muscriptor/) for 0.4.0. Pass the desired variant's actual `model.safetensors` beside `config.json`. Default small precision is FP32. Use `--precision bfloat16` for the tested medium and large GPU runs. |
| RMVPE | [RVC RMVPE implementation](https://github.com/RVC-Project/Retrieval-based-Voice-Conversion-WebUI/tree/81eed5e8f68b6bed1789f682fe78cdd324495afc) on `PYTHONPATH`, with [local weights](https://huggingface.co/lj1995/VoiceConversionWebUI/blob/main/rmvpe.pt). Set `RVC_CUDA_GRAPH=0`. Uses the earlier bake-off's 10 ms pitch segmentation. |
| ADTOF | [ADTOF-pytorch](https://github.com/xavriley/ADTOF-pytorch/tree/85c192e78f716ea0b111cc8a5ee4a8f6a3a4f8a9) and its Frame_RNN weights. |

The result uses SW weights with SHA-256
`24e7d35ee9c64415673d3fd33e06a67cac2c103c5df6267ba1576459c775916e`.
Other versions can change inference and should get new output directories.

## Stage public inputs

`prepare.py --output DIRECTORY` has these subcommands.

- `multtipop ID…` downloads only audio from the metadata's public videos and
  stages the aligned reference MIDI. Run `pop-clips FULL_MIX_DIRECTORY` into a
  separate directory for excerpt inference. Negative excerpt timestamps use
  left padding. The excerpt's time zero is MIDI time zero.
- `slakh` stages the first 30 seconds of 18 evenly spaced IDs from the
  [Slakh2100-redux test mix mirror](https://huggingface.co/datasets/DreamyWanderer/Slakh2100-FLAC-Redux-Reduced).
  This run uses the mirror's `all_src.mid`. Exact rendered-source MIDI is
  available for the additional five full-track controls below.
- `musdb PARQUET` extracts five complete 25-second examples starting at 45 s
  from [the first test shard](https://huggingface.co/datasets/roro128/musdb18-hq-flac/blob/main/data/test-00000.parquet).
  This command additionally needs PyArrow. Delete the parquet after extraction.
- `mir-st500 ID…` uses 28 seconds, starting two seconds before the first
  labeled vocal onset, from the [MIR-ST500 test split](https://github.com/york135/singing_transcription_ICASSP2021/tree/master/MIR-ST500_20210206).
  It uses yt-dlp and the dataset's public audio links.

`stage_slakh_h2h.py OUTPUT ID…` stages full 44.1 kHz mixes and class-grouped
reference stems from the [Slakh2100-redux source mirror](https://huggingface.co/datasets/J1mmymm/MIMuT_Data).
It uses the per-source MIDI that was actually rendered. Five tracks can be
downloaded independently of the full release. The Slakh set is local-only.

## Inference and scoring

```bash
"$SEPARATOR_PY" separate.py --model sw --batch "$WORK/pop-clips" \
  --weights "$SW_WEIGHTS" --config "$SW_CONFIG" --output "$WORK/pop-sw"

"$PY" matrix.py manifest "$WORK/pop-clips" "$WORK/ymt3" mix > "$WORK/cases.json"
"$YOURMT3_PY" notes.py --model yourmt3 --weights "$YOURMT3_WEIGHTS" \
  --manifest "$WORK/cases.json"
"$PY" matrix.py score "$WORK/pop" mix="$WORK/ymt3"

"$PY" matrix.py manifest "$WORK/pop-sw" "$WORK/bp" bass guitar piano other > "$WORK/stems.json"
"$BASIC_PITCH_PY" notes.py --model basic-pitch --manifest "$WORK/stems.json"
"$PY" matrix.py score "$WORK/pop" bass="$WORK/bp" guitar="$WORK/bp" \
  piano="$WORK/bp" other="$WORK/bp"

"$PY" evaluate.py separation "$WORK/musdb" "$WORK/musdb-sw"
```

Use `--batch` for separation or `--manifest` for notes to keep the model
loaded across excerpts. The batch notes sidecars store labeled notes so
you can score MIDI program families and assemble a MIDI with
`matrix.py midi`. Add `drums=RESULTS` to replace the drum output of a mix-based
transcriber with a dedicated run. The flat note JSON conforms to the
[external transcription hook](../../headless/README.md#external-transcription).
A single direct command emits triples and compact provenance.

```json
{
  "basic-pitch-local": [
    "/path/to/basic-pitch-venv/bin/python", "/path/to/experiment/notes.py",
    "--model", "basic-pitch", "--input", "{input}", "--output", "{output}",
    "--role", "{role}", "--device", "cpu"
  ]
}
```

Use `--model yourmt3 --weights FILE` for YourMT3+. For RMVPE pass
`--model rmvpe --weights FILE` in the environment with the upstream RVC
source. The ADTOF command uses `--model adtof --role drums` and returns GM
drum pitches. A drum-aware caller should route these events separately.
A mix-based command's note triples drop program/drum labels by design.
Use `--benchmark-labels` to retain that information in the sidecar when
the caller supports it.

The `gold.py` scorer reads onset and optional release annotations in
`regions[]`. Each region has `id`, `start_s`, `end_s`, and `notes[]`.
Each note has `attack_s`, `release_s` (nullable) and `midi`. Use a
benchmark-label sidecar for local notes or `--midi-track` for the designated
Suno synth part. `listen.py` accepts an array of cases with `title`,
`mix`, `suno`, `local` and `duration`, plus optional `highlights` pairs.
Its output is a standalone HTML with MP3 data URIs. Keep it private.

## Original MIDI render head-to-head

Use `stage_original_h2h.py MANIFEST SOUND_FONT OUTPUT` with a local GM soundfont
and FluidSynth. A manifest entry contains `id`, `midi`, and `parts` in the
MIDI's instrument order. Each part has `role`, zero-based GM `program`, and
optional `pan` (−1 to 1) and `trimDb`. Roles are `lead`, `bass`,
`drums`, `piano`, `guitar`, and `other`. The renderer applies light stereo
reverb and uses a shared peak-normalization gain across the mix and references.
Every staged mix includes its rendered reference MIDI, the grouped stems and
the exact scored span in `source.json`.

For tracks with `suno/DONE`, score both stem takes and the MIDI. The local
MIDI directory must contain `SONG_ID/local.mid`. Supply as many local
separator output directories as desired.

```bash
"$PY" suno_h2h.py "$HEAD_TO_HEAD" "$LOCAL_MIDI" \
  --separator sw="$LOCAL_STEMS" > "$PRIVATE_RESULTS/head-to-head.json"
```

Save exported MIDI as `suno/suno.mid` and name each WAV by part and take,
for example `Bass take 1.wav` or `Bass_1.wav`. The scorer also accepts
`suno/stems.json` with take IDs mapping to objects of part name to WAV
filename arrays. Write `DONE` only when both takes and MIDI are present.
The scorer reports pending IDs and paired aggregates. One common transport
delay per stem take is estimated from the reference mix and the summed Suno
stems. It is applied to all of that take's stems and reported in samples.
The MIDI uses its supplied sample-zero clock.

## Metrics

The note tables aggregate one-to-one mir_eval matches within each track.
Onsets have a 50 ms tolerance and MIDI pitch must agree. Onset-plus-offset
scoring additionally requires release agreement within 20% of note duration
or 50 ms, whichever is greater. Cross-instrument matches are disallowed
for labeled F1 (piano, guitar, bass, vocals and remaining GM programs).
Drum events are matched at ±50 ms inside five GM classes. F1 is micro-averaged.
Suno head-to-head stem tables group the dedicated lead into the other
instrumental region and use the same scored span on each system.

SDR is scale-dependent, computed from reference energy divided by squared
estimate error. Report the median per stem and the mean of those medians.
Piano and guitar are summed into other for four-stem MUSDB comparison.
For head-to-head, report a common bass, drums and other table as well as a
finer table when the stems support it. Silent reference classes are omitted.
Wall times exclude one-time model load and the separator's time is separate
from transcription time. GPU memory is Torch's peak reserved bytes. Runs
shared the GPU with other jobs, so timings are descriptive.
