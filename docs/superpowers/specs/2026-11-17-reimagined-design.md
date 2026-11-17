# OpenSMPSDeck reboot: direction and first delivery

## Intent

Build a modern SMPS music workspace that a human or an external agent can use
to produce tracks for OpenGGF's actual driver and chips. The primary long-term
workflow is an external agent reconstructing a Suno song as faithfully as the
hardware permits. Human composition is also a first-class use case.

The agent is external. Files, a skill and optionally MCP expose authoring and
headless validation, compilation, rendering and export. No embedded agent,
model account or AI service is required by the application. Valid external
file edits should refresh in the UI while preserving navigation; incomplete
writes must not replace the last valid view, and concurrent human edits must
be recoverable rather than silently overwritten.

## Human editing direction

The user's familiar reference is Adobe Premiere: clips on a timeline, reusable
sequences, nested sections and dragging a loop to fill a duration. A timeline
with a detailed piano roll and SMPS event lanes is the current proposal;
the exact editor and UI toolkit are not yet chosen. Nested multi-channel
sequences may organize per-channel clips, but must expose hardware occupancy
and conflicts. Linked instances versus independent copies needs an explicit
user-facing distinction.

Preserve native SMPS expressiveness, including state inherited across clip
boundaries. Export should bake predictable driver behaviour, with an inspectable
mapping to commands. Calls, loop counters, relative state changes, partial
repetitions and finite hardware channels need validation, not silent clipping
or discarded content. Arbitrary ROM decompilation is not promised to round-trip
through a musical editor without loss.

## Approaches and decision

1. Patch the old tracker in place. Lowest short-term disruption, but preserves
   the disliked navigation and its separately modified, stale audio engine.
2. Establish a headless core from pinned OpenGGF audio and build a new timeline
   over shared project files. This is the selected direction following the
   discussion and independent Claude consultation. Validate the human editor
   early alongside the core, rather than completing an entire backend first.
3. Build a general DAW with embedded transcription/agent orchestration. Too
   broad and conflicts with the requirement that agents be externally controlled.

Sonic 3 & Knuckles is the first dialect. The user accepted that recommendation;
both S2 and S3K are mature in OpenGGF. Compatibility means matching the selected
OpenGGF revision, including its intentional original-driver quirks.

## First delivery: playback foundation

- Pin committed OpenGGF `develop` revision
  `a1c4aac7ae2708f5c487e8000da80502e854abe2` (fetched 2026-11-17).
- Extract the required audio/session/chip/S3K source dependency closure into
  `openggf-audio`, preserving upstream packages and license notices. Record
  every source hash and the sole source patch: remove an unused AudioManager
  import. Read Git objects, never the dirty upstream working tree.
- Keep the legacy application on its existing `synth-core` for now. The new
  module is the pinned foundation for the reboot, not a claim that the old
  app's playback has been upgraded.
- Add a Java 25 headless S3K playback proof with a synthetic FM/PSG demonstration
  and bounded standalone binary-to-WAV operation using the upstream production
  session activation and hardware-frame cadence. Reject inputs outside the
  first CLI's documented supported subset instead of silently omitting assets.
- Compare chip-write and PCM digests against original committed OpenGGF sources
  compiled independently at that revision. This establishes extraction parity,
  not end-to-end gameplay or full Suno reconstruction fidelity.

The vendored core retains its native capabilities. The first CLI deliberately
does not provide a complete validator for arbitrary SMPS programs. Calls,
loops, asset packages and further events can be exposed as their input contract
is implemented. It must distinguish this limitation from a driver limitation.

## Verification and delivery

Use a deterministic non-ROM smoke track, source/provenance checks, extraction
failure tests and focused rendering/CLI tests. Run the existing Maven suite and
compare failures by identity with the pre-change baseline. Preserve the old
checkout and unrelated upstream changes. Deliver a task-branch PR against the
only established destination (`master`); leave it open for review.

## Next slice and open decisions

Next prove external text authoring, live refresh and a minimal editable timeline
using the same short song. This delivery does not select the UI toolkit or lock
the new project schema. Input availability (Suno mix, stems, MIDI), resource
packaging (voices/envelopes/DAC), and musical timing/state policies need further
design. A shared upstream Maven audio module remains a possible later replacement
for controlled source extraction.
