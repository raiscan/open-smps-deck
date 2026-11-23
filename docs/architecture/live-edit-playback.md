# Live reconstruction playback

Studio auditions pitch, gate and supported bend edits throughout an attached
S3K, S1 or S2 reconstruction. **Song**, **Section** and **Selection** support live edits
on Chip and on the Chip side of A/B. Voice and Lane use built scoped playback.
An edit ahead of the playhead is heard on arrival. A loop lets a passed note
be heard on its next pass. If an unlooped note at or just ahead of the gesture
has already passed when the preview is ready, playback briefly re-cues the
edited gate on the same line, including at the end of the song.

Phase 1 limited audition to the first 2.5 project seconds. The exploratory
benchmarks measured about two seconds to replay the first minute from the
origin. [Phase 1 design and measurements](https://github.com/raiscan/open-smps-deck/pull/122)

## Assembly and journal

On open, Studio loads the built score, structure, allocation, fitted voicing
and assembly layout. It checks the assembly fingerprints and verifies that the
rebuilt bank has the same assets and executed events as the attached bank.
The loaded score already incorporates the built edit prefix.

Pitch, gate and supported bends use the existing note-edit kernels and stay
on saved lanes when the edited gates fit. Independent bars can replay roles
and allocation exactly when the lanes have non-overlapping gates and no
dependencies cross the bar edges. Assembly-only vibrato and layer edits reuse
the allocation.

Stem-backed edits can change classification and allocation elsewhere in the
song. Studio first auditions a **Provisional** result using the saved sounds,
the edited attacks and the occupied lanes in their window. An inserted note
can borrow an unpinned backing lane within its bar. A separate worker runs the canonical stages
against the built allocation in a private directory. A newer journal revision
supersedes the replay, including undo and redo. Studio clears the indicator when
the canonical bank reaches the existing audio line, or as soon as it proves
that the two banks execute identically. If they differ, playback continues from
the current playhead with the canonical chip and driver state.

The canonical worker retains source transcription, stem spectra and exact
gate measurements, voice-identification probes and drum attack levels for
unchanged source files. Replay also reuses decoded inputs from unchanged
built artifacts. Changes to the note pitch or
gate invalidate the relevant measurements. Replay uses the same role,
allocation, compatible voicing and bank size decisions as **Apply & rebuild**.
It skips the comparison with an unseeded allocation and the published WAV and
listening diagnostics. Without usable saved voicing or a local representation
of an edit, Studio shows **Rebuild needed** while the canonical replay runs.

Only a checked result publishes the journal under the existing locks and
revision check. Export builds the current journal in a private directory when it differs from
the published build. Every format reads the canonical project from that build.
Use **Apply & rebuild** to publish edits to the project and reports. The watcher
adopts accepted disk edits while the draft is clean and labels the source of
the change. If accepted edits arrive while local edits are unsaved, Studio
preserves the local draft and offers journal conflict resolution. Proposed
edits stay silent until auditioned or accepted.

The A/B controls use the current playhead for history, stock FM voice and
proposal comparisons. They keep the compared changes in memory until the
reviewer applies one. Explicit refit uses the stock fitter on the current
allocation on a separate worker and offers the selected patch as B. New edits
supersede an unfinished refit, and editing stays available while it runs.

## Checkpoints and handoff

The pinned OpenGGF session supplies both snapshots. The logical snapshot
covers the driver, per-track sequence and coordination flag state, and
timing accumulators. The physical snapshot covers both chip cores, including
FM operator state and LFO, PSG noise, DAC playback, and resampler history.
Studio captures them together at 60-presentation intervals. The initial
state comes from the normal session activation.

For edits with exact changed gates, each revision retains checkpoints strictly
before the earliest changed gate. For a replayed bank, Studio uses the
executed-prefix proof to find the last compatible checkpoint.
The compiler may change CALL and LOOP packing or move a voice table when
it recompiles the song. Before reusing a checkpoint, Studio compares each
track's executed event bytes and verifies the asset bindings. It relocates
the next program counter, active return address and loop counter in the
new bank. A failed proof discards that checkpoint and the suffix. Studio
rebuilds the missing states from the latest verified checkpoint. S1 and S2
restore snapshots within a revision. An edited classic bank rebuilds its grid
from the start because cursor relocation is proven only for S3K packing.

The render worker prepares an exact handoff position. The audio worker
restores it on the open line and switches at the next unqueued block. It
caps queued audio at two presentations on the editable transport. Newer
edits supersede pending work, and each reader belongs to its own worker.
The first pass builds the checkpoint grid in the background. The built,
active and six most recent settled revisions can retain grids with up to
60 checkpoints per minute. Canonical handoff uses the longest verified
prefix among those grids. Studio retains a
transient handoff snapshot during a swap. Editable playback renders PCM
in presentation-sized blocks.

A warm seek restores the closest preceding checkpoint and renders at most
one second of catch-up before playing at the requested sample. Scrubbing
within the active range keeps the line open. A cold seek or a change far
behind the playhead can require a longer catch-up while Studio rebuilds
the invalidated interval.

Set `-Dopensmpsdeck.liveTiming=true` to log milliseconds from the gesture
to the first changed sample written and to observation that the output
line's device frame cursor has passed that sample. A separate timing entry
records when the canonical revision takes over or proves equivalent. The
first-sample comparison uses
the two revisions at the same project frame. The device cursor measures the audio line boundary. Acoustic output
delay is outside that measurement.

## Verification

Synthetic tests compare restored PCM with continuous playback from
several seeks and checkpoints. They cover compact and linear packing,
address relocation, invalidation on a late pitch change, and canonical
pitch, gate, bend and undo renders. Synthetic reconstruction tests compare allocation and PCM with a canonical
rebuild after independent-bar role edits, deletion and a mixed journal.
Studio tests use a silent audio line by default and cover late handoffs,
deletion, undo and adoption of an agent edit on the same line.

Use [`bench live-edit`](benchmark.md#live-edit-latency-and-parity) to measure
presentation-aligned replay and verify bank and PCM parity on each target.

[Stage and edit contracts](reconstruction-stages.md) ·
[Studio review and transport](../user-guide/studio.md#inspect-and-edit-a-reconstruction)
