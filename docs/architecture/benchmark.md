# Reconstruction benchmark

The benchmark measures the current headless `reconstruct` command against
native S3&K playback and optional private hand annotations. Keep the corpus
fixed when changing reconstruction so changes in scores reflect the pipeline.
The harness calls reconstruction in-process and scores the compiled output
through the pinned OpenGGF sequencer.

## Build and run

Build the reactor with `mvn -B verify`. Set these variables to your own inputs
and an output directory outside the checkout. The corpus, scorecards, and
temporary artifacts may contain private or cartridge-derived data.

```sh
jar=headless/target/opensmpsdeck-headless-0.1.0-SNAPSHOT.jar
java -jar "$jar" bench build-corpus "$rom" "$catalogue" "$bench/corpus"
manifest="$bench/corpus/manifest.json"
java -jar "$jar" bench run "$manifest" "$catalogue" "$bench/quick" --quick
java -jar "$jar" bench run "$manifest" "$catalogue" "$bench/full"
java -jar "$jar" bench run "$manifest" "$catalogue" "$bench/holdout" --holdout
java -jar "$jar" bench run "$manifest" "$catalogue" "$bench/refined" --audio-refine
java -jar "$jar" bench compare "$bench/full/scorecard.json" "$bench/refined/scorecard.json"
```

The default window is 30 seconds at 60 presentations/s. Set `--seconds N`
when building to change it. The native bank supplies the sound and the
sequencer's outer-frame timeline supplies notes, the FM patch SHA-256 or PSG
envelope slot at each onset, and DAC trigger times and sample SHA-256 hashes.
PSG3 noise is recorded separately in truth. The Extra Life cue requires prior
playback context and has no standalone reference here.

The 12 defaults cover syncopation and layered FM (Angel Island 1 and Hydrocity
2), longer melodic figures (Marble Garden 1 and Sky Sanctuary), chromatic and
percussive writing (Carnival Night 1), driving lines (Flying Battery 1 and
Doomsday), slower sustained material (IceCap 1 and Lava Reef 2), and contrasting
bass, percussion, and lead textures (Mushroom Hill 1, Sandopolis 1, and Endless
Mine). The four-song quick preset uses `0x01,0x04,0x0b,0x15` and all nine
conditions per song. For a custom corpus pass `--songs 0x01,0x15` to
`build-corpus`. IDs accept decimal and hexadecimal. `run --quick` filters
the manifest to the preset IDs.

Each song has three MIDI representations and three reference conditions.

| MIDI | Input |
|---|---|
| Clean | One track per hardware lane, with noise on PSG3 and percussion on GM channel 10. |
| Merged | All melodic FM notes on one track, melodic PSG on another, and DAC and PSG noise on a GM percussion track. Repeated pitches may overlap on a merged channel. |
| Noisy | Merged, with a fixed seed per song. Drop 5% of melodic notes, shift each remaining melodic note by up to ±15 ms, and add octave-up copies to 5% of the original count where the range permits. Percussion is unchanged. |

| Audio | Matcher input |
|---|---|
| Dry | Byte-identical to the pinned render. |
| Room | Four feedback delays per side, nominal 0.8 s RT60 and 25% wet mix. |
| Lo-fi | 8 kHz low-pass and light 2:1 peak compression above one-quarter scale. |

Build the shifted profile separately to keep existing manifests comparable.

```sh
java -jar "$jar" bench build-corpus "$rom" "$catalogue" "$bench/corpus-sunolike" --profile suno-like
java -jar "$jar" bench run "$bench/corpus-sunolike/manifest.json" "$catalogue" "$bench/suno-like"
```

`suno-like` uses the same attacks and perturbations as noisy, writes one
explicit 120 BPM event, and adds a deterministic, nonzero random delay of
less than one native bar. The delay is in whole 60 Hz presentations.
Reference WAVs and truth receive the same delay, with a silent prefix in
the WAV. The end is clipped to the original window. Existing note, melody,
voice, drum and audio metrics use their unchanged definitions.

The manifest gives each case ID and SHA-256 hashes for its truth, MIDI,
matcher input and dry reference. The runner checks these hashes. Every
condition is scored against the **dry** native render. `--holdout` drops a
content asset that the song uses (declarations count) unless at least two other
songs also use it, and drops recipes that reference removed assets. Assets
shared that widely, such as the common DAC drum kit, are library sounds any
real reconstruction would have, so they stay. Song-specific FM voices are
removed.

`run` writes `scorecard.json` and `scorecard.md` to a fresh output
directory. It reports case failures and returns a nonzero status if any fail.
The bounded worker pool uses up to eight workers by default, or 16 in quick
mode. Use `--jobs N`
to adjust it. JSON rows and aggregate order are stable across worker counts.
Runtime and scratch paths are omitted from successful scorecards.

## Read the scorecard

Overall and slice values are case-level macro means. Profiles and WAV variants
are grouped independently. `measured` reports the denominator for each mean.
Per-case counts expose differences in coverage and make the aggregate
interpretable when a metric is undefined. Empty truth and estimate give F1
of 1, with exactly one empty side giving 0.

| Metric | Measurement |
|---|---|
| Note precision, recall, F1 | Maximum-cardinality one-to-one association at exact MIDI pitch with onset error ≤50 ms. |
| Offset F1 | Also requires end error ≤ the greater of 50 ms and 20% of truth duration. |
| Melody F1 | Select one lane per four beats on the corpus MIDI's 120 BPM grid (two-second proxy bars). Prefer notes sustaining at least 100 ms in the bar, then choose the highest duration-weighted mean pitch. If none sustain, use all notes. Apply the same rule to the output, counting attacks in the selected lanes. |
| Drum F1 and class accuracy | Associate DAC triggers and PSG noise attacks by onset within 30 ms. Class accuracy is over matches. Catalogue labels take precedence, with native sample slots mapping recognized kick and snare/clap presets, PSG noise mapping to hat, and unclassified presets to other. |
| Voice top-1 and top-3 | For matched truth FM notes, compare actual emitted patch content and the first three entries in the reported shortlist by hash. Match report choices to output by source pitch, nearest quantized onset within one presentation, and emitted patch, with part ID breaking ties. The scorecard also gives counts by truth FM channel. |
| Holdout voice spectral distance | Compare the truth and emitted patch using `AudioFingerprint` spectral distance on raw same-pitch 200 ms FM probes. Coverage is the fraction of matched truth FM notes for which both patches could be probed. |
| Log-mel distance | Mean absolute log-mel magnitude difference between mono dry truth and output, over 40 bands, 512/1024/2048-sample Hann windows and 10 ms hops at 24 kHz. |
| Onset correlation | Pearson correlation of the positive differences of successive 10 ms RMS windows. |
| Release energy | Mean-square energy projected onto each matched note's fundamental in the 100 ms following truth note-off, truncated at the next reconstructed attack of the same pitch. This mix-based estimate can include energy from other simultaneous sources. |

### Musical grid and repeats

These measurements use the nominal native clock in the shifted manifest.
A quarter note has the native project's declared ticks per beat multiplied
by the header divider. The initial S3K tempo byte holds duration counters
on a fraction `tempo / 256` of presentations. That gives a nominal
quarter-note BPM of `3600 × (1 − tempo / 256) / ticksPerQuarter`. Truth
bars use four quarter notes from the known shift of the native time
origin. This nominal clock assumes constant tempo and a downbeat at
native time zero. It does not infer mid-song meter or tempo changes.

| Metric | Measurement |
|---|---|
| Tempo error (%) | Absolute BPM deviation divided by truth BPM. The default PPQ grid measures as 120 BPM. |
| Bar-phase error (ms) | Circular distance from the estimated downbeat to the known shifted origin, modulo the truth bar length. The default PPQ grid measures as offset zero. |
| Repeat precision and recall | Pairwise precision and recall for equally long, nonoverlapping 1-, 2-, and 4-bar windows. Truth is exact equality of the nonempty melodic attack lists across all FM and tonal PSG lanes, keyed by lane, pitch, and relative attack and release rounded to nominal native ticks. Timbre, DAC hits and PSG noise are excluded. An attack belongs to the window containing its rounded onset. A release can extend beyond the window. |

A prediction comes from each pair of instances in a phrase group or an
exact bar repeat group. Duplicate pairs count once. Both span boundaries
must align within one eighth of a native bar, or the prediction is a
false positive. Repeat precision is undefined without a prediction.
Repeat recall is undefined without a truth pair. The scorecard reports
how many cases contribute to each mean. The existing regression
comparison continues to gate the original headline metrics.

Higher is better except for log-mel distance, holdout voice spectral distance,
and release energy. `compare` returns 1 if an overall headline or gold metric
regresses by **more than** the default 0.01 absolute tolerance, disappears,
or if a card has failed cases. Use `--tolerance N` to set a different absolute
threshold. It requires the same manifest, case selection, gold inputs and
holdout mode. A changed catalogue or refinement setting remains visible on
the cards and can be compared. Inspect the profile and variant slices for
regressions hidden by the overall mean.

## Private gold cases

Provide a private descriptor with paths relative to the descriptor or absolute
paths. Do not check in the descriptor or its inputs.

```json
{
  "id": "annotated-example",
  "reference": "mix.wav",
  "midi": "input.mid",
  "annotations": "labels.json",
  "leadPart": 0,
  "stems": { "0": "lead.wav" }
}
```

Use `bench run … --gold GOLD.json` to add a gold case, or include
`--gold-only` to skip corpus reconstructions. The gold case uses the supplied
catalogue because it has no ROM song identity. Optional `stems` are keyed by
reconstruction MIDI part ID. With `leadPart`, attribute output attacks to that
part using source pitch, quantized onset, and patch evidence. With no
`leadPart` use the same highest-sustained-lane rule as for corpus cases.
Optional `seconds` limits reconstruction and must cover every region.

The labels file has a `regions` array. Each region includes `id`, `start_s`,
`end_s` and `notes`. Only notes marked `voice: "lead"` with resolved
`attack_s` in the region are scored. `midi` is the annotated pitch and
`release_s` may be null. Lead attack F1 uses one-to-one onset matching within
50 ms, with precision penalized for unmatched predictions in the labelled
regions. Pitch accuracy is measured over matched attacks with a resolved
pitch. Gold metrics are reported separately from native-corpus means.
