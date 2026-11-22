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

Each audition applies the ordered suffix of stable-note-ID intents with the
existing `setNote`, `transpose`, `bend` and `glide` kernels. It projects changed
notes onto their saved lanes, retains fitted patches and gains, and uses the
canonical S3K assembly and bank compiler. A conflicting gate, a changed
journal prefix, an unsupported edit or a bank limit leaves the built audio
available and displays **Rebuild needed**. **Apply & rebuild** can
reevaluate allocation and other upstream decisions. It updates the built
project, reports, diagnostics and exportable WAV.

Studio saves a checked audition as an atomic `edits.json` replacement under
the existing journal locks and revision check. Undo and redo save the
corresponding journal revision while an audition is active. Proposals keep
their existing statuses. An external accepted edit or rebuild reloads the
snapshot through the existing watcher and can require **Apply & rebuild**
before the next audition.

## Checkpoints and handoff

The pinned OpenGGF session supplies both snapshots. The logical snapshot
covers the driver, per-track sequence and coordination flag state, and
timing accumulators. The physical snapshot covers both chip cores, including
FM operator state and LFO, PSG noise, DAC playback, and resampler history.
Studio captures them together at 60-presentation intervals. The initial
state comes from the normal session activation.

Each revision retains checkpoints strictly before the earliest changed gate.
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
pitch, gate, bend and undo renders. The Studio tests cover journal and
audio-line continuity, including a deliberately late handoff after a
short unlooped note has passed.

Phase 3 can extend the live path to incremental roles and allocation,
external journal updates, patch and proposal A/B, explicit refitting,
and additional drivers.

[Stage and edit contracts](reconstruction-stages.md) ·
[Studio review and transport](../user-guide/studio.md#inspect-and-edit-a-reconstruction)
