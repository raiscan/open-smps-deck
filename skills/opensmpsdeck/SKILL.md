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
covers model setup and config paths. The bake-off recommends SW, MuScriptor
Medium for tonal instruments, Basic Pitch for vocals and ADTOF for drums.
The current audio-only path uses one configured tonal transcriber and the
built-in spectral drum detector. Use the explicit Medium option above for
the integrated baseline.

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
- **View** aligns source and rendered spectrograms with note and lane gates.
  Use `--stem PART` for a mapped stem, `--harmonics NOTE_ID` to trace a
  note's partials and `--ridges` for numerical trajectories. MCP
  `reconstruct_view` returns a PNG and legend using fields such as `stem`,
  `harmonics`, `ridges`, `sections` and `colorBy`. Studio Analysis has
  a source picker, **Ridges**, section bands, and role and lane filters.
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
Use [role plans](../../headless/README.md#inspect-the-score-and-plan-musical-roles)
for recurring lead and backing rules and [external transcription](../../headless/README.md#external-transcription)
when a mapped or unmatched stem needs a separate transcriber.

## 4. Propose or apply a scoped edit

Use `edits.json` for musical intent. Never hand-edit `score.json` or
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
| Fix or reshape attacks | `insertNote`, `deleteNote`, `setNote`, `splitNote`, `mergeNotes` |
| Route a line or register | `setRole`, `swapRoles`, `pinLane` with a lane or `laneType`, `unmerge` |
| Shape articulation and level | `bend`, `glide`, `noteVoicing` for instrument or vibrato overrides, `setVelocity` |
| Layer selected attacks | `layer` with `echo`, `chorus`, `double` or `off` |
| Fit stacked tones | `arpeggio`, `chordInstrument` |
| Change a voice in a reviewable batch | `voiceEdit` |

For a note-scoped echo, choose `laneType: "FM"` or `"PSG"` and set
`delayFrames` and `levelDb` or `attenuation`. To match Studio's default
echo, use 14 frames and 11 TL below dry. Studio shows this as a −3 dB
trim from the original 7-TL echo. A layer edit overrides lane policies for
its notes and rebuilds from `assemble`. In Studio, use **Selection → Layer**,
then **Queue layer** and **Apply**. Check the dry-run and trace results for
skipped copies before review.

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

**Layering on spare channels.** `voicing.json.lanePolicies` adds opt-in
`pads`, `leadEcho`, `psgDouble` and `fmDouble` (chorus or echo) layers on
free PSG and FM lanes, scoped by `voice`, `section` or `span`. The
`s3k-layering` preset combines an octave PSG double with a 14-frame FM echo.
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

Use `export WORK_DIR OUTPUT --format wav|mp3|vgm|smps|package` or MCP
`project_export` with exactly one of `workDir` and `project`. WAV and MP3
support project-second spans, fades and channel stems (`--stems`, optionally
`--zip`). VGM exports the full chip-write stream. Raw S3K SMPS targets
a compatible driver with local voices. Use the package for projects with
external envelope or DAC assets. It preserves bindings, resources and source. Use **Export…** for the same formats in Studio.

For timeline and piano-roll handoff, choose **Open as arrangement**. Saving
a musical hand edit detaches the arrangement. Export uses the detached
project. A rebuild that would replace it requires explicit
`--overwrite-detached` (MCP `options.overwriteDetached` on run/stage
or top-level `overwriteDetached` on replay). Prefer replay to a new
directory when preserving both versions. Studio **Apply** offers a warning
with options to preserve the edited arrangement before rebuilding.
[Export reference](../../headless/README.md#export-project-output)

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
