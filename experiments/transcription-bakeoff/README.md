# Per-stem transcription bakeoff

Compare alternative MIDI inputs to the same OpenSMPSDeck reconstruction. This is
an offline experiment with local ROM and catalogue inputs. See [RESULTS.md](RESULTS.md)
for the results and [the benchmark guide](../../docs/architecture/benchmark.md)
for the native metrics.

## Setup

Run the commands from the repository root in Bash on Linux x86-64. Use JDK 25
and Maven. Set `DATA` to your private data root, `ROM` to the local locked-on
Sonic 3 & Knuckles ROM, and `MU_WEIGHTS` to the authorized MuScriptor small
checkpoint. Its matching `config.json` must be adjacent to the checkpoint in
the experiment directory. Set `MU_CONFIG` to the local copy of that config.

```bash
umask 077
export DATA=/path/to/private/data
export ROM=/path/to/s3k.gen
export MU_WEIGHTS=/path/to/authorized/model.safetensors
export MU_CONFIG=/path/to/config.json
export BAKEOFF_TMP="$(mktemp -d "${SCRATCH:-$HOME/scratch}/bakeoff-XXXXXXXX")"
export TMPDIR="$BAKEOFF_TMP"
export UV_CACHE_DIR="$BAKEOFF_TMP/uv-cache"
export UV_PYTHON_INSTALL_DIR="$BAKEOFF_TMP/python"
export UV_UNMANAGED_INSTALL="$BAKEOFF_TMP/bin"
export XDG_CACHE_HOME="$BAKEOFF_TMP/cache"
export NUMBA_CACHE_DIR="$BAKEOFF_TMP/numba"
export JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$BAKEOFF_TMP"
export OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=2 PYTHONDONTWRITEBYTECODE=1
EXPERIMENT=experiments/transcription-bakeoff

curl -fsSL https://astral.sh/uv/0.9.30/install.sh | sh
UV="$BAKEOFF_TMP/bin/uv"
"$UV" python install 3.11.14
"$UV" venv --python 3.11.14 "$BAKEOFF_TMP/venv"
PY="$BAKEOFF_TMP/venv/bin/python"
"$UV" pip install --python "$PY" --no-deps -r "$EXPERIMENT/requirements.txt" \
    --extra-index-url https://download.pytorch.org/whl/cpu

git clone --filter=blob:none https://github.com/RVC-Project/Retrieval-based-Voice-Conversion-WebUI.git \
    "$BAKEOFF_TMP/rvc-source"
git -C "$BAKEOFF_TMP/rvc-source" checkout --detach 81eed5e8f68b6bed1789f682fe78cdd324495afc
export PYTHONPATH="$BAKEOFF_TMP/rvc-source"
curl -fL https://huggingface.co/lj1995/VoiceConversionWebUI/resolve/main/rmvpe.pt \
    -o "$BAKEOFF_TMP/rmvpe.pt"
mkdir -p "$BAKEOFF_TMP/muscriptor"
cp "$MU_WEIGHTS" "$BAKEOFF_TMP/muscriptor/model.safetensors"
cp "$MU_CONFIG" "$BAKEOFF_TMP/muscriptor/config.json"
sha256sum -c <<CHECKSUMS
6d62215f4306e3ca278246188607209f09af3dc77ed4232efdd069798c4ec193  $BAKEOFF_TMP/rmvpe.pt
bbd482c786b895cf7d8f44185073d951adae2ebb8a66f82ca84cd1f84569549c  $BAKEOFF_TMP/muscriptor/model.safetensors
3008fc481e4a1cd978e337eb3759260c270892204db5039235ac939e1f42aeb2  $BAKEOFF_TMP/muscriptor/config.json
CHECKSUMS
```

Use the pinned ONNX runtime for Basic Pitch. Its Linux package declares
TensorFlow even for ONNX use, so the setup installs the pinned dependency set
with `--no-deps`. The small MuScriptor checkpoint is gated by the author. These
checksums identify the files attributed to upstream small-model revision
`8c127f603b807520fa465c838e9bfee8a91ada4e`. See the [model
card](https://huggingface.co/MuScriptor/muscriptor-small) and
[reference checksums](https://huggingface.co/AEmotionStudio/muscriptor-models/blob/main/NOTICE).

### ROCm

Use the CPU environment for Basic Pitch and for all assembly and reporting. For
ROCm-enabled inference, install the following into a second environment. This
lock targets ROCm 10.1.0 and the RX 9070 XT (gfx1201). Set
`HIP_VISIBLE_DEVICES` to your gfx1201 device index.

```bash
"$UV" venv --python 3.11.14 "$BAKEOFF_TMP/rocm-venv"
ROCM_PY="$BAKEOFF_TMP/rocm-venv/bin/python"
"$UV" pip install --python "$ROCM_PY" --no-deps -r "$EXPERIMENT/requirements-rocm.txt" \
    --default-index https://stable.repo.amd.com/rocm/whl-next/ \
    --index https://pypi.org/simple --index-strategy unsafe-best-match
export HIP_VISIBLE_DEVICES=0 RVC_CUDA_GRAPH=0
export MIOPEN_CUSTOM_CACHE_DIR="$BAKEOFF_TMP/miopen"
export TRITON_CACHE_DIR="$BAKEOFF_TMP/triton"
export TORCHINDUCTOR_CACHE_DIR="$BAKEOFF_TMP/inductor"
"$ROCM_PY" -c 'import torch; print(torch.version.hip, torch.cuda.get_device_properties(0))'
```

Use `--device rocm` with the ROCm environment for GPU inference. Move existing
per-model transcription caches out of the corpus before changing devices.
The reported run uses ROCm for both F0 trackers and CPU for Basic Pitch and
MuScriptor.

## Build the corpora

```bash
mvn -q verify
CLI="$PWD/headless/target/opensmpsdeck-headless-0.1.0-SNAPSHOT.jar"
CATALOGUE="$DATA/bench/catalogue-current.json"
BAKEOFF="$DATA/bench/bakeoff"
CORPORA=("$BAKEOFF/no-drift" "$BAKEOFF/drift-0.07")
java -jar "$CLI" bench build-corpus "$ROM" "$CATALOGUE" "${CORPORA[0]}" \
    --profile suno-like --stems
java -jar "$CLI" bench build-corpus "$ROM" "$CATALOGUE" "${CORPORA[1]}" \
    --profile suno-like --stems --drift 0.07
```

Each corpus uses the benchmark's 12 default songs, 30-second windows, and dry,
room and lofi variants. The lead stem is the one native lane with the most
note duration classified as melody by the benchmark melody heuristic. The bass
stem is the remaining tonal lane with the lowest duration-weighted register.
Backing contains the other tonal lanes. Drums contain DAC and the PSG noise
output. The builder uses the same native channel masks as `render_clip` and
the same initial padding, degradation and WSOLA window positions as the mix. The masks
separate PSG tone 3 from noise. Each song's `stems.json` records its grouping.

The original corpus manifest and ungrouped MIDI stay available for regression
checks. The four-track controls reuse the native event order and the existing
seeded Suno-like deletion, doubling and jitter sequence. The exporter retains
lane identity until it assigns each event to its stem track. Each experimental
manifest replaces only the MIDI path and digest. All sources use the same
channel-10 drum track from the grouped Suno-like MIDI.

## Transcribe and score

```bash
"$PY" "$EXPERIMENT/transcribe.py" basic-pitch "${CORPORA[@]}"
TRACKER_PY="$PY"
TRACKER_FLAGS=()
if [[ -n "${ROCM_PY:-}" ]]; then
    TRACKER_PY="$ROCM_PY"
    TRACKER_FLAGS=(--device rocm)
fi
"$TRACKER_PY" "$EXPERIMENT/transcribe.py" rmvpe "${CORPORA[@]}" "${TRACKER_FLAGS[@]}" \
    --threads 2 --weights "$BAKEOFF_TMP/rmvpe.pt"
"$TRACKER_PY" "$EXPERIMENT/transcribe.py" torchcrepe "${CORPORA[@]}" "${TRACKER_FLAGS[@]}" \
    --threads 2

# Try lead, bass and backing WAVs individually to estimate the MuScriptor CPU budget.
for stem in lead bass backing; do
    "$PY" "$EXPERIMENT/transcribe.py" muscriptor "${CORPORA[@]}" \
        --weights "$BAKEOFF_TMP/muscriptor/model.safetensors" --pilot-role "$stem" \
        --pilot "${CORPORA[0]}/01/$stem-dry.wav"
done

# Run independent case shards with a two-hour wall-clock limit.
pids=()
for shard in 0 1 2 3; do
    timeout 7200 "$PY" "$EXPERIMENT/transcribe.py" muscriptor "${CORPORA[@]}" \
        --threads 2 --shard "$shard" 4 --weights "$BAKEOFF_TMP/muscriptor/model.safetensors" \
        > "$BAKEOFF/muscriptor-$shard.log" 2>&1 &
    pids+=("$!")
done
for pid in "${pids[@]}"; do wait "$pid" || exit 1; done

"$PY" "$EXPERIMENT/assemble.py" "${CORPORA[@]}"
for corpus in "${CORPORA[@]}"; do
    "$PY" "$EXPERIMENT/evaluate.py" "$CLI" "$CATALOGUE" "$corpus" \
        suno-like clean basic-pitch muscriptor f0-lead hybrid
done
"$PY" "$EXPERIMENT/report.py" "${CORPORA[@]}"
"$PY" -m unittest discover -s "$EXPERIMENT" -v
```

If the MuScriptor pilot projects over budget or weights are unavailable, use
`--without-muscriptor` on assembly and reporting and omit that source from
evaluation. Evaluate the baseline before other sources so the wrapper can run
`bench compare --alternate-midi` with the original scorecard and both manifests.
Regression status 1 in that paired comparison indicates a metric regression.
The comparison checks both manifest digests and requires all fields other
than MIDI to agree.

Basic Pitch uses its default onset and frame thresholds and a 50 ms minimum
note. MuScriptor small uses greedy decoding with its default chunk ties and
conditions on synth lead, electric bass, or synth lead and pad. Both transcribe
each tonal stem independently. Pitch wheels are ignored by the existing
MIDI reader, so the comparison encodes discrete notes. The F0 trackers run at
100 frames per second. RMVPE uses salience 0.05 and torchcrepe tiny uses
periodicity 0.20 and a −60 dB silence gate. The segmenter requires two voiced
frames to start a gate or sustain a jump over half a semitone, keeps vibrato
within a semitone bin together, labels the span by its median pitch and ends
at the first unvoiced frame. F0 only replaces the lead track.

Tracker selection uses mean lead onset F1 across both corpora. The donor for
F0's remaining tonal tracks is selected by mean raw bass and backing onset F1.
The comparisons are on the same bench used for selection.

Raw note scoring uses one-to-one mir_eval matching, onset within 50 ms, pitch
within 50 cents and no offset constraint. The additional lead offset column
also requires a release within 50 ms or 20% of the truth gate, whichever is
larger. Drifted inputs are matched to truth on the exact input clock. Drum F1
uses the native classifier's clean MIDI as reference.

Native `bench run` measures melody F1, note F1 and rendered onset correlation.
For lead attacks, the wrapper captures the emitted arrangement, score and
report in the benchmark work directory before cleanup. A missed capture or
a resumed scorecard can repeat the same reconstruction invocation. It takes
FM1 from the arrangement and maps the project ticks through the detected
tempo curve back to source time. It matches attacks
within 50 ms by maximum cardinality then minimum time error and measures pitch
accuracy on those matches. The existing `bench lead-gap --gold` measures missing
FM1 coverage in seconds per evaluation minute against the source-time lead
gates. Inference timing includes per-WAV decode, prediction and note
postprocessing, with model setup and reconstruction outside the timed interval.

## Licences

- [Basic Pitch](https://github.com/spotify/basic-pitch/blob/main/LICENSE) is Apache-2.0.
- [MuScriptor code](https://github.com/muscriptor/muscriptor/blob/main/LICENSE) is MIT.
  [MuScriptor weights](https://huggingface.co/MuScriptor/muscriptor-small) are CC BY-NC 4.0.
- [RMVPE's original implementation](https://github.com/Dream-High/RMVPE/blob/main/LICENSE)
  is Apache-2.0. The benchmark uses the [MIT RVC implementation](https://github.com/RVC-Project/Retrieval-based-Voice-Conversion-WebUI/blob/main/LICENSE)
  and the [RVC checkpoint repository](https://huggingface.co/lj1995/VoiceConversionWebUI), labelled MIT.
- [torchcrepe](https://github.com/maxrmorrison/torchcrepe/blob/master/LICENSE) and its bundled CREPE checkpoint are distributed under MIT.
