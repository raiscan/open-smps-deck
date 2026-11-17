# Native S3K commands: verification

Verified locally on 2026-11-17 in the existing `codex/reimagined` worktree.
The upstream audio snapshot remains unchanged at
`a1c4aac7ae2708f5c487e8000da80502e854abe2`.

## Delivered boundary

The accepted timeline now has a linked-source Commands tab and piano-roll event
markers. Nine native commands cover pan/AMS/FMS, detune, absolute volume,
chip-specific volume deltas, absolute/additive transpose, custom modulation and
modulation off. Source controls expose existing FM voices, base volume and
RESET/CARRY repetition. Commands participate in common undo/save/live refresh;
unapplied drafts survive same-source external changes and cannot overwrite them.

Strict version 2 JSON adds ordered events and repeat policy. Version 1 keeps its
field set and compiled bytes. The compiler merges events with native time units,
using duration-only HOLD continuations without retriggering or rereading pitch.
The [support matrix](../architecture/s3k-support.md) records the remaining full-S3K
obligations, including pinned-engine limitations. Importing is deferred until
the representation, editing, rendering and dependency-complete export gate.

## Checks and outcomes

- `xvfb-run -a -s '-screen 0 1600x1000x24' mvn -B -pl studio -am verify
  -Dstudio.ui.test=true -Dstudio.snapshot=<absolute PNG>`: **93 Java tests pass**
  (47 project-core, 26 headless, 20 Studio), zero failures/errors/skips. This was
  repeated after the final table styling change. Actual scene events cover Add,
  order changes, undo, Delete routing, source settings, saving, marker selection,
  atomic external replacement, stable IDs and refusal of stale form updates.
- Twelve new headless cases compare compiled source with independently
  handwritten native banks through the production session: PCM and
  presentation-stamped chip writes, event frame, key-on counts, held transpose,
  detune, volume, modulation, same-tick order, long notes, RESET/CARRY, independent
  clips, silent sources, trimmed repeats and the CLI. Negative native alternatives
  establish that retriggering and rereading pitch are detected. Truncated and
  unsupported streams fail before PCM output.
- `python3 tools/verify_studio_dpi.py --scratch <disk scratch>`: **all six packaged
  native pointer cases pass**, including after final styling. Each checks
  output scale/work-area bounds, clip drag, scrolled note entry/resize, command
  Add and Save, with shared-file readback. Covers 100%, desktop 150%/160% and 200%
  property/environment/GDK precedence. Tests run on isolated Xvfb displays.
- Packaged CLI validates/compiles the separate
  [native controls example](../../examples/native-controls.smps.json) to **501
  bytes** and renders 1,800 presentations: 30 seconds of nonzero 48 kHz, 16-bit
  stereo PCM. PCM SHA-256:
  `b2c42150aabf6514ba6701d4300950b89d6c8b2931cd243704ea26bd2ba6360e`.
- `python3 tools/openggf_audio.py check --source <OpenGGF>` and all **8 Python
  tooling tests** pass. Independent original-source/extracted PCM and chip-write
  parity passes for FM, PSG and FM CALL/LOOP, plus production-activation demo PCM.
  Its original golden remains
  `5e455223632e6ef88262ac953f08f9eab181d34f5e59076d60355f80f7219c73`.
  Both finished executable JARs retain every locked source and license hash.
- Workflow YAML, local documentation links and `git diff --check` pass.
  Screenshots were visually inspected, including correcting the table's initial
  unthemed cells; QA PNGs remain under `studio/target/qa/` locally.
- Independent read-only review found no actionable issue in the completed model,
  compiler, render validator or inspected UI integration. Review did not substitute
  for the execution checks above.

## Full-suite comparison and limits

`xvfb-run ... mvn -B --fail-at-end verify -Dstudio.ui.test=true
-Dstudio.snapshot=<absolute PNG>` ran **792 tests, 9 failures, 0 errors, 2 skips**.
All new-path modules passed. Compared exact failure identities, exception types
and assertion messages against the [recorded baseline](2026-11-17-s3k-foundation.md):
the eight absent Drowning-rip assertions and existing DAC subdirectory assertion
are unchanged. The same two legacy timeline-import cases skip. There are no new
or worsened failures. The later style-only change was checked by the focused
reactor and packaged native UI cases; legacy modules were unchanged.

This verifies the delivered command slice against the pinned driver. It does not
certify arbitrary native-console behavior, whole-track S3K support, importing or
physical-device audibility. F0 authoring currently requires an explicit attack
tick, and v2 referenced local FM voice indices must be below 128. These temporary
restrictions and remaining assets/control-flow/timing work are explicit in the
support matrix. Local verification does not establish hosted CI completion.
