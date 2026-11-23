# Reconstruction benchmark

The benchmark measures the current headless `reconstruct` command against
native cartridge playback and optional private hand annotations. Keep the corpus
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

The quick baseline now uses the S3K layering preset. Keep the original
dry-output scorecard when comparing across this default change. The scorer
counts all rendered note gates, including echoes and octave copies, and
can choose a PSG octave as the highest sustained melody. Use the refreshed
layered scorecard for subsequent zero-tolerance comparisons on the same
corpus and catalogue.

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

Build an accelerando fixture with `--profile suno-like --drift 0.07`. This
raises the input tempo linearly by 7% across the song. It transforms both
MIDI note endpoints and pitch-preservingly stretches each audio variant.
Truth events and reference audio retain the original timeline. Score both
modes against the same truth.

```sh
java -jar "$jar" bench build-corpus "$rom" "$catalogue" "$bench/drift" --profile suno-like --drift 0.07
java -jar "$jar" bench run "$bench/drift/manifest.json" "$catalogue" "$bench/drift-auto"
java -jar "$jar" bench run "$bench/drift/manifest.json" "$catalogue" "$bench/drift-off" --steady-tempo off
```

Read `onsetCorrelation`, `noteF1` and `melodyF1` from the three scorecards,
including the separate undrifted `suno-like` run. Audio scoring uses the
undrifted comparison interval, with silence padding if playback ends early.
`tempo-warp.json` records the warp decision per successfully reconstructed
case.

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

### Per-target quick gates

`bench run --target s1|s2|s3k` selects the reconstruction destination.
It uses the destination's catalogue variants, default layering preset,
stock drum kit where applicable, and pinned playback driver. The default
is `s3k`. The source ROM is selected during corpus creation by `--game`;
the manifest's dry native render and note truth stay fixed across targets.

For classic targets, the duration clock accounts for tempo holds when
placing events. The scorer observes the sequencer's actual 60 Hz
presentations and converts arrangement-tick annotations to that clock.
It uses the saved reconstruction tempo map and any corpus drift map to
align output events and audio to truth seconds. Classic ladder runs also
report exact and shortlist FM voice scores in editable operator order
when holdout mode is off, alongside the spectral scores.

Build the classic quick corpora from user-supplied REV01 ROMs. Set `bench`
to a private output directory, and `s1_rom` and `s2_rom` to the ROM paths.
`rom set` registers the path in RomLibrary for the stock DAC kit.
`catalog scan` builds the matching game's FM, PSG and DAC catalogue.
A classic run validates its target ROM and checks that the catalogue
contains variants for the requested target before starting cases.

| Gate | Native music IDs | Conditions |
| --- | --- | --- |
| S1 | `0x81,0x83,0x84,0x86` | 60 seconds each, R0/R1/R2/R4/R5 |
| S2 | `0x81,0x82,0x83,0x8c` | 60 seconds each, R0/R1/R2/R4/R5 |
| S3K | `0x01,0x04,0x0b,0x15` | Existing quick corpus, three MIDI × three audio conditions per song |

Classic gates use the four-song ladder presets and the five rungs available
without external transcription. Build them with an OpenSMPSDeck configuration
without MuScriptor, so the builder omits R3. `--quick` filters by the source
manifest's song selection and retains its available rungs. This lets you use
the same cases for cross-target diagnostics.

```sh
for game in s1 s2; do
  case "$game" in s1) rom="$s1_rom";; s2) rom="$s2_rom";; esac
  java -jar "$jar" rom set "$game" "$rom"
  java -jar "$jar" catalog scan "$bench/catalogue-$game.json" --game "$game" --rom "$rom"
  java -jar "$jar" bench build-corpus "$rom" "$bench/catalogue-$game.json" "$bench/corpus-$game" \
    --game "$game" --ladder --quick --seconds 60
  java -jar "$jar" bench run "$bench/corpus-$game/manifest.json" "$bench/catalogue-$game.json" \
    "$bench/baseline-$game" --target "$game" --quick
done
```

For pipeline changes, build clean base and candidate JARs in separate
checkouts and copy them outside `target/` before any further clean builds.
Run both builds against the same S3K quick manifest and catalogue. Require
`bench compare --tolerance 0` and byte-identical S3K scorecards when default
behavior is intended to stay identical. Run both classic quick gates
against their fixed manifests, ROMs and catalogues. Compare each candidate
to its own target's baseline using `bench compare --tolerance 0` for
no headline regression, and review case failures and measurement counts.
Keep the corpora, catalogues, scorecards and listening outputs private.

### Live-edit latency and parity

Run once per built target and edit journal. Use a complete journal as the
variant, including any accepted prefix you want to retain.

```sh
java -jar "$jar" bench live-edit "$work" "$journal" --target s2 --at 12 --samples 6
```

Omit `--target` to use the saved target. `--at` defaults to the song start.
The benchmark reports nearest-rank p95 for preparing provisional PCM and
settling at that presentation on a warmed checkpoint grid. It counts
unavailable previews and different provisional banks. It opens no audio line.
The command exits unsuccessfully if the canonical live bank or full-song PCM
differs from a full rebuild of the same journal.

### Measure a ground-truth role plan

```sh
java -jar "$jar" bench run "$manifest" "$catalogue" "$bench/oracle" --role-plan oracle
java -jar "$jar" bench run "$manifest" "$catalogue" "$bench/oracle-quick" --quick --role-plan oracle
java -jar "$jar" bench run "$bench/corpus-sunolike/manifest.json" "$catalogue" "$bench/oracle-suno-like" --role-plan oracle
java -jar "$jar" bench run "$manifest" "$catalogue" "$bench/oracle-section" --role-plan oracle-section
```

For each native case, the oracle runs transcription and structure, then
writes a plan and resumes from roles. It takes melody from the same native
timeline selection used in Melody F1. Within each structure section, the
oracle takes bass from the lowest duration-weighted native lane among notes
outside the truth melody. It pairs truth attacks with input attacks by pitch
and onset, preferring a dedicated input track when its attacks match a
native lane closely. It writes section rules, using pitch bands for shared
tracks and time bounds where the truth melody changes inside a section.
It checks the melody and bass plan, the melody-only plan, and the heuristic
on the allocated note timeline before choosing which plan to voice and
render. If neither plan improves that fit, the generated plan has no
overrides. The scorecard still measures rendered playback. Gold cases use
the heuristic. Compare Melody F1 with a default run on the same manifest
and case selection.

`oracle-section` uses the same truth-to-input pairing and writes at most one
bass and one melody rule per section and part. It uses a texture filter when
all the mapped attacks for the role have the same texture and the filter
removes other attacks. It can apply one register bound, a minimum for melody
or a maximum for bass, when it removes other attacks without losing mapped
attacks. It traces the top or bottom line if other eligible attacks remain.
It uses no sub-section time windows and runs the resulting plan once, with
no candidate comparison on the allocated or rendered timeline.

## Read the scorecard

Overall and slice values are case-level macro means. Profiles and WAV variants
are grouped independently. `measured` reports the denominator for each mean.
Per-case counts expose differences in coverage and make the aggregate
interpretable when a metric is undefined. Empty truth and estimate give F1
of 1, with exactly one empty side giving 0.

Note-based scores use the compiled attacks and gates outside copy windows
in `report.json`. `lanePolicyCopies` identifies generated copies by lane
and project ticks, and applied `noteLayers` entries identify authored
copies. Original notes on shared lanes and notes routed by the pads
policy still count. The scorecard also records the previous scoring
view under `*WithLayers` fields, such as `melodyF1WithLayers`. Both views
retain the existing `effectCopyLanes` exclusion for dedicated FM effect
lanes. Absent copy provenance leaves the corresponding timeline intact.
The regression comparison uses the unsuffixed metrics. Log-mel distance,
onset correlation, and release energy use the full rendered audio.
Release energy also retains the earlier pairing and cutoff timeline with
lane-policy and per-note layers included.

| Metric | Measurement |
|---|---|
| Note precision, recall, F1 | Maximum-cardinality one-to-one association at exact MIDI pitch with onset error ≤50 ms. |
| Offset F1 | Also requires end error ≤ the greater of 50 ms and 20% of truth duration. |
| Melody F1 | Select one lane per four beats on the corpus MIDI's 120 BPM grid (two-second proxy bars). Prefer notes sustaining at least 100 ms in the bar, then choose the highest duration-weighted mean pitch. If none sustain, use all notes. Apply the same rule to the output, counting attacks in the selected lanes. |
| Lead fidelity F1 | Compare the declared native lead with compiled FM1 on the native clock. Use one-to-one pitch and onset matching within 50 ms, with the supported lead-copy substitutions below. Measured on ladder cases with a declared lead. |
| Drum F1 | Maximum one-to-one matches of DAC triggers and PSG noise attacks within 30 ms, with any pairing of classes. |
| Drum class accuracy | Maximum one-to-one matches within 30 ms restricted to the same class, divided by the timing-only match count. Undefined when there are no timing matches. Simultaneous kick and hat hits can each get credit for their own class. |
| Voice top-1 and top-3 | For matched truth FM notes, compare actual emitted patch content and the first three entries in the reported shortlist by hash. Match report choices to output by source pitch, nearest quantized onset within one presentation, and emitted patch, with part ID breaking ties. The scorecard also gives counts by truth FM channel. |
| Holdout voice spectral distance | Compare the truth and emitted patch using `AudioFingerprint` spectral distance on raw same-pitch 200 ms FM probes. Coverage is the fraction of matched truth FM notes for which both patches could be probed. |
| Log-mel distance | Mean absolute log-mel magnitude difference between mono dry truth and output, over 40 bands, 512/1024/2048-sample Hann windows and 10 ms hops at 24 kHz. |
| Onset correlation | Pearson correlation of the positive differences of successive 10 ms RMS windows. |
| Release energy | Mean-square energy projected onto each matched note's fundamental in the 100 ms following truth note-off, truncated at the next reconstructed attack of the same pitch. This mix-based estimate can include energy from other simultaneous sources. |

At R0, the allocator routes the declared lead to FM1 and can split other
native parts across several output lanes. Melody F1 chooses a predominant
physical lane independently on each side every two seconds. The winners can
therefore carry different source parts after allocation, including doubled
lines already present in the source. The score can drop even when FM1 preserves
the declared lead. Lead fidelity F1 uses the fixed declaration in the ladder
manifest for the entire song and measures preservation of that route.

The declared lead provides one target per attack. After direct matches, an
unmatched FM1 attack can substitute for a remaining target when it matches a
supported copy in native truth at that copy's pitch and onset. A copy pattern
on another native lane needs at least five matched attacks and 80% coverage
in each direction within a two-second window. The pattern allows unison or
one octave of displacement and up to 20 frames of lag in either direction,
with a one-frame tolerance. Delayed or octave-shifted patterns also need
three distinct matched pitches. Copies add no lead targets, and each target
and FM1 attack can match only once. Unrelated notes incur the ordinary F1
penalty.

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

DAC classes use catalogue labels and known native kick and snare/clap slots.
A gain-fitted DAC hit retains the original catalogue class when applying the
saved gain to the catalogue PCM reproduces the emitted bytes exactly.
Compiled PSG noise attacks use the GM drum pitch of the source placement on
PSG3 at that playback tick, distinguishing closed and pedal hats, open hats,
crash-family cymbals, rides and other percussion. Native S3K noise has no GM
instrument label. Its short envelopes (slots 1 and 2) serve as closed hats,
and its longer envelopes (slots 4 and 8) serve as open hats. Other native
noise and compiled noise without a source placement use an unclassified
noise bucket. The corpus MIDI projection stays fixed, so a native long
accent can still be supplied to reconstruction as a closed hat.

Scorecard version 2 reports the original time-only pairing diagnostic with
its original drum classes under `drumClassAccuracyLegacy`. `compare` uses
the legacy metric when either card is version 1, and the fixed metric when
both cards are version 2.

Higher is better except for log-mel distance, holdout voice spectral distance,
and release energy. `compare` returns 1 if an overall headline or gold metric
regresses by **more than** the default 0.01 absolute tolerance, disappears,
or if a card has failed cases. Use `--tolerance N` to set a different absolute
threshold. It requires the same manifest, case selection, gold inputs and
holdout mode. Scorecards record `target` for S1 and S2. An absent key
means `s3k`, keeping existing S3K scorecards byte-compatible. Comparisons
require equal targets unless you supply `--cross-target` for diagnostics
on otherwise comparable cards. Lead fidelity F1 is additive when comparing with older
scorecards. Its delta uses the same tolerance when both cards report it.
A changed catalogue or refinement setting remains visible on the cards and
can be compared. Inspect the profile and variant slices for regressions
hidden by the overall mean.

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


### Lead gaps

`leadGap` is seconds of reference lead activity without a compiled FM1
gate, per minute evaluated. Lower is better. The metric samples at 10 ms
intervals in source time and maps the compiled gates back from project time
when steady tempo is active. Gold cases use the union of annotated lead
notes with resolved attacks and releases, clipped to the labelled regions.
The denominator is the total evaluated length of those regions.

Score a retained reconstruction with the same gold annotations or with its
aligned lead stem.

```sh
java -jar "$jar" bench lead-gap WORK_DIR --gold GOLD.json
java -jar "$jar" bench lead-gap WORK_DIR --stem LEAD.wav
```

The stem estimate counts 200 ms windows where a pitched tone between MIDI
55 and 84 has a measurable fundamental relative to the stem RMS and a
partial with strong 4–8 Hz pitch modulation. It uses the union of those
windows across the common stem and score duration. This criterion targets
modulated lead sounds and may miss straight tones or brief attacks. The
command also prints the active, missed and evaluated seconds so the
reference coverage is visible. Compare before and after using the same
reference and duration.


## Native reconstruction ladder

The ladder uses a local Sonic 1 REV01, Sonic 2 REV01, or 4 MiB locked-on
Sonic 3 & Knuckles cartridge. It renders through the pinned driver and
records native note gates, FM voice hashes and probe bytes, PSG envelope
slots, and DAC hit slots in shared truth. Sonic 2's PSG envelopes come from
the decompressed sound driver in the supplied ROM. The diagnostic clock is
NTSC at 60 presentations per second.

```sh
java -jar "$jar" bench build-corpus "$rom" "$catalogue" "$bench/ladder-s3k" \
  --game s3k --ladder --seconds 60
java -jar "$jar" bench run "$bench/ladder-s3k/manifest.json" "$catalogue" "$bench/run-s3k"
java -jar "$jar" bench build-corpus "$rom" "$catalogue" "$bench/credits-s3k" --game s3k --torture
java -jar "$jar" bench run "$bench/credits-s3k/manifest.json" "$catalogue" "$bench/run-credits-s3k"
java -jar "$jar" bench ladder-report "$bench/run-s3k" "$bench/run-credits-s3k" --out "$bench/report"
```

For a same-game classic ladder, use `--game s1` or `--game s2` at build time,
and pass the matching `--target` at run time. Use the corresponding ROM,
catalogue and output directories. Each manifest uses one cartridge revision.
`--songs` overrides the four default zone themes. Sonic 2 takes the engine
music IDs in `Sonic2Music`, including those whose music IDs differ from the
sound-test labels. Sonic 3 & Knuckles uses the combined music table IDs.
Default lists span different registers, densities, and rhythmic textures.
S3K includes Angel Island Act 1 (`0x01`) with its short FM2/FM3 alternating attacks.

| Rung | MIDI | Reference and stems |
| --- | --- | --- |
| R0 exact | Native tonal attacks and gates, split by hardware channel | Dry mix and isolated hardware outputs |
| R1 grouped | The same tonal attacks and gates in lead, bass, and backing parts, plus drums | Dry mix and grouped stems |
| R2 mix-effects | R1 | Existing room effect on mix and stems. Select lofi with `--effects lofi` |
| R3 transcribed | External notes on the melodic stems and existing Suno-like drums | R2 |
| R4 suno-like | Existing Suno-like perturbation and grouped parts on the native start clock | R2 |
| R5 suno-like+drift | R4 with 7% accelerando | R2 mix and stems warped with the same WSOLA alignment |
| R6 local | None. Audio-only reconstruction transcribes the separated stems | Dry mix and the configured separator's stems |

The lead reference is the native channel with the most time in the existing
predominant-melody heuristic. Bass is the lowest weighted register among the
remaining tonal channels. Hardware assignments are saved in `groups.json`.
Grouped MIDI reserves extra wire channels for overlapping same-pitch gates
and labels each such track as one logical part. The physical source channel
cannot be inferred from those temporary wire channels. Drums use the existing
GM projection. Truth retains native DAC identity and PSG noise gates.

The grouped lead is passed as `--lead-part` using its parsed MIDI part ID.
The exact rung passes the relevant native lead part. Standard reconstruction
stages still interpret the supplied notes, infer the arrangement, and fit
voices to the stem audio. R3 and R4 are alternative MIDI inputs to the same
audio. Their differences can be measured against R2 on paired songs.
When a local `muscriptor` entry is configured, the builder uses the same
external transcriber hook as `reconstruct`. To select an entry explicitly,
pass `--transcriber NAME --transcriber-config FILE --transcriber-device DEVICE`.
The R3 manifest hashes the generated MIDI and transcriber provenance.
If there is no configured MuScriptor, the builder prints a skip message
and omits R3 cases.

R3 transcribes oracle stems rendered from the cartridge and keeps the
simulated drums. R6 measures the shipped local route instead. It starts from
the dry mix alone. Pass `--separator NAME`, optionally with
`--separator-config FILE` and `--separator-device DEVICE`. The builder then
runs the separator once per song and hashes its stems and `separation.json`.
`bench run` passes the labelled stems to `reconstruct run --audio-only`, so
the configured transcribers, vocal lead, drum detector and beat grid run at
scoring time. The run reads them from the usual `transcribers.json` or
`OPENSMPSDECK_TRANSCRIBERS`. R6 has no lead part. `ladder-report` compares
R6 with R0. Without `--separator`, the builder omits R6.

Add `--keep-work` to `bench run` to keep each ladder case's reconstruction
work directory under `cases/GAME/SONG/RUNG/work` for diagnosis. It contains
the score, allocation, voicing and report files.

The ladder retains the 30-second default and accepts `--seconds N`.
`--full-length` follows playback to completion or a detected loop, with a
120-second default cap that `--seconds` can override. `--loops 2` includes
an additional pass. The loop detector checks per-track control flow,
including return addresses and counted loops, and stops after all active
tracks complete the requested passes. A case that reaches the limit records
the duration cap explicitly. The native clock is independent of the structure
and tempo inferred by reconstruction.

### Credits stress cases

`--torture` builds R0 and R1 to the native end or first loop, using the
existing 36,000-presentation guard only if neither is found. Add
`--ladder` to run all rungs on the credits. The cases use these entries
from the pinned OpenGGF music tables.

| Game | Table entry | Bench ID |
| --- | --- | --- |
| Sonic 1 | `Sonic1Music.CREDITS` | `0x91` |
| Sonic 2 | `Sonic2Music.CREDITS`, native request `0x9E` | `0xBD` |
| Sonic 3 | `Sonic3kMusic.CREDITS_S3`, same locked-on song as the standalone-table alias `0x126` | `0x26` |
| Sonic & Knuckles | `Sonic3kMusic.CREDITS_SK` | `0x33` |

The torture table records wall time per case before MP3 export, playback
duration, last completed reconstruction stage, saved project size, inferred
structure counts, exact failures, and per-section scores for completed
renders. Section boundaries come from each run's inferred structure. The
usual project byte, bank, frame and group-depth limits apply.

### Ladder reporting clocks and attribution

`ladder-report` combines any set of ladder run directories. It writes
`ladder-report.md`, `ladder-report.json`, and `listening.html` under
`--out` or the first run directory. It groups metrics by rung and game and
calculates single-step changes over songs with both measurements. The
listening page embeds original and R0, R3, R4 and R6 MP3s, with credits R0 and R1
where a render exists. The page works from one private file. `ffmpeg` with
`libmp3lame` must be on the path to encode listening assets during `bench run`.

Note metrics use the native timeline, inverting the fitted project time
map and the known R5 input warp. `onsetCorrelation` compares the actual
playback audio to the dry mix with the same interval length. `leadAttackF1`
pairs native lead and FM1 attacks within 50 ms. `leadPitchAccuracy` counts
pitch matches among those pairs. `leadGap` is missed FM1 gate activity per
evaluation minute using the existing source-clock measurement. FM voice
distance compares clean, same-pitch probes of the captured original and
emitted voices. Coverage gives the fraction of matched original FM notes
with a comparable emitted voice. The score measures patch fit independent
of changes in note duration, pan, and playback effects.

The attribution waterfall pairs truth attacks by pitch and onset, requiring
the original MIDI part in R0, then follows prepared IDs through structure,
roles, allocation, and native playback. It counts the first missing stage
and records existing reason codes and examples. The separate lead waterfall
also counts a lead routed to a different role or lane. Metrics use
independent output pairing, so their recall and the lineage counts may
differ when attacks are restored or a neighboring part survives.
