---
name: opensmpsdeck
description: Use when reconstructing Mega Drive music from a local mix, a Suno download, or aligned MIDI and audio, inspecting SMPS note decisions, proposing arrangement edits, or preparing audio and S3K exports for review.
---

# Reconstruct and review an S3K arrangement

Use the packaged headless JAR or the local MCP server. Build and connect
them with the [external-agent setup guide](../../docs/user-guide/agents.md).
Run the examples from your private workspace. Set `jar` to the absolute
path of the packaged JAR and `work` to the reconstruction work directory. Keep downloads, ROMs, catalogues derived
from ROMs, work artifacts and renders outside public checkouts.

Choose the target game before analysis. Use `--target s1|s2|s3k` on
`reconstruct import` or a fresh `reconstruct run`. In MCP, use `target` on
`reconstruct_import` or `options.target` on `reconstruct_run`. The default is
`s3k`. Replays retain the saved target. Use a catalogue with variants from
the selected game. Build one from a verified ROM with
`java -jar "$jar" catalog scan "$catalogue" --game s1 --rom "$rom"`
(substitute `s2` or `s3k` for the other games). Omit `--rom` to use the
path set by `rom set`. Use `catalog search --target` to browse the game's
variants. `catalog audition` uses the variant's driver. MCP
`catalog_search` and `catalog_audition` take an optional `target` field.
If the S3K layering preset adds an incompatible envelope on a classic
target, use `--lane-policy-preset off` on the fresh reconstruction.
Portable and mod exports default to the saved project target.

Check live audition latency and full-rebuild parity with
`java -jar "$jar" bench live-edit "$work" "$journal" --target s2 --at 12`.
Use a complete edit journal and the saved reconstruction target. This probe
renders PCM in memory and opens no audio line.

## Work on a shared reconstruction branch

Create a branch with `reconstruct branch create "$work" agent-pass --author "AGENT MODEL"`.
Write intents with `reconstruct edit "$work" --branch agent-pass add --file BATCH.json`.
Build with `reconstruct run "$work" --branch agent-pass` and inspect with
`reconstruct branch diff "$work" agent-pass --json`. Add `--branch agent-pass`
to describe, trace, assemble and render. The result stays isolated under
the shared work directory. Keep build, diff and render outputs private.

Rebase after main's journal changes. To combine work, use
`reconstruct branch merge "$work" agent-pass human-pass --into review --dry-run`.
Review direct and downstream conflicts, then select complete intents with
`--parts`, `--span` (source seconds), `--groups` or `--edits`.
Use `branch import "$work" agent-pass COPY` for a journal already edited in a copy.
In MCP use `reconstruct_branch` with an `action` and `workDir`, and pass
`branch` to `reconstruct_run`, `reconstruct_describe` and `reconstruct_trace`.
[Branch CLI and storage](../../docs/architecture/edit-branches.md)

## Notes pasted from Studio

Read `opensmpsdeck-notes v1` as line-oriented text with JSON objects on
`project:`, `context:`, and `note:` lines. The absolute path points to the
user's machine. Use the `fetch:` command (prefix with `java -jar "$jar"`
in place of `opensmpsdeck` when needed). For a reconstruction, append
`--json` or call `reconstruct_describe` with `workDir`, `notes` (ID list
as a comma-separated string), `context: "1bar"`, `revision`, and
`json: true`. Compare the selected notes with the following bar in
`neighbors`, including their role, allocation reason, previous lane and
competing notes. Check `stale`, `missingIds`, and the clipboard's
`draft` flag before proposing edits. For arrangement notes, the
`describe-notes` command resolves their sequence and optional clip path.
[Clipboard format and clocks](../../docs/user-guide/agents.md#investigate-notes-pasted-from-studio)


For a screenshot lasso attachment, run `reconstruct describe --from-png
IMAGE --json` or call `reconstruct_describe` with absolute `fromPng` and
`json: true`. Read `method` (metadata, lsb or registry), `lasso` (range and
view state), `reference` and the note IDs before using the live query.
Registry fallback needs the originating machine's local state and work
directory. Ask for an original file attachment or the text payload when
the image has been re-encoded and its local reference is unavailable.
[Attachment decode and registry](../../docs/user-guide/agents.md#decode-a-screenshot-lasso)

## 1. Separate and check a local mix

Start with a WAV or MP3. For FLAC, decode to WAV first
(`ffmpeg -i mix.flac -c:a pcm_f32le mix.wav`). Configure BS-RoFormer SW
and MuScriptor Medium under the names used here. Set `catalogue` to the
absolute path of your local instrument catalogue.

```sh
java -jar "$jar" reconstruct separate mix.wav separated --separator bs-roformer --device auto
java -jar "$jar" reconstruct import separated import-work \
  --catalogue "$catalogue" --transcriber muscriptor-medium
```

Use a new separator output directory. Read the ✓ pass, ⚠ warning and ✗ error
checks and `import-work/import.json`. Check the lead stem, drums and any
skipped stems. `separation.json` records the model and aligned outputs and
lets import recognize the audio-only source automatically. Override a role
with `--stem-role stems/NAME.wav=ROLE` and repeat it on subsequent checks.

The [local extraction guide](../../headless/README.md#external-stem-separation)
covers model setup and config paths. Use MuScriptor Medium for tonal backing, ADTOF for drums, and configure
[RMVPE](../../tools/transcribers/rmvpe/) under the name `rmvpe` for
syllable-aware vocal leads. The vocal gate and selected register are saved
in `manifest.json`. Use `vocalLead.choice` in the transcriber configuration
to choose Basic Pitch, MuScriptor or fusion, and `vocalLead.registerShift`
to change the default +12 semitones. A configured `bs-roformer`
separator supplies and snapshots aligned drums when the input has no mapped
drum stem. Otherwise the CLI warns and fits drums against the mix.

With MCP, call `separators_list` and `transcribers_list`, then `separate`
with absolute `input` and new `outputDir`, `separator: "bs-roformer"` and
optional `device`. Pass the separated directory as `folder` to
`reconstruct_import` with `workDir`, `catalogue` and `transcriber`. Use
file-relative keys in `stemRoles`.

### Alternative inputs

- **Suno mix, MIDI and stems.** Use `reconstruct import DOWNLOAD IMPORT_WORK
  --catalogue CATALOGUE`. Check alignment, the lead part's winning stem and
  runner-up, and any `also_correlates_with_part` warning. Each MIDI part can
  use one fitting stem. Unmapped parts can use the mix. Use repeatable
  `--assign FILE=PART` and `--exclude FILE` with download-relative paths
  (MCP `assign` map and `exclude` list). Add `--transcribe-unmapped
  muscriptor-medium` (MCP `transcribeUnmapped`) for unmatched tonal stems.
  Studio offers **Import Suno folder… → Override → Re-check → Start
  reconstruction**. [Suno import reference](../../headless/README.md#import-a-suno-download)
- **Already aligned WAV stems without MIDI.** Use `reconstruct import
  --audio-only` for a labelled stem folder or `reconstruct run --audio-only`
  with explicit stem paths. [Audio-only inputs](../../headless/README.md#audio-only-reconstruction)
- **Already aligned MIDI and WAV.** Run directly with `reconstruct run
  song.mid mix.wav CATALOGUE WORK_DIR --seconds 60 --stem 0=lead.wav`.
  For MCP, send `workDir`, `inputs` with `midi`, `wav`, `catalogue` and
  optional `stems` keyed by part ID. Use `options.seconds` to cap a fresh run.

## 2. Run and preserve a baseline

```sh
java -jar "$jar" reconstruct import separated import-work \
  --catalogue "$catalogue" --transcriber muscriptor-medium --run
```

Repeat any checked import overrides. The command builds in
`import-work/reconstruction`. For MCP, pass the returned `runRequest` to
`reconstruct_run`. For a short CLI smoke run with `import --run`, separate
a short excerpt of the mix. The stage work directory must be new. Set
`work` to that directory for the remaining examples. In Studio, choose
**Open reconstruction…** and select the stage work directory.

The stages run in this order.

| Stage | Decision |
| --- | --- |
| `transcribe` | Source notes, detected candidates and score edits |
| `structure` | Grid, repeats and phrasing evidence |
| `roles` | Melody, bass, counter, harmony, drums and drops |
| `voices` | Source instruments and function voices |
| `allocate` | Hardware lanes and chord reductions |
| `voice` | Patches, levels and effects |
| `assemble` | Project, package, render and report |

Copy the work directory before testing a change. Resume with
`reconstruct run --from STAGE WORK_DIR` or MCP `reconstruct_run` with
`from`. Add `--keep-voicing` or MCP `options.keepVoicing` to retain
compatible settings and review any stale settings removed during rebuild.
Use `reconstruct_stage` with `stage` for a single stage.

Use `reconstruct rerun WORK_DIR NEW_WORK_DIR` or MCP `reconstruct_rerun`
with `workDir` and a new `output` for manifest replay. Keep the same build
and hashed inputs for an exact comparison. Replay reuses saved options,
snapshots, edit and planning journals, lane affinity and clip anchors.
Use `--search DIR` or MCP `search` for relocated inputs, and
`--keep-voicing` or MCP `keepVoicing` when replaying accepted voicing.
Studio starts builds from Import and resumes them with **Apply** in Analysis.
While playing Chip, Studio can audition the edit provisionally while the
canonical stages replay. The checked journal is saved when the replay
succeeds. Use **Apply & rebuild** to include the edits in exports.
Use CLI or MCP for manifest replay. [Replay details](../../docs/architecture/reconstruction-stages.md#trace-and-reproducible-inputs)

## 3. Review the musical evidence

Use source seconds for reconstruction spans by default. Use `--time
project` (MCP `time: "project"`) for Studio timeline feedback or project
seconds. Edits use `timeBase` on the intent. Review notes, `render_clip`,
and audio export spans use project seconds.

```sh
java -jar "$jar" reconstruct trace "$work" --by-onset --start 12 --end 14
java -jar "$jar" reconstruct view "$work" --start 12 --end 14 --sections \
  --color-by voice --ridges --harmonics-at 'C4@12.5' --render "$work/evidence.png"
```

- **Trace** gives stable note IDs, fate, gates, blockers and both clocks.
  `--by-onset` groups nearby attacks by part. MCP `reconstruct_trace` takes
  `byOnset: true`. In Studio, click a note or lane gate to inspect its trace
  and expand **Raw trace JSON**.
- **Part repeats** are opt-in for mapped audio-only pitched stems. Add or
  propose a `repeatPolicy` op with the integer `part`, `fill` (`off`, `low`,
  `medium`, `high`), and `lanes` (`off`, `on`, `strict`) via
  `reconstruct edit` or MCP `reconstruct_edit`. For Brains, use High + On
  for bass and Medium + Off for the backing synth. Try High on the synth
  only if Medium misses a locally supported attack. Leave the other parts
  off. Review `structure.repeatConsensus` for aligned notes and local
  evidence, then compare drops and shortened gates across parts. Studio
  exposes the same controls under **Roles → Part repeats**.
- **Held notes** appear in describe and trace when a mapped stem sustains
  through a missing, shortened, silent or heavily decayed chip gate. Use
  `reconstruct proposals list WORK_DIR --group held-notes` for review
  suggestions and `part-render` to audition the assembled part.
- **View** aligns source and rendered spectrograms with note and lane gates.
  Use `--stem PART` for a mapped stem, `--harmonics NOTE_ID` to trace a
  note's partials and `--ridges` for numerical trajectories. MCP
  `reconstruct_view` returns a PNG and legend using fields such as `stem`,
  `harmonics`, `ridges`, `sections` and `colorBy`. Studio Analysis has
  a source picker, **Ridges**, **Auto** contrast, **Sharp** (reassigned)
  display, section bands, and role and lane filters.
  In **Detail** and **Roles**, scroll to zoom time at the pointer or
  **Shift**-scroll to zoom pitch. Middle-drag or hold **Space** and
  left-drag on the focused plot to pan. Tap **Space** to toggle playback
  and Shift-drag on the ruler to set a loop.
- **Voices** distinguish the source instrument and its musical functions.
  A line and chords from one part share a fitted sound by default. Use
  `reconstruct voices WORK_DIR` or `reconstruct_voices` to inspect identities
  before assigning a chip type and instrument. Assignments follow notes
  across lanes. Studio **Selection** can move notes between voices or set
  an instrument for **This voice** or **These notes only**.
- **Sections** identify phrases, periods and larger sections. Use
  `reconstruct sections WORK_DIR` or `reconstruct_sections` to inspect
  the effective hierarchy. Pass an ID or unique name to `view --section`
  or `render-compare --section`. Studio can loop, rename, resize, split
  or merge sections by interacting with their bands.
- **Stem templates** record suspected missed notes in
  `stem-template-candidates.json`. Review the strongest entries in the
  `audio-template` proposal group. Strong independent attacks can also
  become automatic harmony. Use `--no-stem-template-notes` on the initial
  run (MCP `options.noStemTemplateNotes`) to suppress automatic insertion
  while retaining the evidence and proposals.
  Proposals are capped (`shortlistedProposals`, three per 16 seconds) and
  can still be timbre changes: check pitch motion, neighbouring partials,
  attack and release with `view --harmonics-at PITCH@TIME` before accepting.
- **Drum choices** are fitted to isolated stem hits. Inspect
  `lanes.DAC.instruments[].drumSelection.evidence` in `voicing.json` for
  hit windows, candidate distances, fitted rates and kit provenance.

See the [view reference](../../headless/README.md#inspect-time-aligned-evidence)
and [stage guide](../../docs/architecture/reconstruction-stages.md) for
additional filters, voice assignments, section journals and detector details.
For an isolated S3K part, `held-note fallback` in the stock FM choice means
no candidate sustained every measured hold that survived FM allocation.
Audition the selected patch on the reported holds before accepting it.
Use [role plans](../../headless/README.md#inspect-the-score-and-plan-musical-roles)
for recurring lead and backing rules and [external transcription](../../headless/README.md#external-transcription)
when a mapped or unmatched stem needs a separate transcriber.

In note trace, `stem-upper-harmonic` means a generated upper note followed
a decoded lower note's partial in frequency, phase and envelope. A note
that reaches roles but drops in allocation has a separate fate and reason.

### Clean up a vocal-derived lead

1. Run `reconstruct vocal-review "$work" --json` or call
   `reconstruct_vocal_review` with `workDir`. Scope to a phrase with
   `--phrase ID` (MCP `phrase`). The review includes decoded syllables,
   kept FM1 gates, and per-note contour, harmonic and repeat evidence.
   Read `pitchDecisions` for automatic retunes, merges and suppressed
   attacks in the stem register before the configured shift. Check
   `pitchEvidence` for retunes supported by the stem spectrum, learned
   register and pitch set, or aligned repeats. Strong local evidence
   preserves genuine register leaps.
2. Use `reconstruct vocal-render "$work" --phrase ID OUT.wav` or MCP
   `reconstruct_vocal_render` for a built FM1 excerpt and metrics against
   the vocal stem. Check onset alignment and voiced gaps. Pitch agreement
   compares audible FM1 gates with the saved stem contour. Audition the
   returned `stem` over the corresponding source seconds.
3. Add `--propose` or MCP `propose: true` to save suggested intents under
   `vocal-lead-review`. Revise an intent with
   `reconstruct proposals revise "$work" ID --file INTENT.json` or
   **Revise** in Studio. Accept or reject an ordered set of IDs or use
   `--file`, `--group` or `--op`. Use `--show-decided` to inspect
   earlier decisions alongside the pending suggestions.
4. Run `reconstruct run --from transcribe "$work"` (MCP
   `reconstruct_run` with `from: "transcribe"`). Review the rebuilt
   phrase and audition the FM1 excerpt again.

### Arrange for cleanliness

When the source is denser than the hardware can reproduce, simplify by
part and section before resorting to note-ID edits. Save the controls
under `voicing.json.arrangement`. Automatic voicing selects exact stock FM
patches and compatible PSG envelopes. For an intentional FM release change,
use a scoped `minimumReleaseRate` edit. The arrangement exports the changed
instrument as a custom clone with its stock asset ID in `origin.clonedFrom`.
In MCP, read and write the full
`voicing` artifact with `artifact_read` and `artifact_write`.

For isolated S3K parts, the FM fit uses catalogue voice names and observed
retail register and role as bounded preferences. Rescan the S3K ROM with
`catalog scan` to include retail usage. Inspect `patchReason` in `voicing`,
`stockVoiceChoices` in `reconstruct describe "$work" --json` or MCP
`reconstruct_describe`, or **Automatic FM fit** on a selected FM note in
Studio Detail.

- Use `partChips: [{"part":3,"chip":"FM","lanes":["FM3","FM4","FM5"]}]`
  to keep backing on one chip. Use `"PSG"` for intentional PSG parts.
- Apply `polyphony: [{"part":3,"maxVoices":3,"span":[8,24]}]` to thin
  chords while retaining their extremes. Omit `span` for the full part.
- Apply `bassHarmonics: [{"part":3,"bassPart":1}]` when simultaneous
  octave, twelfth or double-octave notes are weaker than the bass.
  Compare the dropped IDs with the stem and adjust the overlap, pitch
  or level tolerances if the rule removes a real line.
- Use `hats: "all"` to reserve DAC tails for other drums, or
  `hats: "overflow"` to keep hats that would otherwise collide.
  On an isolated drum stem, `drumDynamics: {"part":0,"levels":true,
  "dropBelowDb":-15}` measures hit levels and drops hits far below the
  median of their class.
- Run `reconstruct run --from allocate "$work"` for these allocation
  edits. Read drop reasons and competing IDs in `reconstruct trace`
  and the assembly report.
- Compare `reconstruct part-render "$work" --part 3 PART.wav` with
  the mapped stem (MCP `reconstruct_part_render`). The solo includes
  every placed lane and layer of that part. Set
  `sectionFit: [{"part":3,"maxJumpDb":3}]` and rebuild from assemble
  to fit the part's section levels against active stem audio.
- For a lead deliberately placed at +24, set `vocalLead.registerShift`
  in the transcriber config or `arrangement.vocalRegisterShift` for an
  existing transposed lead. Review octave proposals in that register.
  Set `monophonicStems.guitar: {"adapter":"rmvpe","span":[8,24],
  "registerShift":12}` in the config to transcribe a guitar solo span
  via F0. Author the solo's lane and role separately.
- Shortlist patches with `catalog search INDEX.json --role lead
  --min-centroid-hz 500 --max-centroid-hz 2600 --max-decay-db 4`.
  Read the held-note measurements, then audition a few matching patches
  against the part in context.

[Headless arrangement controls](../../headless/README.md#arrange-for-cleanliness)
describes the complete fields, defaults and MCP calls.

## 4. Propose or apply a scoped edit

For dropped tonal notes, inspect `chordAudit.losses` in `describe --json`
or MCP `reconstruct_describe`. Review the role and reason before proposing
a change. A covered source unison already sounds through another note.
Studio Detail and `reconstruct trace` show the competing IDs when known.

For dropped percussion, use `reconstruct trace WORK_DIR --lane DAC --json`
or `--lane PSG3`. Review the busy intervals and competing notes in
`drums.droppedHits`. Dry-run a suggested `pinLane` to move a tonal blocker.

Use `edits.json` for musical intent. For a repeated pitch inside a held
note, use `retrigger` with `attacks: [{"offset":0,"gate":0.06}, …]` or
`rate` and `count` with an optional `gate`. Offsets, interval and gates are
in seconds. Dry-run to get the stable IDs for layer edits and rebuild from
transcribe. Never hand-edit `score.json` or
`allocation.json`. Keep each batch scoped to a musical reason and record
the evidence. Use stable note IDs or a point selector with part, pitch and
onset. For spans, check the part, inclusive pitch bounds and voice rank
before changing a register or role. A `setRole` span can select the top,
bottom or a ranked attack in each cluster. `swapRoles` moves matching
notes to the destination role and sends its previous notes to harmony. The
[edit reference](../../docs/architecture/reconstruction-stages.md#persistent-musical-edits)
lists field names, selector rules and rebuild stages.

| Goal | Operations |
| --- | --- |
| Fix or reshape attacks | `insertNote`, `deleteNote`, `setNote`, `splitNote`, `retrigger`, `mergeNotes` |
| Route a line or register | `transpose` (signed `semitones` and `noteIds`), `setRole`, `swapRoles`, `pinLane` with a lane or `laneType`, `unmerge` |
| Shape articulation and level | `bend`, `glide`, `noteVoicing` for instrument or vibrato overrides, `setVelocity` |
| Layer selected attacks | `layer` with `echo`, `chorus`, `double` or `off` |
| Fit stacked tones | `arpeggio`, `chordInstrument` |
| Change a voice in a reviewable batch | `voiceEdit` |

For staged vibrato on an allocated S3K FM note, propose a `noteVoicing`
edit with `vibratoEnvelope`. Each segment has `startOffset` in project
driver ticks from the attack, `rateHz`, and `depthCents`. An opening with
no segment is plain. A segment with zero depth turns vibrato off.
`reconstruct vibrato-review WORK_DIR --propose` (MCP
`reconstruct_vibrato_review` with `propose: true`) drafts stem-backed
candidates. Review the proposals, rebuild from assemble, and check the
stages in `trace` before auditioning.

For a note-scoped echo, choose `laneType: "FM"` or `"PSG"` and set
`delayFrames` and `levelDb` or `attenuation`. To match Studio's default
echo, use 14 frames and 11 TL below dry. Studio shows this as a −3 dB
trim from the original 7-TL echo. A layer edit overrides lane policies for
its notes and rebuilds from `assemble`. In Studio, use **Selection → Layer**,
then **Queue layer** and **Apply**. Check the dry-run and trace results for
skipped copies before review. Use `off` with `"policies":["fmDouble"]` to
keep other policies eligible. Assemble with `--policy-decisions` (MCP
`options.policyDecisions: true`) to inspect each policy's attacks, gates and
blockers in `report.json` and `trace`.

**Propose by default when a human is reviewing.** For example, save an
ordered array of intents to `batch.json` and preview the group before it
enters the built arrangement. Add `group` to each intent and optionally
attach `evidence.text` and a work-relative `evidence.path`.

```sh
java -jar "$jar" reconstruct edit propose "$work" --file batch.json
java -jar "$jar" reconstruct run "$work" --with-proposals --group phrase-1
```

MCP `reconstruct_edit` takes `propose: true` and `edit` or `edits`.
Use `reconstruct_run` with `withProposals: true` and optional
`proposalId` or `group`. The preview returns a sibling work directory and
built/proposed A/B clips. Check `proposal-results.json` for selector
outcomes. In Studio open **Proposals**, use **Preview A/B**, then **Accept**
and **Apply**, or **Reject**. After CLI/MCP acceptance, rebuild from the
returned `from` stage. Scope accept or reject to the intended ID or group.

For an authorized direct edit, use `reconstruct edit WORK_DIR add --dry-run
--file BATCH` first, then `add --file BATCH` and rebuild from the reported
`from` stage. MCP `reconstruct_edit` supports `dryRun: true` then
`action: "add"` with optional `rerun: true`. Inspect `edit-results.json`
for invalid or orphaned targets and blocked pins. In Studio, use **Insert**,
**Cut**, **Bend / slide** and **Vibrato** for note gestures. The **Selection**
tab handles bulk roles, pins, velocity, merges and chord fitting. **Undo**,
**Redo** and **Apply** operate on the draft. Resolve dirty-draft conflicts before
publishing. [Studio controls](../../docs/user-guide/studio.md#review-a-reconstruction)

**Layering on spare channels.** New reconstructions use `s3k-layering`
for an octave PSG double and a 14-frame FM echo on spare channels.
Existing voicing and reruns keep their saved policy. Set
`--lane-policy-preset off` on a new run or an assemble to disable it.
`voicing.json.lanePolicies` also accepts custom `pads`, `leadEcho`,
`psgDouble` and `fmDouble` (chorus or echo) rules scoped by `voice`,
`section` or `span`.
For a missed upward scoop or a run of apparent semitone fragments, inspect
the mapped stem with `reconstruct view` and check
`reconstruct proposals list WORK_DIR --group stem-pitch-approach`. Resume
from `transcribe` to scan old work or apply an accepted proposal. Separate
attacks can each have a scoop, and written chord voices can follow a
matching rise on FM or PSG tone. Check the stem's ridge across each attack
before merging fragments into one note. Use equal initial offsets to hold
the pickup pitch before the glide. Set `start` on a `bend` or a `mergeNotes`
with explicit glide points to move the attack to the pickup onset.
`mergeNotes` with `glide: true` interprets those points relative to the
last note’s pitch.

Per-note layers and copy policies follow rendered bends, including the
dry note's vibrato. Check the curve fit and cents RMS error in `noteLayers`
or `lanePolicies[].bendLayers` in the report.
Use `reconstruct assemble WORK_DIR --lane-policy-preset s3k-layering`, MCP
`options.lanePolicyPreset`, or Studio **Lane policies → S3K layering**.
The echo defaults to 11 carrier TL steps below dry (8.25 dB nominal),
4 TL steps or 3 dB quieter than the original 7-TL echo. To audition a
different level, set `lanePolicies.echoAttenuation` in `voicing.json` to
an integer from 0 to 127 (0.75 dB per step) and
rebuild with `reconstruct assemble WORK_DIR` using the saved preset.
Check `report.json.lanePolicies` for coverage and skips before auditioning.
[Lane policies](../../docs/architecture/reconstruction-stages.md#optional-lane-policies)

## 5. Compare and listen

```sh
java -jar "$jar" reconstruct trace-diff "$before" "$after" --span 12,14 --json
java -jar "$jar" render-compare "$after" --start 12 --end 14 "$after/comparison.wav"
```

Check `lanePermutations`, `voicingChanges`, `compiledTimeline` and
`renderedPcm` outside the edited span. A PSG tail can change phase even
when the later driver events match. Look for `renderedPcm.psgTail` when
available. MCP `reconstruct_trace_diff` takes `oldWorkDir`, `newWorkDir`
and a source-time `span`. Studio's **Trace diff** tab summarizes the rebuild.

`render-compare` and MCP `render_compare` write an A/B with the chip
excerpt followed by the aligned, level-matched reference. Warped runs use the steady reference
by default. Use `reconstruct audition` with `--variant` or `--shortlist`
to compare FM variants on a frozen phrase. MCP `reconstruct_audition`
takes explicit `variants`. For a project-time lane excerpt, use
`render_clip` with `solo` or use Studio **Solo lane**, **Solo note**,
loop and **R** to switch between chip and source. Send the human paths,
split points and the specific cue to listen for before resolving feedback.
Validate the resulting project with `validate-project` or MCP
`project_validate`.
[Audition reference](../../headless/README.md#refine-and-audition-voicing)

## 6. Export or hand off the arrangement

Use `export WORK_DIR OUTPUT --format wav|mp3|vgm|smps|package|portable|mod|disasm` or MCP
`project_export` with exactly one of `workDir` and `project`. WAV and MP3
support project-second spans, fades and channel stems (`--stems`, optionally
`--zip`). VGM exports the full chip-write stream. Raw S3K SMPS targets
a compatible driver with local voices. Use the package for projects with
external envelope or DAC assets. It preserves bindings, resources and source.
For an [SMPS2ASM kit](../../docs/architecture/disassembly-kit.md), use
`disasm` with a project, work directory or embedded-bank portable `.smps.zip`.
For s1disasm or s2disasm, first compile a portable package with `--driver s1`
or `--driver s2`. Use a `.zip` output for an archive. Stock DAC notes are
reused when the asset and retail playback rate match. `--rom PATH`
materialises stock assets for personal use. `--dpcm-closed` quantises the WAVs. Repeat `--replace-dac NN`
(hex) to repurpose unused retail slots. S1 requires `81` or `82` for
each custom DAC binding. S2 extends its playlist by default. The MCP
options are `roms`, `dpcmClosed` and `replaceDac` (integer note numbers).
Read the output warnings. In Studio, choose **Export… → Disassembly kit**
for the project's driver. Use **DPCM closed** as needed and register a clean
ROM in **ROM settings…** to materialise stock assets.

For timeline and piano-roll handoff, choose **Open as arrangement**. Saving
a musical hand edit detaches the arrangement. Export uses the detached
project. A rebuild that would replace it requires explicit
`--overwrite-detached` (MCP `options.overwriteDetached` on run/stage
or top-level `overwriteDetached` on replay). Prefer replay to a new
directory when preserving both versions. Studio **Apply** offers a warning
with options to preserve the edited arrangement before rebuilding.
[Export reference](../../headless/README.md#export-project-output)

### Test in game

Use `rom set s3k PATH` or MCP `rom_set` with `game: "s3k"` and an
absolute `path` to register a verified clean locked-on Sonic 3 & Knuckles
ROM. Check `rom list` or `rom_list` for its status.

```sh
java -jar "$jar" rom-patch song.smps.json --out song.s3k.gen --bps song.s3k.bps --no-demo
```

Use at least one output. Pass `--rom PATH` to override the saved ROM for
one export. With MCP `rom_patch`, use absolute `project`, `out` and/or
`bps` paths, with optional `rom`, `slot`, `titleMusic`, `noDemo`, `loop`,
and `force`. Read the `report` and `warnings`, including any DPCM and
envelope approximations. A patched ROM is for personal use only. Hand off
the BPS when sharing, subject to rights on any stock-derived material.
It requires the same clean ROM to apply. Studio offers **Export… → Test in
game…** once the ROM verifies. [Test in game guide](../../docs/user-guide/14-test-in-game.md)

## Lessons from agent trials

- Judge a change by attack, pitch, rhythm, timbre and balance evidence.
  Use benchmark scores to gate pipeline changes.
- Verify a suspected octave double against the stem template. Compare
  partial excess with neighbouring partials and look for independent
  attack, release or pitch motion.
- Inspect out-of-span trace diff results, including the compiled timeline,
  even when the intended edits all resolved.
- For audible phasing, check for a muted-attack PSG envelope or unison
  doubling across lanes. Solo the lanes and check the compiled gates.
- Judge balance perceptually. Use gated K-weighted LUFS for drum balance
  and treat RMS measurements as diagnostics.
- Keep one instrument per source part by default, including line and chord
  function voices.
- Check the lead stem role or MIDI assignment in `import.json` before
  correcting the transcription.
- Agents cannot hear the render. Produce short A/B clips so the human can
  judge the change.

Package exports can warn about embedded ROM assets or retail song data.
Keep flagged packages for personal use. Check CLI stderr or the MCP
`warnings` array. Use `stock-table build ROM OUT.json` to regenerate the
S3&K hashes and locators. For S1 or S2, add `--driver s1|s2` and
`--rom OTHER_REVISION.gen` to generate the revision union.

Use `compile-package PROJECT OUT.smps.zip --portable` or
`export PROJECT OUT.smps.zip --format portable` for schema 4. Use
`--no-source` to omit the default redacted source. Set `--driver s1|s2|s3k`
(default `s3k`, required for native v5 banks). S1 and S2 convert the S3K tempo
to the nearest native tick rate. Supply `--speed-up-tempo BYTE` on the same
S3K clock for optional speed shoes. Check `TEMPO_APPROXIMATED` diagnostics
for the converted bytes and rate error. Use `--strict-tempo` to reject
inexact normal or speed-shoes tempo conversions. Set `--license SPDX`, `--package-id ID`, `--package-version MAJOR.MINOR.PATCH`
and repeatable `--author NAME` as needed. Use `export --format mod` with
repeatable decimal `--replace ID` from the target game to create an OpenGGF patch mod JAR.

For native v5 S3K retail banks, use `--retail-patch` with either portable
export or `export --format mod`. An edited bank needs `--rom PATH`.
An unchanged bank in the stock table can export without a ROM. The patch
omits source and carries the `DERIVED_FROM_RETAIL_SONG` composition warning.
`copy-arrangement` requires embedded form. OpenGGF still rejects patch form.

Run `inspect-package PKG` to read the manifest and
`validate-package PKG [--rom PATH ...] [--render OUT.wav --frames N]` to
verify hashes, resolve stock references and audition through the pinned
driver. Use `render-package PKG OUT.wav [--rom PATH ...] [--frames N]` for
a direct preview. Pass `--rom PATH` to `validate-project`, `render-project`,
`compile-package` or `export` to resolve redacted source. On package and kit
export, a supplied ROM also verifies references whose bytes are present and
checks transformed results against recorded payloads or hashes. Fix the
reported asset slots before retrying a mismatch. Studio's **Portable SMPS
package**, **OpenGGF mod** and **Disassembly kit** options share this
verification. Studio defaults to the ROM registered for the project driver in
**ROM settings…**. Choose **Use another ROM for this export…** if the stock
assets require a different image. The package review shows asset matches and
provenance warnings before writing. In MCP, use
`project_export` with `format: "portable"` or `"mod"`, optional `driver`
`speedUpTempo`, `strictTempo`, and `retailPatch` (with `roms` for an edited bank).
Use `package_inspect` to see the patch form and base song and
`package_validate` with `roms` to verify the rebuilt bank. Check warnings
even when validation succeeds.

[Portable package CLI and MCP fields](../../headless/README.md#portable-song-packages)

When reclassifying notes, keep detail intents in the shared `edits.json`.
Use stable note IDs. After rebuilding, inspect `orphanedEdits` from
`reconstruct describe WORK_DIR --json` or `reconstruct_describe` for
targets that lost playable gates. Restore a compatible assignment, or remove
the intent and add one with the corrected target.

For the coarse classification pass, use `reconstruct describe WORK_DIR
--roles --json` or `reconstruct_describe` with `roles: true`. This groups
stable note IDs by voice and role. Use the same IDs for detail edits.
In Studio, Roles shows voice colors, patterns and short tags over the same
spectrogram viewport as Detail. Use **Fit pitch** to follow the visible tonal
range or use the pitch zoom buttons and vertical scrollbar to set it manually.
Enable **Show drums** in **Voices** for a separate drum-hit strip. Use the
voice's **⋯ → Edit tag…** to change its tag locally. Click the voice name to
select its notes. Click its assignment arrow, or drag a Roles selection onto
the entry, to move the selection into that voice. Both views share edit history.
