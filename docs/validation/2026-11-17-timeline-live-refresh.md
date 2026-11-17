# Timeline Studio and live refresh: verification

Verified on 2026-11-17 with JDK 25.0.5 and Maven 3.10.0 in the isolated
`codex/reimagined` worktree. This extends foundation commit
`af4996a80485f2f2c921cb771098c25c193c2b99`; the upstream audio pin is unchanged.

## Passing focused checks

- `mvn -B -pl studio -am verify -Dstudio.ui.test=true -Dstudio.snapshot=<file>`
  under an isolated `xvfb-run -a -s '-screen 0 1600x1000x24'` display:
  **56 Java tests passed**, no errors/failures/skips (33 project-core,
  14 headless, 9 Studio).
- The project tests cover strict JSON/UTF-8/types/unknown fields, IDs/references,
  hardware occupancy, mono notes, sparse hardware lanes, native resets/voice
  order, repeats and partial repeats, split durations/ties, exact 32 KiB limits,
  optimistic saves, real replacement/deletion, dirty conflicts and recovery,
  symlink preservation, bounded IO and watcher shutdown.
- Actual JavaFX events move and extend a clip, edit a linked note and save it.
  Files are atomically replaced while the scene is open. Tests verify invalid
  intermediate writes/recovery, stable clip selection and exact horizontal
  viewport position, missing selection clearing, dirty recovery, cancellation of
  an in-progress drag on external adoption, and FX serialization of read/edit
  versus external refresh. Billion-tick unused sequences and huge ruler metadata
  remain responsive with viewport-sized canvas buffers.
- Native S3K tempo tests cover delay bytes 0, 128 and 192. A slow-track render
  confirms its late note is audible before preview completion. PSG MIDI 60 is
  verified from actual pinned-core PCM at approximately **261.63 Hz**, within
  a 3 Hz tolerance; chip-specific pitch bounds reject clamped/zero-period notes.
- `PackagedStudioSmoke`, launched against the finished Studio JAR plus its test
  helper under isolated Xvfb, opens the committed shared example, lays out the
  scene, captures a PNG and closes its watcher/window. This checks shaded runtime,
  native JavaFX loading and CSS/resources, beyond reactor-class tests. Scene
  snapshots were visually inspected and remain in `studio/target/qa/` locally.
- Packaged headless CLI validates the original example (4 sources, 8 clips,
  **403 compiled bytes**) and renders a 1,800-presentation WAV. The workflow's
  packaged raw-demo smoke still matches the original PCM golden. Every upstream
  embedded source, license/notice and lock hash was checked in **both** executable
  JARs.
- 8 Python extraction tests, exact upstream-object provenance and independent
  source-extraction PCM/chip-write parity still pass. No vendored audio source
  was changed. Local documentation links and workflow YAML were checked.

## Legacy suite comparison

`xvfb-run ... mvn -B --fail-at-end test -Dstudio.ui.test=true
-Dstudio.snapshot=<file>` ran **755 tests, 9 failures, 0 errors, 2 skips**.
The failure identities, assertion types/messages and skip identities match the
[recorded baseline](2026-11-17-s3k-foundation.md): eight failures need the absent
Sonic 2 Drowning rip and one is the existing Windows-style DAC-path parsing issue.
There are no new/worsened failures. The two existing timeline-import skips remain.
Ordinary runs without the explicit UI-test flag additionally skip the display
smoke; CI enables it on a separate Xvfb display.

## Review findings resolved

A fresh read-only review found defects in the initial implementation. Corrections
and tests now cover:

- Applying external state on a background worker could interleave with UI
  read/transform/edit and lose updates. Adoption is serialized on the FX thread.
- A retained drag snapshot could overwrite newer external clip/note fields.
  Project/source adoption now cancels active gestures.
- S3K carry frames delay note expiry; preview length/playhead use
  `(256 - tempo) / 256`, with zero accepted, rather than S2-style pacing.
- PSG and FM have different native table bases. MIDI pitches map separately;
  PSG's hardware-supported source range is 45..117, FM's is 12..106.
- Ruler arithmetic could wrap near integer limits. Visible grid bounds use
  long/double arithmetic; gesture tick calculations saturate before validation.
- Normalized scroll fractions moved the view when the song/source grew.
  Both editors preserve horizontal pixel offsets; removed selections are cleared.
- Delayed playback callbacks are gated by playback generation after Stop/reload.

## Limits

This is an FM/PSG editing slice. Nested multi-channel groups, make-unique, DAC,
external envelopes and arbitrary raw commands remain future work. Repeated source
clips are baked inline rather than optimized into native CALL/LOOP reuse. The
legacy tracker remains separate and continues to use its old engine.

Real audio-device preview was not exercised on the shared desktop; deterministic
PCM playback/export and unavailable-device handling are separate from audibility
on a particular host. The JAR contains JavaFX natives for the build platform,
not an installer for every OS. JavaFX 21 emits module/native-access/deprecation
warnings on JDK 25, but both isolated and packaged launch checks pass.

File saving is optimistic against arbitrary uncooperative external writers, not
an OS-level transaction with them; its final comparison/rename race is documented
in the project-format guide. CI has been configured for these checks; local
verification does not establish a completed hosted run.
