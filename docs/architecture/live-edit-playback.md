# Live reconstruction playback

Studio auditions pitch, gate and supported bend edits throughout an attached
S3K reconstruction. **Song**, **Section** and **Selection** support live edits
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
on saved lanes when the edited gates fit. Stem-backed edits, repeat and
correction evidence, and measured section gains require canonical replay
because the score changes can affect decisions outside those gates. For a role or lane-pin edit, Studio
can rerun allocation inside complete bars when the original and edited lanes
have independent, non-overlapping gates and no dependency crosses either
edge. Assembly-only vibrato and layer edits reuse the allocation. The bank
always compiles through the S3K arrangement compiler.

Allocation for stem-backed material can depend on notes outside an edited
bar. Other examples include a lane collision, a change to score topology, a
new voice fit, and a modified built journal prefix. In those cases Studio
shows **Rebuild needed** while it replays the canonical stages in a private
directory. The replay uses the same allocation affinity, compatible voicing
and bank size decisions as **Apply & rebuild**. It stops after compiling
the bank. WAV export and listening diagnostics remain part of a published
rebuild. Studio switches to the replayed bank on the existing audio line.

Local audition saves the checked journal atomically under the existing locks
and revision check. Undo and redo save their corresponding journal revision.
The watcher adopts accepted disk edits while the draft is clean and labels
the source of the change. If accepted edits arrive while local edits are
unsaved, Studio retains the local draft and offers the journal conflict
resolution. Proposed edits stay silent until auditioned or accepted.

The A/B controls use the current playhead for history, stock FM voice and
proposal comparisons. They keep the compared changes in memory until the
reviewer applies one. Explicit refit uses the stock fitter on the current
allocation in the background and offers the selected patch as B.

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
rebuilds the missing states from the latest verified checkpoint.

The render worker prepares an exact handoff position. The audio worker
restores it on the open line and switches at the next unqueued block. It
caps queued audio at two presentations on the editable transport. Newer
edits supersede pending work, and each reader belongs to its own worker.
The first pass builds the checkpoint grid in the background. The built
revision and latest edited revision can each retain a grid, with up to
60 checkpoints per minute. Studio retains a transient handoff snapshot
during a swap. Editable playback renders PCM in presentation-sized blocks.

A warm seek restores the closest preceding checkpoint and renders at most
one second of catch-up before playing at the requested sample. Scrubbing
within the active range keeps the line open. A cold seek or a change far
behind the playhead can require a longer catch-up while Studio rebuilds
the invalidated interval.

Set `-Dopensmpsdeck.liveTiming=true` to log milliseconds from the gesture
to the first changed sample written and to observation that the output
line's device frame cursor has passed that sample. The comparison uses
the two revisions at the same project frame. The device cursor measures the audio line boundary. Acoustic output
delay is outside that measurement.

## Verification and next phase

Synthetic tests compare restored PCM with continuous playback from
several seeks and checkpoints. They cover compact and linear packing,
address relocation, invalidation on a late pitch change, and canonical
pitch, gate, bend and undo renders. Synthetic reconstruction tests compare allocation and PCM with a canonical
rebuild after independent-bar role edits, deletion and a mixed journal.
Studio tests use a silent audio line by default and cover late handoffs,
deletion, undo and adoption of an agent edit on the same line.

Further work can extend the window proof to stem-backed allocation,
reduce the cost of invalidated checkpoints, and support live playback
for additional drivers.

[Stage and edit contracts](reconstruction-stages.md) ·
[Studio review and transport](../user-guide/studio.md#inspect-and-edit-a-reconstruction)
