# Live reconstruction playback

Phase 1 adds a short S3K audition to Studio's reconstruction Notes workspace.
The design and exploratory benchmarks in [PR #122](https://github.com/raiscan/open-smps-deck/pull/122)
measured whole-song held-note diagnostics at 125–147 s, journal validation
at 6–13 s, and replay to project second 60 at about 2 s. These operations
run on the canonical build path or fall outside the Phase 1 window.

## Phase 1 window

Studio can audition pitch, gate and supported bends while playing Chip
(or the Chip side of A/B) in the first 2.5 project seconds. Both the
built and edited gate must fit the active loop, or the intersection of
the current play range and the first 2.5 seconds. Song, Section and
Selection use this path. Voice and Lane keep their scoped playback.
A note ahead of the playhead is heard when the transport reaches it.
A note already passed in a loop is heard on the next pass. In normal
playback the monitor resumes the built arrangement at the end of the
live window and displays that transition.

## Assembly and journal

On open, Studio loads the built source score, structure, allocation,
fitted voicing and assembly layout. Preparation checks the assembly
fingerprints and verifies that the reassembled bank agrees with the built
bank, comparing PCM in the first 2.5 seconds if the bytes or bindings
differ. The loaded score already incorporates the built edit prefix.

Each audition evaluates the ordered suffix of stable-note-ID intents
against this score using the existing `setNote`, `transpose`, `bend`
and `glide` kernels. It projects the changed notes onto their saved
lanes, retains the fitted patches and gains, then uses the same S3K
assembly and bank compiler as a rebuild. A conflicting gate, changed
journal prefix, unsupported edit, or bank limit leaves the built audio
available and displays **Rebuild needed**. A canonical **Apply & rebuild**
runs from the first affected stage and can re-evaluate allocation and
other upstream decisions. Only that build publishes an updated project,
reports, diagnostics and exportable WAV.

Studio saves a checked audition as an atomic `edits.json` replacement
under the existing journal locks and revision check. Undo and redo
write the corresponding journal revision while a live audition is
active. Proposals stay in the journal with their existing statuses.
An external accepted edit or rebuild reloads the snapshot through the
existing watcher. A clean reload can require **Apply & rebuild** before
the next audition.

## Native playback and timing

The replacement is a fresh pinned OpenGGF session rendered continuously
from the song origin. The audio worker changes source at the next
unqueued block with two presentations of lookahead, or the remaining
blocks at a loop boundary. It keeps the audio line, playhead and monitor.
The replacement PCM cache ends at the live window boundary. A newer edit
supersedes a pending render, and a retired transport cannot publish to a
new one. The handoff uses the native PCM directly, so there is no crossfade
to account for when checking parity.

Set `-Dopensmpsdeck.liveTiming=true` to log the elapsed milliseconds from
a committed edit or undo/redo event to the first output write containing
a sample different from the previous chip revision at the same project
frame. This includes scheduling against the open line and the time until
the changed material is encountered. It measures delivery to the line.
The device can buffer more audio after the write.

## Verification and next phases

The synthetic parity test compares the preview and undo PCM with the
continuous canonical retained-voicing render. The Studio scene test
exercises journal save, undo, redo and rebuild fallback on one audio line.

Phase 2 extends auditioning to later playheads using exact checkpoints
or another bounded catch-up path. A checkpoint must preserve track
pointers, loops, modulation, DAC and chip state together. Cross-bank
reuse needs a proven unchanged executed prefix and a mapping for each
live program counter and return address. The later gates also cover
30/60/120-second seeks and edits, underruns, and parity of held FM, PSG,
DAC and effect tails.

Later work includes incremental roles and allocation, live external
journal updates, patch and proposal A/B, explicit refitting, and
additional drivers.

[Stage and edit contracts](reconstruction-stages.md) ·
[Studio review and transport](../user-guide/studio.md#inspect-and-edit-a-reconstruction)
