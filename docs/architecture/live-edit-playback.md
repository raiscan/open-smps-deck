# Live reconstruction playback

**Status** — Design and throwaway S3K spike. Studio Analysis should keep playing
while a note, gate, bend, role or sound changes. Aim for a warm p95 of 150 ms
from a local edit to changed audio when the edited material is at the playhead,
with 200 ms as the initial acceptance ceiling. Track readiness separately for
a note outside the current audible window. Its change plays on the next
encounter, including the next loop pass.

## What takes time

Two private audio-only S3K reconstructions, A (153 s) and B (163 s), on a
Ryzen 9 9950X with OpenJDK 25. Measurements use the pipeline at
`fdf20f45`, with an unchanged score and the same initial affinity and saved
voicing each pass. Each case ran in its own JVM with one discarded warm-up and
two measured runs. The jobs overlapped on the same host. Stage figures are
median wall seconds. Indented rows are
components of the preceding stage. The edit probe adds a single pitch change
without rebuilding or rendering and resets the journal between samples.

| Stage or operation | A (s) | B (s) |
| --- | ---: | ---: |
| Roles | 1.76 | 6.51 |
| ↳ Lead continuity | 1.55 | 5.58 |
| Voices (voice identification) | 0.33 | 1.82 |
| Allocation | 0.42 | 2.89 |
| Voicing stage | 49.93 | 355.60 |
| ↳ Fitting call | 37.15 | 191.24 |
| Assembly, including reports and WAV | 142.09 | 166.21 |
| ↳ Prepare arrangement and compile bank | 0.043 | 0.052 |
| ↳ Full-song WAV render and write | 14.36 | 12.78 |
| ↳ Held-note report | 125.39 | 146.77 |
| Edit journal validation and write | 5.80 | 13.36 |
| In-memory apply of the score intent | 0.0013 | 0.0043 |

Host load varied sharply. In case B the two warm passes took 138–573 s for
voicing and 158–174 s for full assembly. Use the numbers to identify
where time goes on this host.

The voice stage searches and probes FM patches and calibrates levels. That
work has value when fitting sound. A pitch, gate or bend tweak can usually
reuse a fitted patch. A full-song WAV render costs seconds even with a warm
JVM. The held-note report additionally renders a full solo pass per relevant
lane. Packaging, hashes, diagnostics and a stretched reference belong to the
build or export path. Role assignment includes a global continuity analysis.
Allocation reads source evidence and runs both the affinity-seeded result
and a fresh comparison for diagnostics. `ReferenceAudio` opens and parses
WAV headers for individual windows. Repeated JSON reloads, WAV reads and
whole-song analysis are avoidable on the edit path. The measurements do
not isolate disk I/O from computation inside those calls.

The journal command's validation runs through `previewEdits`, which can
retranscribe, restructure and reclassify the song for a pitch edit. Its cost
is substantially larger than applying the same resolved score intent to an
already-loaded model. Cache the immutable **pre-edit** transcription and
its evidence, since an existing `score.json` can already contain journal
effects and replaying ordered intents over it can double-apply them.

## Playback path

`StudioPlayback` already streams 48 kHz stereo PCM through one
`SourceDataLine`, tracks device-to-project frame segments, and selects the
monitor per block. It renders a fixed snapshot into a whole-song
`RenderBuffer`, so the active transport cannot adopt a changed bank today.
In Arrange, `PianoRollView` commits a `NoteEvent` through
`ProjectEdits.updateNote` and `ProjectDocument.edit`; `StudioWindow`
stops transport. An arrangement edit may change multiple linked occurrences
and detaches a reconstruction output. Detail and Roles should keep using
stable reconstruction note IDs and authored intents.

Use **a replacement native session and a bounded PCM queue**. The worker
compiles an immutable arrangement through `TargetPlayback`, advances the
pinned OpenGGF session to the replacement frame, and renders a few blocks
ahead. The output worker switches at the first shared unqueued frame with
sufficient preroll, keeping the line, played-frame clock, loop, and monitor.
Each driver session stays on its creating thread. A short, fixed crossfade
at handoff can remove discontinuities. Compare raw streams for parity
before the fade. On a stale revision, underrun risk, seek, or loop wrap,
discard or reschedule the candidate and keep the current stream running.
Coalesce rapid edits and cancel superseded work. Tag PCM by revision, loop pass, mask and absolute project frame.

A bank cannot safely be replaced underneath an arbitrary sequencer state.
Track positions, return stacks, loops, tempo accumulators, modulation,
chip phases and DAC selections reflect the old bytes and bindings.
Compact compilation can relocate later code even for a one-note edit.
A driver reading editable notes directly would also bypass SMPS command
semantics. Rendering independent windows from a reset loses sustaining
notes and chip state. Use windows rendered by continuous native sessions,
with checkpoints as an optimization. Keep same-bank checkpoints for seek
and loops. Snapshots include the original song and asset dependencies. For cross-bank reuse, require a proven unchanged executed
prefix and compatible bindings, plus a mapping for every live program
counter and stack or loop address. Restore the logical and physical state
together through a tested API on the pinned driver. Until that contract
exists, use exact replay from the origin off-thread or a clearly marked
loop audition with bounded preroll. A restart near a late playhead
without state produces a different result.

## Live session and journal

- Load an immutable snapshot of source notes, the source-to-project clock,
  roles, voices, allocation, fitted patches and compiler inputs. Index by
  stable note ID, voice and interval. Cache bounded WAV evidence windows
  by source fingerprint and fit results by target, catalogue, source
  instrument, fit inputs and settings. Keep the previous lane assignment
  seed and fitted voicing explicit.
- Evaluate ordered intents with the shared stage kernels. Invalidate the
  union of the old and new gates, overlapping competitors across lanes,
  chord groups, layer delays, retriggers, and release tails. Seed
  allocation from the last unchanged boundary, including occupied gates,
  affinity and pending attacks. Extend the interval until output and
  carried state converge. Scoped bend, vibrato and compatible patch
  changes can start with shared assembly logic. A role edit may expand to
  a phrase or voice. Global tempo, repeat grouping, voice identification,
  changed fit inputs or bank-size fallback can force a larger rebuild.
- Compile the resulting arrangement to validated native SMPS bytes with
  its asset bindings. Start with whole-bank compilation and measure before
  adding a chunk cache. The full compile and bank-limit rules still apply.
  Follow the [portable package contract](portable-package.md#4-bank) for
  native layout and bindings. Keep exports and imported native banks on the
  existing addressed-bytecode path.
- Use `edits.json` as the sole durable note-edit journal. A Studio draft
  is an in-memory revision of that journal. Audition its validated intents
  immediately and save via the existing revision check, `.edits.lock` and
  atomic replacement. Undo/redo and proposal acceptance use the same
  generation path. Existing `ProjectWatcher` instances already observe
  edits and report replacement. Validate a journal-only update against the
  loaded snapshot so it skips reloading evidence. Adopt accepted changes
  when clean, deduplicate Studio's own write by content, and preserve a
  dirty draft on conflict. Unaccepted proposals enter only an explicit A/B
  revision. The watcher normally debounces for 75 ms, with a 500 ms
  fallback probe. Measure external notification separately from render
  readiness. A completed build may replace the working snapshot only
  when its input revisions and report match.
- Share deterministic kernels, selector ordering, timing quantization,
  affinity seeds, fitted voicing policy, and asset tables with the CLI.
  Compare affected decisions, compiled bytes and pre-fade PCM against a
  full run with those same inputs and policies. Use
  `reconstruct run --from <first-stage> --keep-voicing` when comparing to
  retained voicing, and follow the normal fit path for an explicit refit.
  When a fast audition freezes broader decisions that a rebuild could
  change, mark it **Preview**. Compute the canonical revision in the
  background and reconcile it at the playhead. Explicit or necessary
  refits are separate revisions with their own before/after audio. Keep
  built/exportable state visible until a canonical build completes.

For A/B, retain the built master and an immutable draft or proposal variant
at the same project frame and with the same loop, channel mask and gain.
For patch A/B, branch just the relevant patch intent over the same
arrangement and prewarm both pinned streams. A/B selects the ready stream
at a block boundary and queues a short crossfade. Prepare both streams on a
seek, and keep the reference monitor independent. If revisions have
different time maps, align via source time or require a shared clock
before enabling instant A/B.

## Spike evidence

The [standalone harness](../../tools/spikes/live-edit-playback/README.md)
loads an assembled reconstruction, retunes one held FM1 note by seven
semitones, and shortens it by four ticks during a paced stream.
It builds the replacement only after the edit. Every emitted PCM block
matches the corresponding block of the continuous original or edited
render at the same frame. The reported endpoint is the first block with
an RMS difference of at least 32 PCM units, plus a modeled two-block output
queue (33.3 ms).

| Case | Playhead at edit | Median | Warm range (8 runs) | Replay to 60 s |
| --- | ---: | ---: | ---: | ---: |
| A | 2.20 s | 133.4 ms | 116.7–133.4 ms | 2.38 s |
| B | 1.15 s | 100.0 ms | 83.3–133.6 ms | 1.98 s |

These are headless measurements on the final arrangement, exercising the
same project edit and compiler used by Arrange. Analysis journal resolution,
incremental allocation, a physical device and crossfading are outside this
spike. The discarded first paced trial for B reached 433 ms. Device
latency and distant playheads need separate measurements. Replay to
60 s alone took about 2 s, which rules out replay from song start as the
regular late-playhead path.

## Delivery phases

| PR | Scope and rough size | Acceptance |
| --- | --- | --- |
| 1 | S3K Analysis loop audition with saved instruments, note pitch/gate and scoped bends. Shared in-memory intent and assembly path plus a rolling PCM handoff. Medium, 4–6 engineering days. | Target warm p95 ≤200 ms in an already prepared short loop, including the loaded audio queue. Cursor and reference continue. A/B switches on the same frame, previews are labeled, and save/undo use the journal. |
| 2 | Bounded catch-up at arbitrary playheads, exact native checkpoints and generation-aware seek/loop scheduling. Large, 1–2 weeks plus any upstream API work. | Verify 30/60/120 s edits and seeks, real device latency, and no underruns or stale swaps. Compare held FM, PSG, DAC and effect tails against continuous PCM before fades. Fall back safely when a checkpoint cannot be validated. |
| 3 | Incremental roles/allocation, journal watcher integration, canonical reconciliation and patch A/B. Large, 1–2 weeks. | Local edits converge against the full run with frozen voicing. Test collisions, layers, section boundaries, proposals, external replacement and dirty-draft conflicts. Scope or defer global changes. |
| 4 | Background/explicit refit, full-build review and additional driver support. Medium, 4–7 engineering days. | Refits leave transport running, stay revision-scoped, and can be A/B compared. Exports use the verified canonical revision. S3K defaults pass zero-tolerance benchmark parity and S1/S2 pass their target gates. |

[Stage and edit contracts](reconstruction-stages.md) ·
[Studio review and transport](../user-guide/studio.md#inspect-and-edit-a-reconstruction)
